package com.meteorite.unsuspiciousblock.client.ui.support;

import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.core.network.journal.JournalWireNbt;
import com.meteorite.unsuspiciousblock.core.journal.catalog.CatalogIdentity;
import com.meteorite.unsuspiciousblock.core.journal.catalog.CatalogReadSnapshot;
import net.minecraft.core.RegistryAccess;
import com.meteorite.unsuspiciousblock.core.network.payload.s2c.JournalTransferPayload;
import com.meteorite.unsuspiciousblock.core.network.payload.s2c.JournalTransferPayload.Kind;
import net.minecraft.nbt.CompoundTag;
import java.io.IOException;
import com.meteorite.unsuspiciousblock.client.ui.entry.ArchaeologyJournalEntry;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.LootTableCatalog;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.CatalogTableDto;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.CatalogQueryIndex;
import com.meteorite.unsuspiciousblock.core.loottable.signature.SignatureExcludedComponents;
import com.meteorite.unsuspiciousblock.core.journal.state.ArchaeologyJournalState.TableProgress;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.LootTableCatalog.CatalogStructure;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.LootTableCatalog.TableDefinition;
import com.meteorite.unsuspiciousblock.core.journal.state.ArchaeologyJournalState;
import com.meteorite.unsuspiciousblock.core.network.payload.c2s.RequestCatalogPayload;
import com.meteorite.unsuspiciousblock.core.network.payload.c2s.RequestJournalStateFullPayload;
import com.meteorite.unsuspiciousblock.core.network.payload.s2c.JournalCatalogData;
import com.meteorite.unsuspiciousblock.core.network.payload.s2c.SyncCatalogHashPayload;
import com.meteorite.unsuspiciousblock.core.network.payload.s2c.NotifyTableCompletionRewardPayload;
import com.meteorite.unsuspiciousblock.client.network.ClientNetwork;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * 考古笔记客户端状态缓存。
 * <p>
 * 管理服务端推送的目录、玩家进度和日志数据，
 * 并通过回调通知 UI 层解锁事件（如 Toast 弹窗）。
 */
public final class ArchaeologyJournalClientState {

    // 解锁通知回调：表解锁、物品解锁、单表 100% 完成奖励
    // 由平台代码注入，将状态层与 UI 层解耦（状态层不直接引用 ui.toast）
    private static volatile Consumer<List<Component>> tableUnlockNotifier;
    private static volatile BiConsumer<List<Component>, List<ItemStack>> itemUnlockNotifier;
    private static volatile Consumer<Component> tableCompletionNotifier;
    private static final AtomicLong catalogRevision = new AtomicLong();
    private static final AtomicLong stateRevision = new AtomicLong();
    private static volatile ConnectionState connectionState = new ConnectionState();

    private static final class ConnectionState {
        private Map<ResourceLocation, TableDefinition> serverCatalog = Collections.emptyMap();
        private CatalogStructure catalogStructure = CatalogStructure.empty();
        private Map<ResourceLocation, Set<ResourceLocation>> foldedItems = Map.of();
        private SignatureExcludedComponents exclusions = SignatureExcludedComponents.EMPTY;
        private final ArchaeologyJournalState journalState = new ArchaeologyJournalState();
        private Map<ResourceLocation, TableDefinition> catalogProjection = Collections.emptyMap();
        private long catalogProjectionRevision = -1L;
        private long observedCatalog = -1L;
        private long observedSimulation = -1L;
        private long viewRevision;
        private final JournalTransferAssembly catalogAssembly = new JournalTransferAssembly();
        private @Nullable CatalogIdentity announcedCatalog;
        private @Nullable CatalogIdentity committedCatalog;
        private long catalogTicket;
        private long catalogDeadline;
        private boolean catalogWaiting;
        private boolean catalogRetried;
        private boolean catalogFailed;
        private final List<ResourceLocation> pendingRewards = new ArrayList<>();
        private boolean catalogInitialized;
        private boolean stateInitialized;
        private final JournalTransferAssembly progress = new JournalTransferAssembly();
        private final Map<ResourceLocation, TableProgress> pendingUnlocks = new LinkedHashMap<>();
        private long committedProgress = -1;
        private long progressTicket;
        private long progressDeadline;
        private boolean waitingProgress = true;
        private boolean progressRetried;
        private boolean progressFailed;
    }

