package com.meteorite.unsuspiciousblock.loottable.simulation;

import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.TableDefinition;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 战利品概率模拟主线程 tick 驱动器。
 * <p>
 * 不使用后台线程——因为 {@code LootTable.getRandomItems} 与 {@code LootContextParamFiller}
 * 会触碰 {@code ServerLevel} 关联的 {@link net.minecraft.world.level.levelgen.LegacyRandomSource}，
 * 后台线程与主线程 tick 并发会触发 {@link net.minecraft.util.ThreadingDetector} 报错。
 * <p>
 * 改为在服务端每 tick 末尾（{@code END_SERVER_TICK}）由 {@link #tick(MinecraftServer)} 消费队列，
 * 每个 tick 按固定时间预算推进可续跑任务，复杂表会跨多个 tick 保留进度。
 * <p>
 * 优先级：HIGH（玩家解锁插队）先于 LOW（启动批量填充）。
 * 数据包重载期间通过 {@link #pauseForReload()} / {@link #resumeAfterReload()} 暂停消费。
 */
public final class LootProbabilitySimulationWorker {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** 每 tick 用于模拟抽取的软时间预算；单次抽取本身无法被中断。 */
    private static final long TICK_BUDGET_NANOS = 15_000_000L;

    /** 单例，随服务端生命周期创建/销毁 */
    private static volatile LootProbabilitySimulationWorker instance;

    /** 高优先级队列（玩家解锁触发，插队） */
    private final Deque<WorkItem> highQueue = new ArrayDeque<>();
    /** 低优先级队列（启动批量填充） */
    private final Deque<WorkItem> lowQueue = new ArrayDeque<>();
    /** 已入队任务索引，同时用于去重与优先级提升。 */
    private final Map<ResourceLocation, WorkItem> enqueued = new HashMap<>();
    private WorkItem current;
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
            if (enqueued.containsKey(tableId)) continue;
            WorkItem item = new WorkItem(tableId, entry.getValue(), Priority.LOW);
            enqueued.put(tableId, item);
            lowQueue.addLast(item);
        }
    }

    /**
     * 单表插队入队（高优先级，玩家解锁触发）。
     * 调用方需自行检查是否已有模拟数据，并提供 raw table 定义。
     */
    public void enqueuePriority(ResourceLocation tableId, TableDefinition rawTable) {
        WorkItem existing = enqueued.get(tableId);
        if (existing != null) {
            promote(existing);
            return;
        }
        WorkItem item = new WorkItem(tableId, rawTable, Priority.HIGH);
        enqueued.put(tableId, item);
        highQueue.addLast(item);
    }

    /** 清空队列（reload/flush 时丢弃旧任务） */
    public void clearQueue() {
        highQueue.clear();
        lowQueue.clear();
        current = null;
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

    /** 判断当前是否仍有模拟任务或正处于 reload 暂停阶段。 */
    public boolean isBusy() {
        return this.paused || this.current != null || !this.enqueued.isEmpty();
    }

    /**
     * 由服务端 tick 末尾调用：在主线程按 {@link #TICK_BUDGET_NANOS} 软预算推进任务。
     * 所有操作（模拟、写 SavedData、写 catalog、广播、回调）均在主线程完成，无并发风险。
     */
    public void tick(MinecraftServer server) {
        if (paused) return;
        long deadlineNanos = System.nanoTime() + TICK_BUDGET_NANOS;
        boolean completedAny = false;
        ServerLevel level = server.overworld();

        // 当前低优先级任务只在 tick 边界被抢占，任务对象保留全部模拟进度。
        if (this.current != null && this.current.priority == Priority.LOW && !this.highQueue.isEmpty()) {
            this.lowQueue.addFirst(this.current);
            this.current = null;
        }

        boolean attempted = false;
        while (!attempted || System.nanoTime() < deadlineNanos) {
            attempted = true;
            if (this.current == null) {
                this.current = pollNext();
            }
            if (this.current == null) {
                break;
            }
            if (advanceCurrent(server, level, deadlineNanos)) {
                completedAny = true;
                this.current = null;
            } else {
                break;
            }
        }
        if (completedAny && this.current == null && highQueue.isEmpty() && lowQueue.isEmpty()) {
            Consumer<MinecraftServer> handler = this.queueDrainedHandler;
            if (handler != null) {
                handler.accept(server);
            }
        }
    }

    private WorkItem pollNext() {
        WorkItem item = this.highQueue.pollFirst();
        return item != null ? item : this.lowQueue.pollFirst();
    }

    // 返回 true 表示当前任务已经完成或失败，可继续消费预算内的下一任务。
    private boolean advanceCurrent(MinecraftServer server, ServerLevel level, long deadlineNanos) {
        WorkItem item = this.current;
        if (item.startedNanos == 0L) {
            item.startedNanos = System.nanoTime();
        }
        long sliceStartNanos = System.nanoTime();
        LootProbabilitySimulator.SimResult completedResult;
        try {
            if (item.job == null) {
                item.job = LootProbabilitySimulator.createJob(item.tableId, item.rawTable, level);
            }
            if (!item.job.advance(deadlineNanos)) {
                return false;
            }
            completedResult = item.job.result();
        } catch (Throwable t) {
            LOGGER.error("模拟战利品表 {} 时异常", item.tableId, t);
            completedResult = LootProbabilitySimulator.SimResult.failure(item.tableId, item.rawTable);
        } finally {
            item.activeNanos += System.nanoTime() - sliceStartNanos;
        }
        complete(server, item, completedResult);
        return true;
    }

    private void complete(MinecraftServer server, WorkItem item,
                          LootProbabilitySimulator.SimResult result) {
        long wallElapsedMs = item.startedNanos == 0L ? 0L
                : (System.nanoTime() - item.startedNanos) / 1_000_000L;
        long activeElapsedMs = item.activeNanos / 1_000_000L;
        if (result.successful()) {
            ResultHandler handler = this.resultHandler;
            if (handler != null) {
                handler.handle(result, server);
            }
            LOGGER.info("已完成战利品表 {} 的概率模拟，有效计算 {}ms，跨 tick 历时 {}ms，剩余队列 {}",
                    item.tableId, activeElapsedMs, wallElapsedMs,
                    Math.max(0, enqueued.size() - 1));
        } else {
            LOGGER.warn("战利品表 {} 的概率模拟未成功，不写入缓存，有效计算 {}ms，跨 tick 历时 {}ms",
                    item.tableId, activeElapsedMs, wallElapsedMs);
        }
        ProgressListener listener = this.progressListener;
        if (listener != null) {
            listener.onTableSimulated(result.tableId(), result.result());
        }
        this.enqueued.remove(item.tableId);
    }

    private void promote(WorkItem item) {
        if (item.priority == Priority.HIGH) {
            return;
        }
        item.priority = Priority.HIGH;
        if (item != this.current && this.lowQueue.remove(item)) {
            this.highQueue.addFirst(item);
        }
    }

    /** 可排队并保留续跑任务状态的工作项。 */
    private static final class WorkItem {
        private final ResourceLocation tableId;
        private final TableDefinition rawTable;
        private Priority priority;
        private LootProbabilitySimulationJob job;
        private long startedNanos;
        private long activeNanos;

        private WorkItem(ResourceLocation tableId, TableDefinition rawTable, Priority priority) {
            this.tableId = tableId;
            this.rawTable = rawTable;
            this.priority = priority;
        }
    }

    private enum Priority {
        HIGH,
        LOW
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
