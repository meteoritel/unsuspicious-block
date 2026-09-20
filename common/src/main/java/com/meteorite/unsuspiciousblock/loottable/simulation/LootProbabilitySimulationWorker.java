package com.meteorite.unsuspiciousblock.loottable.simulation;

import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.TableDefinition;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

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
 * <b>去重键是 {@code (表, 输入)} 而不是表</b>（决策 38）：抽样次数与参数进入了输入身份，
 * 同一张表的两个参数组合是两个不同的问题，按表去重会让后一个请求把前一个顶掉。
 * <p>
 * 优先级：HIGH（玩家按需请求）先于 LOW（启动基准填充）。
 * 数据包重载期间通过 {@link #pauseForReload()} / {@link #resumeAfterReload()} 暂停消费。
 */
public final class LootProbabilitySimulationWorker {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** 每 tick 用于模拟抽取的软时间预算；单次抽取本身无法被中断。 */
    private static final long TICK_BUDGET_NANOS = 15_000_000L;

    /**
     * 玩家请求在 HIGH 队列中的待处理上界（决策 15）。
     * <p>
     * 只约束**玩家触发**的部分，不约束启动批量填充：启动批次由服务端自己排定、数量等于收录表数，
     * 把上界也套在它身上只会让"表很多"被误判成"被刷爆"。真正要防的攻击面是连点场景刷爆 tick 预算，
     * 那正好全部落在 HIGH 队列里。
     */
    private static final int MAX_PENDING_PLAYER_REQUESTS = 32;

    /** 每个玩家同时可有的在途请求数（决策 15）。连点场景是真实攻击面，限流按玩家计数。 */
    private static final int MAX_IN_FLIGHT_PER_PLAYER = 2;

    /** 线程 CPU 时间计量器；不支持时相关字段退化为 0，不影响调度行为。 */
    private static final java.lang.management.ThreadMXBean THREAD_MX =
            java.lang.management.ManagementFactory.getThreadMXBean();
    private static final boolean CPU_TIME_SUPPORTED = THREAD_MX.isCurrentThreadCpuTimeSupported();

    /** 单例，随服务端生命周期创建/销毁 */
    private static volatile LootProbabilitySimulationWorker instance;

    /** 高优先级队列（玩家按需请求，插队） */
    private final Deque<WorkItem> highQueue = new ArrayDeque<>();
    /** 低优先级队列（启动基准填充） */
    private final Deque<WorkItem> lowQueue = new ArrayDeque<>();
    /** 已入队任务索引，键是 {@code (表, 输入键)}，同时用于去重与优先级提升。 */
    private final Map<String, WorkItem> enqueued = new HashMap<>();
    /** 本轮实际失败的 {@code (表, 输入)}；仅供结束时汇总，不参与重试。 */
    private final Map<String, String> failedInputs = new LinkedHashMap<>();
    /** 每玩家在途请求计数——入队时加、完成时减；玩家退出时残留由上限自行收敛。 */
    private final Map<UUID, Integer> playerInFlight = new HashMap<>();

    private WorkItem current;
    private volatile boolean paused = false;
    /** 模拟结果回调（由调用方设置，如写入目录、广播等） */
    private volatile ResultHandler resultHandler;
    /** 当前队列全部处理完毕后的回调 */
    private volatile java.util.function.Consumer<MinecraftServer> queueDrainedHandler;
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
        LOGGER.info("战利品概率模拟工作器已就绪（主线程 tick 驱动，按 (表, 输入) 去重）");
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
     * 一个待模拟的输入。
     *
     * @param generation 该任务所属的目录代次，结果提交前必须校验
     * @param tableHash  该表的当前内容哈希，结果写入缓存前必须校验（哈希变了说明输入已被淘汰）
     * @param requester  触发者；启动批量填充为 {@code null}
     */
    public record SimulationRequest(ResourceLocation tableId, TableDefinition rawTable,
                                    SimulationInput input, SimulationScenario scenario,
                                    long generation, String tableHash, @Nullable UUID requester) {
        public SimulationRequest {
            if (tableId == null || rawTable == null || input == null || scenario == null) {
                throw new IllegalArgumentException("模拟请求的表、输入与场景都不可为空");
            }
        }

        /** 去重键——表 + 输入身份。 */
        public String dedupKey() {
            return tableId + "#" + input.key();
        }
    }

    /** 入队结果——调用方据此区分"已排队"与"被限流拒绝"。 */
    public enum EnqueueOutcome {
        /** 已入队（或已在队列中被提升优先级）。 */
        ACCEPTED,
        /** 玩家请求超出待处理上界，已拒绝。 */
        REJECTED_QUEUE_FULL
    }

    /**
     * 批量入队（低优先级，启动基准填充用）。
     * 数量由服务端排定，因此不受玩家请求上界约束。
     */
    public void enqueueBatch(java.util.List<SimulationRequest> requests) {
        for (SimulationRequest request : requests) {
            WorkItem existing = this.enqueued.get(request.dedupKey());
            if (existing != null) {
                continue;
            }
            WorkItem item = new WorkItem(request, Priority.LOW);
            this.enqueued.put(request.dedupKey(), item);
            this.lowQueue.addLast(item);
        }
    }

    /**
     * 单条玩家请求入队（高优先级）。
     * HIGH 队列达到 {@link #MAX_PENDING_PLAYER_REQUESTS} 时拒绝，而不是无限堆积。
     */
    public EnqueueOutcome enqueuePlayerRequest(SimulationRequest request) {
        String key = request.dedupKey();
        WorkItem existing = this.enqueued.get(key);
        if (existing != null) {
            // 队列里已有同一个 (表, 输入)：不重复排一次，但要把这位玩家登记为等待者——
            // 否则"启动批量正在算的正好是我要的那个输入"会让玩家点了却永远收不到结果。
            promote(existing);
            if (request.requester() != null) {
                existing.extraRequesters.add(request.requester());
                addInFlight(request.requester(), 1);
            }
            return EnqueueOutcome.ACCEPTED;
        }
        if (pendingPlayerRequests() >= MAX_PENDING_PLAYER_REQUESTS) {
            return EnqueueOutcome.REJECTED_QUEUE_FULL;
        }
        WorkItem item = new WorkItem(request, Priority.HIGH);
        if (request.requester() != null) {
            item.extraRequesters.add(request.requester());
            addInFlight(request.requester(), 1);
        }
        this.enqueued.put(key, item);
        this.highQueue.addLast(item);
        return EnqueueOutcome.ACCEPTED;
    }

    /**
     * 游戏事件驱动的插队（如玩家解锁一张表）。
     * <p>
     * 与 {@link #enqueuePlayerRequest} 的区别是它**不**计入玩家的按需额度、也不受玩家队列上界约束：
     * 那套限流防的是"连点场景刷爆 tick 预算"，而解锁是游戏进程自然发生的、每张表至多一次的事件。
     * 把它也算进额度会让"刚解锁一张表"直接吃掉玩家一半的提问额度。
     */
    public void enqueuePriority(SimulationRequest request) {
        WorkItem existing = this.enqueued.get(request.dedupKey());
        if (existing != null) {
            promote(existing);
            return;
        }
        WorkItem item = new WorkItem(request, Priority.HIGH);
        this.enqueued.put(request.dedupKey(), item);
        this.highQueue.addLast(item);
    }

    /** 某玩家当前在途（排队或计算中）的请求数。 */
    public int inFlightFor(UUID playerId) {
        return this.playerInFlight.getOrDefault(playerId, 0);
    }

    /** 该玩家是否还能再发一个请求（决策 15 的每玩家在途上界）。 */
    public boolean canAcceptFor(UUID playerId) {
        return inFlightFor(playerId) < MAX_IN_FLIGHT_PER_PLAYER;
    }

    private void addInFlight(UUID playerId, int delta) {
        if (playerId == null) {
            return;
        }
        int next = this.playerInFlight.getOrDefault(playerId, 0) + delta;
        if (next <= 0) {
            this.playerInFlight.remove(playerId);
        } else {
            this.playerInFlight.put(playerId, next);
        }
    }

    /** 当前 HIGH 队列中待处理（含正在跑）的玩家请求数。 */
    public int pendingPlayerRequests() {
        int count = this.highQueue.size();
        if (this.current != null && this.current.priority == Priority.HIGH) {
            count++;
        }
        return count;
    }

    /** 清空队列（reload/flush 时丢弃旧任务） */
    public void clearQueue() {
        highQueue.clear();
        lowQueue.clear();
        current = null;
        enqueued.clear();
        failedInputs.clear();
        playerInFlight.clear();
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
    public void setQueueDrainedHandler(java.util.function.Consumer<MinecraftServer> handler) {
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
            logFailedSummary();
            java.util.function.Consumer<MinecraftServer> handler = this.queueDrainedHandler;
            if (handler != null) {
                handler.accept(server);
            }
        }
    }

    // 一轮模拟结束时汇总失败输入：明确它们不会自动重试，概率保持"未知"直到下次重载
    private void logFailedSummary() {
        if (this.failedInputs.isEmpty()) {
            return;
        }
        LOGGER.warn("{} 个模拟输入失败，其概率保持未知（不会显示为 0），"
                        + "将在下次数据包重载或 /usb journal reload 时重新尝试：{}",
                this.failedInputs.size(), String.join(", ", this.failedInputs.keySet()));
        this.failedInputs.clear();
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
        long sliceStartCpuNanos = currentThreadCpuTime();
        LootProbabilitySimulator.SimResult completedResult;
        try {
            if (item.job == null) {
                item.job = LootProbabilitySimulator.createJob(item.request.tableId(),
                        item.request.rawTable(), level, item.request.input(), item.request.scenario());
            }
            if (!item.job.advance(deadlineNanos)) {
                return false;
            }
            completedResult = new LootProbabilitySimulator.SimResult(item.request.tableId(),
                    item.request.input(), item.job.result(), true);
        } catch (Throwable t) {
            LOGGER.error("模拟战利品表 {} 的输入 {} 时异常",
                    item.request.tableId(), item.request.input().key(), t);
            completedResult = LootProbabilitySimulator.SimResult
                    .failure(item.request.tableId(), item.request.input());
        } finally {
            item.activeNanos += System.nanoTime() - sliceStartNanos;
            item.activeCpuNanos += currentThreadCpuTime() - sliceStartCpuNanos;
        }
        complete(server, item, completedResult);
        return true;
    }

    private void complete(MinecraftServer server, WorkItem item,
                          LootProbabilitySimulator.SimResult result) {
        long wallElapsedMs = item.startedNanos == 0L ? 0L
                : (System.nanoTime() - item.startedNanos) / 1_000_000L;
        long activeElapsedMs = item.activeNanos / 1_000_000L;
        long cpuElapsedMs = item.activeCpuNanos / 1_000_000L;
        if (result.successful()) {
            ResultHandler handler = this.resultHandler;
            if (handler != null) {
                // 原始触发者先收（requester 为 null 即启动批量填充，调用方据此发布到共享目录），
                // 其余等待者各自收一份——按内容去重的缓存是全服共享的，但"当前展示哪个输入"
                // 是每个玩家自己的选择，所以结果要回到每个提出请求的人手里。
                handler.handle(result, item.request.generation(), item.request.requester(), server);
                for (UUID waiter : item.extraRequesters) {
                    if (!waiter.equals(item.request.requester())) {
                        handler.handle(result, item.request.generation(), waiter, server);
                    }
                }
            }
            // 无论成功与否都要释放每玩家在途额度，否则一次失败会让这位玩家永久被限流
            for (UUID waiter : item.extraRequesters) {
                addInFlight(waiter, -1);
            }
            // 三个时间各有用处：线程 CPU 回答"这张表本身有多贵"；"有效计算"是墙钟，在服务端启动阶段
            // 会因与区块生成等工作争抢 CPU 而虚高；"跨 tick 历时"还包含 tick 之间的等待。
            LOGGER.info("已完成战利品表 {} 的输入模拟（scenario={}, {}），有效计算 {}ms（线程 CPU {}ms），"
                            + "跨 tick 历时 {}ms，剩余队列 {}{}",
                    item.request.tableId(), item.request.scenario().key(),
                    item.request.input().params().describe(),
                    activeElapsedMs, cpuElapsedMs, wallElapsedMs,
                    Math.max(0, enqueued.size() - 1), item.job != null ? item.job.metricsSummary() : "");
        } else {
            // 失败输入不会在本轮重试：能确定性失败的情形（注册表中没有该表、条件求值抛异常）
            // 用同一份输入重跑只会再失败一次并持续占用 tick 预算。这里只如实记录，
            // 由本轮结束时的汇总说明其后续行为。
            String reason = item.request.tableId() + " (scenario=" + item.request.scenario().key()
                    + ", " + item.request.input().params().describe() + ")";
            this.failedInputs.put(reason, result.input().key());
            LOGGER.warn("战利品表 {} 的输入模拟未成功，不写入缓存，有效计算 {}ms（线程 CPU {}ms），跨 tick 历时 {}ms",
                    item.request.tableId(), activeElapsedMs, cpuElapsedMs, wallElapsedMs);
        }
        ProgressListener listener = this.progressListener;
        if (listener != null) {
            listener.onTableSimulated(result.tableId(), result.measurement());
        }
        this.enqueued.remove(item.request.dedupKey());
    }

    // 当前线程已消耗的 CPU 时间；平台不支持时返回 0，日志退化为只报墙钟时间
    private static long currentThreadCpuTime() {
        return CPU_TIME_SUPPORTED ? THREAD_MX.getCurrentThreadCpuTime() : 0L;
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
        private final SimulationRequest request;
        /** 与原始触发者之外、还等着这一份结果的其他玩家（按内容去重后的复用者）。 */
        private final java.util.Set<UUID> extraRequesters = new java.util.LinkedHashSet<>();
        private Priority priority;
        private LootProbabilitySimulationJob job;
        private long startedNanos;
        private long activeNanos;
        /** 同一批切片的线程 CPU 时间；用于把"这张表有多贵"从墙钟里分离出来。 */
        private long activeCpuNanos;

        private WorkItem(SimulationRequest request, Priority priority) {
            this.request = request;
            this.priority = priority;
        }
    }

    private enum Priority {
        HIGH,
        LOW
    }

    /**
     * 模拟结果回调（在主线程调用）。
     * <p>
     * 携带 generation 与触发者：调用方据此丢弃旧代结果，并把按需结果只回给请求者
     * （按内容去重的缓存是全服共享的，但"当前展示哪个输入"是每个玩家自己的选择）。
     */
    @FunctionalInterface
    public interface ResultHandler {
        void handle(LootProbabilitySimulator.SimResult result, long generation,
                    @Nullable UUID requester, MinecraftServer server);
    }

    /** 调试用进度回调（在主线程调用） */
    public interface ProgressListener {
        /** 单表单个输入模拟完成 */
        void onTableSimulated(ResourceLocation tableId, SimulationMeasurement measurement);
    }
}