    private ArchaeologyJournalClientState() {
    }

    // 注册表解锁通知回调
    public static void registerTableUnlockNotifier(Consumer<List<Component>> notifier) {
        tableUnlockNotifier = notifier;
    }

    // 注册物品解锁通知回调
    public static void registerItemUnlockNotifier(BiConsumer<List<Component>, List<ItemStack>> notifier) {
        itemUnlockNotifier = notifier;
    }

    // 注册表 100% 完成奖励的通知回调
    public static void registerTableCompletionNotifier(Consumer<Component> notifier) {
        tableCompletionNotifier = notifier;
    }

    /**
     * 完整目录与进度均已提交，空目录也属于已就绪状态。
     */
    public static boolean isReadingReady() {
        return connectionState.catalogInitialized && connectionState.stateInitialized;
    }

    private static void applyCatalog(JournalCatalogData payload) {
        // 网络形态还原为客户端内存读模型：表级场景假设按 key 回填到每个分场景概率
        Map<ResourceLocation, TableDefinition> tables = new LinkedHashMap<>();
        for (CatalogTableDto table : payload.catalog()) {
            tables.put(table.id(), table.toTableDefinition());
        }
        JournalClientConnection.scenario().catalog(payload.catalog());
        connectionState.serverCatalog = Collections.unmodifiableMap(tables);
        connectionState.catalogStructure = payload.structure();
        connectionState.foldedItems = payload.foldedItems();
        connectionState.exclusions = SignatureExcludedComponents.resolve(payload.signatureExclusions());
        connectionState.catalogInitialized = true;
        catalogRevision.incrementAndGet();
        flushUnlocks(connectionState);
        flushRewards(connectionState);
    }

    /** 按服务端目录同代的签名候选读取玩家进度。 */
    public static @Nullable TableProgress projectProgress(ResourceLocation tableId, @Nullable TableProgress progress) {
        if (progress == null || connectionState.foldedItems.isEmpty()) return progress;
        var tables = connectionState.serverCatalog;
        var folded = CatalogQueryIndex.foldedItems(tables, connectionState.foldedItems, tableId);
        if (folded.isEmpty()) return progress;
        return progress.viewFor(CatalogQueryIndex.completionItems(tables, tableId), folded, connectionState.exclusions);
    }

    /** 公告与场景查询共用同一在途请求。 */
    public static void receiveCatalogHash(SyncCatalogHashPayload payload) {
        ConnectionState state = connectionState;
        CatalogIdentity identity = payload.identity();
        if (state.announcedCatalog != null && identity.compareTo(state.announcedCatalog) < 0) return;
        if (!identity.equals(state.announcedCatalog)) {
            state.announcedCatalog = identity;
            state.catalogRetried = false;
            state.catalogFailed = false;
            state.catalogWaiting = false;
        }
        if (!state.catalogWaiting && !state.catalogFailed && !identity.equals(state.committedCatalog)) {
            requestCatalog(state);
        }
    }

    /** 场景报告过期时重新检查共享目录。 */
    static void refreshCatalog() {
        ConnectionState state = connectionState;
        if (state.catalogWaiting) return;
        state.catalogRetried = false;
        state.catalogFailed = false;
        requestCatalog(state);
    }

    private static void requestCatalog(ConnectionState state) {
        state.catalogAssembly.clear();
        state.catalogWaiting = true;
        state.catalogDeadline = System.currentTimeMillis() + JournalTransferAssembly.RESPONSE_TIMEOUT_MS;
        ClientNetwork.sendToServer(new RequestCatalogPayload(++state.catalogTicket, state.announcedCatalog));
    }

