package com.meteorite.unsuspiciousblock.core.loottable.simulation;

import com.meteorite.unsuspiciousblock.core.network.journal.CatalogSimulationDelivery;
import com.meteorite.unsuspiciousblock.core.journal.catalog.ArchaeologyJournalServerCatalog;
import com.meteorite.unsuspiciousblock.core.journal.catalog.CatalogSimulationSubmission;
import com.meteorite.unsuspiciousblock.core.journal.catalog.CatalogSimulationSubmission.Publication;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.List;

import com.meteorite.unsuspiciousblock.core.loottable.catalog.LootTableCatalog.TableDefinition;
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
 * 抽样沿真实战利品表、条件和 Loader 修改器执行，它们访问正在运行的服务端世界，故在主线程推进。
 * <p>
 * 改为在服务端每 tick 末尾（{@code END_SERVER_TICK}）由 {@link #tick(MinecraftServer)} 消费队列，
 * 每个 tick 按固定时间预算推进可续跑任务，复杂表会跨多个 tick 保留进度。
 * <p>
 * <b>去重键是 {@code (表, 输入)} 而不是表</b>（决策 38）：抽样次数与参数进入了输入身份，
 * 同一张表的两个参数组合是两个不同的问题，按表去重会让后一个请求把前一个顶掉。
 * <p>
 * 优先级：HIGH（玩家按需请求）先于 LOW（启动基准填充）。
 * 模拟仅消费与服务器有效资源一致的目录代次。
 */
public final class LootProbabilitySimulationWorker {
    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * 每 tick 用于模拟抽取的软时间预算；单次抽取本身无法被中断。
     */
    private static final long TICK_BUDGET_NANOS = 15_000_000L;

    /** 含玩家等待者的工作上限，覆盖排队和正在运行的任务。 */
    private static final int MAX_PENDING_PLAYER_REQUESTS = 32;

    /**
     * 每个玩家同时可有的在途请求数（决策 15）。连点场景是真实攻击面，限流按玩家计数。
     */
    private static final int MAX_IN_FLIGHT_PER_PLAYER = 2;

    /**
     * 线程 CPU 时间计量器；不支持时相关字段退化为 0，不影响调度行为。
     */
    private static final ThreadMXBean THREAD_MX =
            ManagementFactory.getThreadMXBean();
    private static final boolean CPU_TIME_SUPPORTED = THREAD_MX.isCurrentThreadCpuTimeSupported();

    /**
     * 单例，随服务端生命周期创建/销毁
     */
    private static volatile LootProbabilitySimulationWorker instance;

    /**
     * 高优先级队列（玩家按需请求，插队）
     */
    private final Deque<WorkItem> highQueue = new ArrayDeque<>();
    /**
     * 低优先级队列（启动基准填充）
     */
    private final Deque<WorkItem> lowQueue = new ArrayDeque<>();
    /**
     * 已入队任务索引，键是 {@code (表, 输入键)}，同时用于去重与优先级提升。
     */
    private final Map<String, WorkItem> enqueued = new HashMap<>();
    /**
     * 本轮实际失败的 {@code (表, 输入)}；仅供结束时汇总，不参与重试。
     */
    private final Map<String, String> failedInputs = new LinkedHashMap<>();
    /**
     * 每玩家在途请求计数——入队时加、完成时减；玩家退出时残留由上限自行收敛。
     */
    private final Map<UUID, Integer> playerInFlight = new HashMap<>();

    private WorkItem current;
    private int pendingPlayerTasks;

    private LootProbabilitySimulationWorker() {
    }

    /**
     * 创建并注册单例（服务端启动时调用）
     */
    public static void start() {
        if (instance != null) {
            LOGGER.warn("模拟工作器已在运行，忽略重复 start 调用");
            return;
        }
        instance = new LootProbabilitySimulationWorker();
        LOGGER.info("战利品概率模拟工作器已就绪（主线程 tick 驱动，按 (表, 输入) 去重）");
    }

    /**
     * 销毁单例（服务端停止时调用）
     */
    public static void stop() {
        instance = null;
        LOGGER.info("战利品概率模拟工作器已停止");
    }

    /**
     * 取得当前实例，未启动时返回 null
     */
    public static LootProbabilitySimulationWorker get() {
        return instance;
    }

    /**
     * 便捷方法：若实例存在则驱动一次 tick（供平台 tick 事件调用）
     */
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
                                    long generation, String tableHash, @Nullable UUID requester,
                                    @Nullable SimulationTicket ticket) {
        public SimulationRequest {
            if (tableId == null || rawTable == null || input == null || scenario == null) {
                throw new IllegalArgumentException("模拟请求的表、输入与场景都不可为空");
            }
        }

        /**
         * 去重键——表 + 输入身份。
         */
        public String dedupKey() {
            return tableId + "#" + input.key();
        }
    }

    /**
     * 等待某项工作的最新交互身份，重试替换它而不增加工作或额度。
     */
    public record SimulationTicket(long requestId, long generation, String tableHash) {
    }

    /** 玩家请求的准入结果。 */
    public enum EnqueueOutcome {
        ACCEPTED, REJECTED_QUEUE_FULL, PLAYER_LIMIT
    }

    /**
     * 批量入队（低优先级，启动基准填充用）。
     * 数量由服务端排定，因此不受玩家请求上界约束。
     */
    public void enqueueBatch(List<SimulationRequest> requests) {
        for (SimulationRequest request : requests) {
            WorkItem existing = this.enqueued.get(request.dedupKey());
            if (existing != null) {
                existing.publication = Publication.BASELINE;
                continue;
            }
            WorkItem item = new WorkItem(request, Priority.LOW);
            this.enqueued.put(request.dedupKey(), item);
            this.lowQueue.addLast(item);
        }
    }

    /** 合并相同输入的等待者，并原子更新全局与个人额度。 */
    public EnqueueOutcome enqueuePlayerRequest(SimulationRequest request) {
        String key = request.dedupKey();
        UUID player = request.requester();
        WorkItem item = this.enqueued.get(key);
        if (item != null && item.requesters.containsKey(player)) {
            item.requesters.put(player, request.ticket());
            return EnqueueOutcome.ACCEPTED;
        }
        if (this.playerInFlight.getOrDefault(player, 0) >= MAX_IN_FLIGHT_PER_PLAYER) {
            return EnqueueOutcome.PLAYER_LIMIT;
        }
        boolean takesTaskSlot = item == null || item.requesters.isEmpty();
        if (takesTaskSlot && this.pendingPlayerTasks >= MAX_PENDING_PLAYER_REQUESTS) {
            return EnqueueOutcome.REJECTED_QUEUE_FULL;
        }

        if (item == null) {
            item = new WorkItem(request, Priority.HIGH);
            this.enqueued.put(key, item);
            this.highQueue.addLast(item);
        } else {
            promote(item);
        }
        item.requesters.put(player, request.ticket());
        this.playerInFlight.merge(player, 1, Integer::sum);
        if (takesTaskSlot) {
            this.pendingPlayerTasks++;
        }
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
            existing.publication = Publication.BASELINE;
            promote(existing);
            return;
        }
        WorkItem item = new WorkItem(request, Priority.HIGH);
        this.enqueued.put(request.dedupKey(), item);
        this.highQueue.addLast(item);
    }

    /** 退役任务并返回尚未结束的玩家请求。 */
    public List<CatalogSimulationSubmission> retire() {
        List<CatalogSimulationSubmission> waiting = this.enqueued.values().stream()
                .filter(item -> !item.requesters.isEmpty())
                .map(item -> new CatalogSimulationSubmission(item.request, item.publication, item.requesters))
                .toList();
        highQueue.clear();
        lowQueue.clear();
        current = null;
        enqueued.clear();
        failedInputs.clear();
        playerInFlight.clear();
        pendingPlayerTasks = 0;
        return waiting;
    }

    /**
     * 判断当前是否仍有模拟任务。
     */
    public boolean isBusy() {
        return this.current != null || !this.enqueued.isEmpty();
    }

    /**
     * 由服务端 tick 末尾调用：在主线程按 {@link #TICK_BUDGET_NANOS} 软预算推进任务。
     * 所有操作（模拟、写 SavedData、写 catalog、广播、回调）均在主线程完成，无并发风险。
     */
    public void tick(MinecraftServer server) {
        if (!ArchaeologyJournalServerCatalog.canExecute(server)) return;
        long deadlineNanos = System.nanoTime() + TICK_BUDGET_NANOS;
        long drainedGeneration = 0L;
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
                drainedGeneration = this.current.request.generation();
                this.current = null;
            } else {
                break;
            }
        }
        if (drainedGeneration != 0L && this.current == null && highQueue.isEmpty() && lowQueue.isEmpty()) {
            logFailedSummary();
            CatalogSimulationDelivery.broadcastHash(server, drainedGeneration);
        }
    }

    // 一轮模拟结束时汇总失败输入：明确它们不会自动重试，概率保持"未知"直到下次重载
    private void logFailedSummary() {
        if (this.failedInputs.isEmpty()) {
            return;
        }
        LOGGER.warn("{} 个模拟输入失败，其概率保持未知（不会显示为 0），"
                        + "将在下次数据包重载或 /usb journal catalog rebuild 时重新尝试：{}",
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
                item.job = new LootProbabilitySimulationJob(item.request.tableId(),
                        item.request.rawTable(), level, item.request.scenario(), item.request.input());
            }
            if (!item.job.advance(deadlineNanos)) {
                return false;
            }
            completedResult = item.job.result();
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
        this.enqueued.remove(item.request.dedupKey());
        if (!item.requesters.isEmpty()) {
            this.pendingPlayerTasks--;
            for (UUID waiter : item.requesters.keySet()) {
                this.playerInFlight.computeIfPresent(waiter, (player, count) -> count == 1 ? null : count - 1);
            }
        }
        CatalogSimulationSubmission submission = new CatalogSimulationSubmission(item.request, item.publication, item.requesters);
        CatalogSimulationDelivery.deliver(server, ArchaeologyJournalServerCatalog.commitSimulated(result, submission, server));
        if (result.successful()) {
            // 三个时间各有用处：线程 CPU 回答"这张表本身有多贵"；"有效计算"是墙钟，在服务端启动阶段
            // 会因与区块生成等工作争抢 CPU 而虚高；"跨 tick 历时"还包含 tick 之间的等待。
            LOGGER.info("已完成战利品表 {} 的输入模拟（publication={}, scenario={}, {}），有效计算 {}ms（线程 CPU {}ms），"
                            + "跨 tick 历时 {}ms，剩余队列 {}{}",
                    item.request.tableId(), item.publication, item.request.scenario().key(),
                    item.request.input().params().describe(),
                    activeElapsedMs, cpuElapsedMs, wallElapsedMs,
                    enqueued.size(), item.job != null ? item.job.metricsSummary() : "");
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

    /**
     * 可排队并保留续跑任务状态的工作项。
     */
    private static final class WorkItem {
        private final SimulationRequest request;
        private final Map<UUID, SimulationTicket> requesters = new LinkedHashMap<>();
        private Priority priority;
        private Publication publication;
        private LootProbabilitySimulationJob job;
        private long startedNanos;
        private long activeNanos;
        /**
         * 同一批切片的线程 CPU 时间；用于把"这张表有多贵"从墙钟里分离出来。
         */
        private long activeCpuNanos;

        private WorkItem(SimulationRequest request, Priority priority) {
            this.request = request;
            this.priority = priority;
            this.publication = request.requester() == null ? Publication.BASELINE : Publication.REQUEST_ONLY;
        }
    }

    private enum Priority {
        HIGH,
        LOW
    }

}
