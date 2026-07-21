package com.meteorite.unsuspiciousblock.loottable.simulation;

import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.TableDefinition;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 战利品概率模拟主线程 tick 驱动器。
 * <p>
 * 不使用后台线程——因为 {@code LootTable.getRandomItems} 与 {@code LootContextParamFiller}
 * 会触碰 {@code ServerLevel} 关联的 {@link net.minecraft.world.level.levelgen.LegacyRandomSource}，
 * 后台线程与主线程 tick 并发会触发 {@link net.minecraft.util.ThreadingDetector} 报错。
 * <p>
 * 改为在服务端每 tick 末尾（{@code END_SERVER_TICK}）由 {@link #tick(MinecraftServer)} 消费队列，
 * 每次最多处理 {@link #MAX_TABLES_PER_TICK} 个表，单表模拟（10 000 次抽取）约 1-3ms，
 * 不会显著影响 tick 预算。
 * <p>
 * 优先级：HIGH（玩家解锁插队）先于 LOW（启动批量填充）。
 * 数据包重载期间通过 {@link #pauseForReload()} / {@link #resumeAfterReload()} 暂停消费。
 */
public final class LootProbabilitySimulationWorker {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** 每 tick 最多处理的表数，避免单 tick 占用过多时间 */
    private static final int MAX_TABLES_PER_TICK = 1;

    /** 单例，随服务端生命周期创建/销毁 */
    private static volatile LootProbabilitySimulationWorker instance;

    /** 高优先级队列（玩家解锁触发，插队） */
    private final Deque<SimTask> highQueue = new ArrayDeque<>();
    /** 低优先级队列（启动批量填充） */
    private final Deque<SimTask> lowQueue = new ArrayDeque<>();
    /** 已入队去重集合，避免同一表重复入队 */
    private final Set<ResourceLocation> enqueued = new HashSet<>();
    private volatile boolean paused = false;
    /** 模拟结果回调（由调用方设置，如写入目录、广播等） */
    private volatile ResultHandler resultHandler;
    /** 当前队列全部处理完毕后的回调 */
    private volatile Consumer<MinecraftServer> queueDrainedHandler;
    /** 调试用进度回调（由 /usb journal reload 设置），可能为 null */
    private volatile ProgressListener progressListener;

    private LootProbabilitySimulationWorker() {
    }

    /** 创建并注册单例（服务端启动时调用） */
    public static void start() {
        if (instance != null) {
            LOGGER.warn("模拟工作器已在运行，忽略重复 start 调用");
            return;
        }
        instance = new LootProbabilitySimulationWorker();
        LOGGER.info("战利品概率模拟工作器已就绪（主线程 tick 驱动）");
    }

    /** 销毁单例（服务端停止时调用） */
    public static void stop() {
        instance = null;
        LOGGER.info("战利品概率模拟工作器已停止");
    }

    /** 取得当前实例，未启动时返回 null */
    public static LootProbabilitySimulationWorker get() {
        return instance;
    }

    /** 便捷方法：若实例存在则驱动一次 tick（供平台 tick 事件调用） */
    public static void tickIfPresent(MinecraftServer server) {
        LootProbabilitySimulationWorker worker = instance;
        if (worker != null) worker.tick(server);
    }

    /**
     * 批量入队（低优先级，启动填充用）。
     * 调用方需自行提供 raw table 定义。
     */
    public void enqueueBatch(Map<ResourceLocation, TableDefinition> tables) {
        if (tables.isEmpty()) return;
        for (Map.Entry<ResourceLocation, TableDefinition> entry : tables.entrySet()) {
            ResourceLocation tableId = entry.getKey();
            if (enqueued.contains(tableId)) continue;
            enqueued.add(tableId);
            lowQueue.addLast(new SimTask(tableId, entry.getValue()));
        }
    }

    /**
     * 单表插队入队（高优先级，玩家解锁触发）。
     * 调用方需自行检查是否已有模拟数据，并提供 raw table 定义。
     */
    public void enqueuePriority(ResourceLocation tableId, TableDefinition rawTable) {
        if (enqueued.contains(tableId)) return;
        enqueued.add(tableId);
        highQueue.addLast(new SimTask(tableId, rawTable));
    }

    /** 清空队列（reload/flush 时丢弃旧任务） */
    public void clearQueue() {
        highQueue.clear();
        lowQueue.clear();
        enqueued.clear();
    }

    /** 暂停消费（数据包重载前调用） */
    public void pauseForReload() {
        paused = true;
    }

    /** 恢复消费（数据包重载完成后调用） */
    public void resumeAfterReload() {
        paused = false;
    }

    /** 设置模拟结果回调 */
    public void setResultHandler(ResultHandler handler) {
        this.resultHandler = handler;
    }

    /** 设置队列排空回调，用于合并一批模拟产生的后续操作 */
    public void setQueueDrainedHandler(Consumer<MinecraftServer> handler) {
        this.queueDrainedHandler = handler;
    }

    /** 设置调试用进度回调 */
    public void setProgressListener(ProgressListener listener) {
        this.progressListener = listener;
    }

    /**
     * 由服务端 tick 末尾调用：在主线程消费最多 {@link #MAX_TABLES_PER_TICK} 个任务。
     * 所有操作（模拟、写 SavedData、写 catalog、广播、回调）均在主线程完成，无并发风险。
     */
    public void tick(MinecraftServer server) {
        if (paused) return;
        boolean processed = false;
        ServerLevel level = server.overworld();
        for (int i = 0; i < MAX_TABLES_PER_TICK; i++) {
            SimTask task = highQueue.pollFirst();
            if (task == null) {
                task = lowQueue.pollFirst();
            }
            if (task == null) break;
            process(server, level, task);
            processed = true;
        }
        if (processed && highQueue.isEmpty() && lowQueue.isEmpty()) {
            Consumer<MinecraftServer> handler = this.queueDrainedHandler;
            if (handler != null) {
                handler.accept(server);
            }
        }
    }

    private void process(MinecraftServer server, ServerLevel level, SimTask task) {
        long startNanos = System.nanoTime();
        try {
            LootProbabilitySimulator.SimResult result =
                    LootProbabilitySimulator.simulateOne(task.tableId, task.rawTable, level);
            ResultHandler handler = this.resultHandler;
            if (handler != null) {
                handler.handle(result, server);
            }
            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
            LOGGER.info("已完成战利品表 {} 的概率模拟，耗时 {}ms，剩余队列 {}",
                    task.tableId, elapsedMs, enqueued.size() - 1);
            ProgressListener listener = this.progressListener;
            if (listener != null) {
                listener.onTableSimulated(result.tableId(), result.result());
            }
        } catch (Throwable t) {
            LOGGER.error("模拟战利品表 {} 时异常", task.tableId, t);
        } finally {
            enqueued.remove(task.tableId);
        }
    }

    /** 队列任务 */
    private record SimTask(ResourceLocation tableId, TableDefinition rawTable) {
    }

    /** 模拟结果回调（在主线程调用） */
    @FunctionalInterface
    public interface ResultHandler {
        void handle(LootProbabilitySimulator.SimResult result, MinecraftServer server);
    }

    /** 调试用进度回调（在主线程调用） */
    public interface ProgressListener {
        /** 单表模拟完成 */
        void onTableSimulated(ResourceLocation tableId, TableDefinition result);
    }
}