    static void receiveCatalogTransfer(JournalTransferPayload piece, RegistryAccess registries) {
        ConnectionState state = connectionState;
        CatalogIdentity expected = state.announcedCatalog;
        if (!state.catalogWaiting || piece.ticket() != state.catalogTicket || expected == null
                || piece.revision() != expected.revision()) return;
        if (piece.total() == 0) {
            failCatalog(state);
            return;
        }
        try {
            byte[] content = state.catalogAssembly.accept(piece, System.currentTimeMillis());
            if (content == null) return;
            JournalCatalogData data = CatalogReadSnapshot.decode(content, expected, registries);
            state.catalogWaiting = false;
            applyCatalog(data);
            state.committedCatalog = expected;
            state.catalogRetried = false;
        } catch (RuntimeException exception) {
            Constants.LOG.warn(Constants.LOG_TAG + "考古目录事务无法应用", exception);
            recoverCatalog(state);
        }
    }

    static void tickCatalog() {
        ConnectionState state = connectionState;
        if (state.catalogFailed) return;
        long now = System.currentTimeMillis();
        if (state.catalogDeadline == 0) {
            state.catalogDeadline = now + JournalTransferAssembly.RESPONSE_TIMEOUT_MS;
        }
        if (state.catalogAssembly.expired(now) || (!state.catalogInitialized || state.catalogWaiting)
                && !state.catalogAssembly.active() && now >= state.catalogDeadline) recoverCatalog(state);
    }

    private static void recoverCatalog(ConnectionState state) {
        if (state.catalogRetried) {
            failCatalog(state);
            return;
        }
        state.catalogRetried = true;
        requestCatalog(state);
    }

    private static void failCatalog(ConnectionState state) {
        state.catalogAssembly.clear();
        state.catalogWaiting = false;
        state.catalogFailed = true;
        flushUnlocks(state);
        flushRewards(state);
    }

    /** 某个基础数据流进入终态时，阅读入口可提示重新打开。 */
    public static boolean readingFailed() {
        return connectionState.catalogFailed || connectionState.progressFailed;
    }

    /** 用户再次打开笔记时允许重试终态数据流。 */
    public static void retryReading() {
        ConnectionState state = connectionState;
        if (state.catalogFailed) refreshCatalog();
        if (state.progressFailed) {
            state.progressFailed = false;
            state.progressRetried = false;
            recoverProgress(state);
        }
    }

    /** 完整进度事务按连接内已提交版本推进。 */
    static void receiveProgress(JournalTransferPayload piece) {
        ConnectionState state = connectionState;
        boolean baseline = piece.kind() == Kind.PROGRESS_BASELINE;
        if (state.progressFailed || piece.ticket() != state.progressTicket
                || piece.revision() < state.committedProgress
                || piece.revision() == state.committedProgress && !state.waitingProgress
                || !baseline && state.waitingProgress && state.progressTicket > 0) return;
        if (piece.total() == 0) {
            failProgress(state);
            return;
        }
        if (!baseline && (state.waitingProgress || piece.revision() != state.committedProgress + 1)) {
            recoverProgress(state);
            return;
        }
        if (baseline && piece.offset() == 0 && !state.progress.matches(piece)) state.progress.clear();
        try {
            byte[] bytes = state.progress.accept(piece, System.currentTimeMillis());
            if (bytes == null) return;
            CompoundTag tag = JournalWireNbt.decode(bytes, piece.kind().maxBytes() * 2L);
            Map<ResourceLocation, TableProgress> previous = state.journalState.applyPublication(tag, baseline);
            boolean first = !state.stateInitialized;
            state.committedProgress = piece.revision();
            state.stateInitialized = true;
            state.waitingProgress = false;
            state.progressRetried = false;
            stateRevision.incrementAndGet();
            if (!first) {
                Iterable<ResourceLocation> updated = baseline ? state.journalState.getTables().keySet() : previous.keySet();
                for (ResourceLocation id : updated) {
                    if (!state.pendingUnlocks.containsKey(id)) state.pendingUnlocks.put(id, previous.get(id));
                }
                flushUnlocks(state);
            }
        } catch (IOException | RuntimeException exception) {
            Constants.LOG.warn(Constants.LOG_TAG + "考古进度事务无法应用", exception);
            recoverProgress(state);
        }
    }

    /** 连接 tick 驱动缺片恢复和明确失败。 */
    static void tickProgress() {
        ConnectionState state = connectionState;
        if (state.progressFailed) return;
        long now = System.currentTimeMillis();
        if (state.progressDeadline == 0) state.progressDeadline = now + JournalTransferAssembly.RESPONSE_TIMEOUT_MS;
        if (state.progress.expired(now) || state.waitingProgress && !state.progress.active()
                && now >= state.progressDeadline) recoverProgress(state);
    }

    private static void recoverProgress(ConnectionState state) {
        if (state.progressRetried) {
            failProgress(state);
            return;
        }
        state.progress.clear();
        state.waitingProgress = true;
        state.progressRetried = true;
        state.progressDeadline = System.currentTimeMillis() + JournalTransferAssembly.RESPONSE_TIMEOUT_MS;
        ClientNetwork.sendToServer(new RequestJournalStateFullPayload(++state.progressTicket));
    }

    private static void failProgress(ConnectionState state) {
        state.progress.clear();
        state.progressFailed = true;
        state.waitingProgress = false;
    }

    // 收到服务端的 100% 完成奖励通知：解析表名后走回调（与另两条解锁通知同一解耦方式）
    public static void receiveTableCompletionReward(NotifyTableCompletionRewardPayload payload) {
        ConnectionState state = connectionState;
        if ((!state.catalogInitialized || state.catalogWaiting) && !state.catalogFailed) {
            state.pendingRewards.add(payload.tableId());
            return;
        }
        notifyReward(payload.tableId());
    }

    private static void flushRewards(ConnectionState state) {
        state.pendingRewards.forEach(ArchaeologyJournalClientState::notifyReward);
        state.pendingRewards.clear();
    }

    private static void notifyReward(ResourceLocation tableId) {
        TableDefinition def = connectionState.serverCatalog.get(tableId);
        Component tableName = def != null ? ClientLootTableLanguageStore.displayName(tableId)
                : Component.literal(tableId.getPath());
        Consumer<Component> notifier = tableCompletionNotifier;
        if (notifier != null) {
            notifier.accept(tableName);
        }
    }

    /**
     * 目录投影：在原始目录上叠加当前模拟选择与结果，供日志详情、tooltip 与网格共用。
     * <p>
     * 目录版本与场景投影版本分别观察，任一变化推进单调视图版本：
     * 收到完整目录（{@link #applyCatalog}）、模拟选择/当前测值变化（投影版本）、
     * 断连重置（由 {@link JournalClientConnection} 负责）。命中时返回同一份**不可修改**映射，
     * 调用方只能读取。语言切换无需失效：投影只持有 {@code Component} 与 record，翻译在渲染时解析。
     */
    public static Map<ResourceLocation, TableDefinition> getCatalog() {
        long revision = getCatalogRevision();
        if (connectionState.catalogProjectionRevision == revision) {
            return connectionState.catalogProjection;
        }
        Map<ResourceLocation, TableDefinition> projection = Collections.unmodifiableMap(
                JournalClientConnection.scenario().overlay(connectionState.serverCatalog));
        // 先发布投影再发布版本号，读取方看到新版本号时必然能看到对应投影
        connectionState.catalogProjection = projection;
        connectionState.catalogProjectionRevision = revision;
        return projection;
    }

    // 按物品注册名预检当前可见目录是否存在搜索结果，避免打开空白手册。
    public static boolean hasItemSearchResult(ResourceLocation itemId) {
        JournalSearchQuery query = JournalSearchQuery.forItemId(itemId);
        ArchaeologyJournalState state = connectionState.journalState;
        for (Map.Entry<ResourceLocation, TableDefinition> entry : connectionState.serverCatalog.entrySet()) {
            ArchaeologyJournalState.TableProgress progress = state.getTable(entry.getKey());
            if (progress == null || !progress.isUnlocked()) {
                continue;
            }
            ArchaeologyJournalEntry view = ArchaeologyJournalEntry.of(
                    entry.getKey(), entry.getValue(), progress);
            if (query.matchesTableByItem(view.id(), view.displayName().getString(), view.type(), view.items())) {
                return true;
            }
        }
        return false;
    }

    public static CatalogStructure getCatalogStructure() {
        return connectionState.catalogStructure;
    }

    public static long getCatalogRevision() {
        long catalog = catalogRevision.get();
        long simulation = JournalClientConnection.scenario().projectionRevision();
        if (connectionState.observedCatalog != catalog || connectionState.observedSimulation != simulation) {
            connectionState.observedCatalog = catalog;
            connectionState.observedSimulation = simulation;
            connectionState.viewRevision++;
        }
        return connectionState.viewRevision;
    }

    public static long getStateRevision() {
        return stateRevision.get();
    }

    public static ArchaeologyJournalState getState() {
        return connectionState.journalState;
    }

    static void resetConnectionMemory() {
        connectionState = new ConnectionState();
        catalogRevision.incrementAndGet();
        stateRevision.incrementAndGet();
    }

    private static void flushUnlocks(ConnectionState state) {
        if (!state.catalogInitialized || state.catalogWaiting || state.pendingUnlocks.isEmpty()) return;
        Map<ResourceLocation, TableDefinition> catalog = state.serverCatalog;
        List<Component> newTables = new ArrayList<>();
        List<Component> itemNames = new ArrayList<>();
        List<ItemStack> itemIcons = new ArrayList<>();
        state.pendingUnlocks.forEach((tableId, oldProgress) -> {
            if (!catalog.containsKey(tableId)) return;
            TableProgress newProgress = state.journalState.getTable(tableId);
            if (newProgress == null || !newProgress.isUnlocked()) return;
            if (oldProgress == null || !oldProgress.isUnlocked()) {
                newTables.add(resolveTableName(tableId, catalog));
            }
            if (oldProgress != null) {
                collectItemUnlocks(tableId, newProgress, oldProgress, catalog, itemNames, itemIcons);
            }
        });
        state.pendingUnlocks.clear();
        notifyUnlocks(newTables, itemNames, itemIcons);
    }

    // 通知注册的回调；若未注册则静默忽略
    private static void notifyUnlocks(List<Component> newTables, List<Component> itemNames, List<ItemStack> itemIcons) {
        if (!newTables.isEmpty()) {
            Consumer<List<Component>> tn = tableUnlockNotifier;
            if (tn != null) tn.accept(newTables);
        }
        if (!itemNames.isEmpty()) {
            BiConsumer<List<Component>, List<ItemStack>> in = itemUnlockNotifier;
            if (in != null) in.accept(itemNames, itemIcons);
        }
    }

    // 收集单个表中物品的新解锁
    private static void collectItemUnlocks(ResourceLocation tableId,
                                           ArchaeologyJournalState.TableProgress newProgress,
                                           ArchaeologyJournalState.TableProgress oldProgress,
                                           Map<ResourceLocation, TableDefinition> catalog,
                                           List<Component> outNames,
                                           List<ItemStack> outIcons) {
        TableDefinition tableDef = catalog.get(tableId);
        for (LootTableCatalog.ItemDefinition itemDef : tableDef != null ? tableDef.items() : List.<LootTableCatalog.ItemDefinition>of()) {
            if (newProgress.isItemUnlocked(itemDef.signature())
                    && !oldProgress.isItemUnlocked(itemDef.signature())) {
                ItemStack icon = itemDef.signature().createPreviewStack();
                if (icon.isEmpty()) {
                    icon = new ItemStack(BuiltInRegistries.ITEM.get(itemDef.id()));
                }
                outNames.add(itemDef.displayName());
                outIcons.add(icon);
            }
        }
    }

    // 从目录定义中解析表显示名，若不存在则使用 ResourceLocation 路径
    private static Component resolveTableName(ResourceLocation tableId, Map<ResourceLocation, TableDefinition> catalog) {
        TableDefinition def = catalog.get(tableId);
        if (def != null) {
            return ClientLootTableLanguageStore.displayName(tableId);
        }
        return Component.literal(tableId.getPath());
    }
}
