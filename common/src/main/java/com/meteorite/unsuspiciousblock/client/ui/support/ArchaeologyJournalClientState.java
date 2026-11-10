package com.meteorite.unsuspiciousblock.client.ui.support;

import com.meteorite.unsuspiciousblock.client.state.ScenarioSimulationClientState;
import net.minecraft.nbt.CompoundTag;
import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.client.ui.entry.ArchaeologyJournalEntry;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.LootTableCatalog;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.CatalogTableDto;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.LootTableCatalog.CatalogStructure;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.LootTableCatalog.TableDefinition;
import com.meteorite.unsuspiciousblock.core.journal.state.ArchaeologyJournalLogState;
import com.meteorite.unsuspiciousblock.core.journal.state.ArchaeologyJournalState;
import com.meteorite.unsuspiciousblock.core.network.payload.c2s.RequestCatalogPayload;
import com.meteorite.unsuspiciousblock.core.network.payload.c2s.RequestJournalStateFullPayload;
import com.meteorite.unsuspiciousblock.core.network.payload.s2c.SyncArchaeologyCatalogPayload;
import com.meteorite.unsuspiciousblock.core.network.payload.s2c.SyncCatalogHashPayload;
import com.meteorite.unsuspiciousblock.core.network.payload.s2c.SyncJournalStateIncrementalPayload;
import com.meteorite.unsuspiciousblock.core.network.payload.s2c.SyncJournalStatePayload;
import com.meteorite.unsuspiciousblock.core.network.payload.s2c.NotifyTableCompletionRewardPayload;
import com.meteorite.unsuspiciousblock.core.network.payload.s2c.JournalLogDeleteResultPayload;
import com.meteorite.unsuspiciousblock.client.network.ClientNetwork;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
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
    private static volatile Consumer<Component> logDeleteNotifier;
    private static final AtomicLong catalogRevision = new AtomicLong();
    private static final AtomicLong stateRevision = new AtomicLong();
    private static volatile ConnectionState connectionState = new ConnectionState();

    private static final class ConnectionState {
        private Map<ResourceLocation, TableDefinition> serverCatalog = Collections.emptyMap();
        private CatalogStructure catalogStructure = CatalogStructure.empty();
        private ArchaeologyJournalState journalState = new ArchaeologyJournalState();
        private Map<ResourceLocation, TableDefinition> catalogProjection = Collections.emptyMap();
        private long catalogProjectionRevision = -1L;
        private @Nullable String cachedCatalogHash;
        private boolean stateInitialized;
        private long lastNotifiedRevision = -1L;
        private final ResyncPolicy resyncPolicy = new ResyncPolicy(System::currentTimeMillis);
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

    public static void registerLogDeleteNotifier(Consumer<Component> notifier) {
        logDeleteNotifier = notifier;
    }

    public static void receiveCatalog(SyncArchaeologyCatalogPayload payload) {
        // 网络形态还原为客户端内存读模型：表级场景假设按 key 回填到每个分场景概率
        Map<ResourceLocation, TableDefinition> tables = new LinkedHashMap<>();
        for (CatalogTableDto table : payload.catalog()) {
            tables.put(table.id(), table.toTableDefinition());
        }
        ScenarioSimulationClientState.catalog(payload.catalog());
        connectionState.serverCatalog = Collections.unmodifiableMap(tables);
        connectionState.catalogStructure = payload.structure();
        catalogRevision.incrementAndGet();
        // 收到完整目录后，哈希由服务端下次同步时更新
    }

    // 收到目录哈希后与本地缓存对比，不一致时请求完整目录
    public static void receiveCatalogHash(SyncCatalogHashPayload payload) {
        String newHash = payload.catalogHash();
        String localHash = connectionState.cachedCatalogHash;
        if (localHash != null && localHash.equals(newHash)) {
            // 哈希一致，无需重新下载
            return;
        }
        // 哈希不一致或首次收到，请求完整目录
        ClientNetwork.sendToServer(new RequestCatalogPayload());
        connectionState.cachedCatalogHash = newHash;
    }

    public static void receiveState(SyncJournalStatePayload payload) {
        if (payload.state() == null) return;

        long incomingRevision = payload.revision();

        if (!connectionState.stateInitialized) {
            // 首次同步：仅保存状态，不与空状态比较以避免误触发 Toast
            ArchaeologyJournalState updated = new ArchaeologyJournalState();
            updated.readFrom(payload.state());
            connectionState.journalState = updated;
            stateRevision.set(incomingRevision);
            connectionState.stateInitialized = true;
            connectionState.lastNotifiedRevision = incomingRevision;
            return;
        }

        // 版本号相同或更旧，跳过 Toast 检测
        if (incomingRevision <= connectionState.lastNotifiedRevision) {
            ArchaeologyJournalState updated = new ArchaeologyJournalState();
            updated.readFrom(payload.state());
            connectionState.journalState = updated;
            stateRevision.set(incomingRevision);
            return;
        }

        // 在更新前保存旧状态，用于检测新增解锁
        ArchaeologyJournalState oldState = connectionState.journalState.copy();
        ArchaeologyJournalState updated = new ArchaeologyJournalState();
        updated.readFrom(payload.state());
        connectionState.journalState = updated;
        stateRevision.set(incomingRevision);

        // 检测新解锁的表和物品，触发 Toast 通知
        detectAndNotifyUnlocks(oldState, updated);
        connectionState.lastNotifiedRevision = incomingRevision;
    }

    // 接收增量状态同步包：仅合并变更的表
    public static void receiveStateIncremental(SyncJournalStateIncrementalPayload payload) {
        if (payload.changedTables() == null) return;

        long incomingRevision = payload.revision();

        if (!connectionState.stateInitialized) {
            // 增量包到达但尚未完成首次全量同步，主动请求全量重同步
            requestFullStateWithCooldown();
            return;
        }

        // 版本号相同或更旧，跳过
        if (incomingRevision <= connectionState.lastNotifiedRevision) {
            return;
        }

        // 间隙检测：incomingRevision 应严格等于 connectionState.lastNotifiedRevision + 1
        // 若大于，说明中间有增量包丢失或被跳过，本地状态已不一致，需请求全量重同步
        if (incomingRevision > connectionState.lastNotifiedRevision + 1) {
            Constants.LOG.warn("检测到考古进度 revision 间隙: 期望 {}, 收到 {}, 请求全量重同步",
                    connectionState.lastNotifiedRevision + 1, incomingRevision);
            requestFullStateWithCooldown();
            return;
        }

        // 仅复制变更表用于 Diff，避免全量拷贝
        Set<ResourceLocation> changedIds = new HashSet<>();
        for (String key : payload.changedTables().getAllKeys()) {
            ResourceLocation id = ResourceLocation.tryParse(key);
            if (id != null) {
                changedIds.add(id);
            }
        }
        ArchaeologyJournalState oldState = connectionState.journalState.partialCopy(changedIds);
        connectionState.journalState.mergeFromIncremental(payload.changedTables(), incomingRevision);
        stateRevision.set(incomingRevision);

        // 仅对变更的表做 Diff 检测
        detectAndNotifyUnlocksForTables(oldState, connectionState.journalState, payload.changedTables());
        connectionState.lastNotifiedRevision = incomingRevision;
    }

    // 请求全量重同步（带限流），用于 revision 间隙或增量先于全量到达的恢复路径
    private static void requestFullStateWithCooldown() {
        if (connectionState.resyncPolicy.tryRequest()) {
            ClientNetwork.sendToServer(new RequestJournalStateFullPayload());
        }
    }

    public static void receiveLogDeleteResult(JournalLogDeleteResultPayload payload) {
        Component message = switch (payload.result()) {
            case SUCCESS -> Component.translatable(
                    "screen.unsuspiciousblock.archaeology_journal.log_delete.success", payload.removedCount());
            case NOT_FOUND -> Component.translatable(
                    "screen.unsuspiciousblock.archaeology_journal.log_delete.not_found");
            case INVALID -> Component.translatable(
                    "screen.unsuspiciousblock.archaeology_journal.log_delete.invalid");
        };
        Consumer<Component> notifier = logDeleteNotifier;
        if (notifier != null) {
            notifier.accept(message);
        }
    }

    // 收到服务端的 100% 完成奖励通知：解析表名后走回调（与另两条解锁通知同一解耦方式）
    public static void receiveTableCompletionReward(NotifyTableCompletionRewardPayload payload) {
        ResourceLocation tableId = payload.tableId();
        TableDefinition def = connectionState.serverCatalog.get(tableId);
        Component tableName = def != null ? def.displayName()
                : Component.literal(tableId.getPath());
        Consumer<Component> notifier = tableCompletionNotifier;
        if (notifier != null) {
            notifier.accept(tableName);
        }
    }

    /**
     * 目录投影：在原始目录上叠加当前模拟选择与结果，供日志详情、tooltip 与网格共用。
     * <p>
     * 缓存键由目录版本与场景 Module 的 revision 合成：
     * 收到完整目录（{@link #receiveCatalog}）、模拟选择/结果变化（场景 revision）、
     * 断连重置（由 {@link JournalClientConnection} 负责）。命中时返回同一份**不可修改**映射，
     * 调用方只能读取。语言切换无需失效：投影只持有 {@code Component} 与 record，翻译在渲染时解析。
     */
    public static Map<ResourceLocation, TableDefinition> getCatalog() {
        long revision = getCatalogRevision();
        if (connectionState.catalogProjectionRevision == revision) {
            return connectionState.catalogProjection;
        }
        Map<ResourceLocation, TableDefinition> projection = Collections.unmodifiableMap(
                ScenarioSimulationClientState.overlay(connectionState.serverCatalog));
        // 先发布投影再发布版本号，读取方看到新版本号时必然能看到对应投影
        connectionState.catalogProjection = projection;
        connectionState.catalogProjectionRevision = revision;
        return projection;
    }

    // 按物品注册名预检当前可见目录是否存在搜索结果，避免打开空白手册。
    public static boolean hasItemSearchResult(ResourceLocation itemId) {
        JournalSearchQuery query = JournalSearchQuery.forItemId(itemId);
        ArchaeologyJournalState state = connectionState.journalState;
        ArchaeologyJournalLogState logState = ArchaeologyJournalLogLocalStore.getState();
        for (Map.Entry<ResourceLocation, TableDefinition> entry : connectionState.serverCatalog.entrySet()) {
            ArchaeologyJournalState.TableProgress progress = state.getTable(entry.getKey());
            if (progress == null || !progress.isUnlocked()) {
                continue;
            }
            ArchaeologyJournalEntry view = ArchaeologyJournalEntry.of(
                    entry.getKey(), entry.getValue(), progress, logState.getTable(entry.getKey()));
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
        return catalogRevision.get()
                + ScenarioSimulationClientState.revision();
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

    // 比较旧状态和新状态，检测新解锁的表和物品并通知
    private static void detectAndNotifyUnlocks(ArchaeologyJournalState oldState, ArchaeologyJournalState newState) {
        Map<ResourceLocation, TableDefinition> catalog = connectionState.serverCatalog;
        List<Component> newTables = new ArrayList<>();
        List<Component> itemNames = new ArrayList<>();
        List<ItemStack> itemIcons = new ArrayList<>();

        for (Map.Entry<ResourceLocation, ArchaeologyJournalState.TableProgress> entry : newState.getTables().entrySet()) {
            ResourceLocation tableId = entry.getKey();
            // 仅对追踪目录中的表触发 Toast，避免未追踪的表（不会在考古笔记中显示）弹出通知
            if (!catalog.containsKey(tableId)) {
                continue;
            }
            ArchaeologyJournalState.TableProgress newProgress = entry.getValue();
            ArchaeologyJournalState.TableProgress oldProgress = oldState.getTable(tableId);

            if (newProgress.isUnlocked() && (oldProgress == null || !oldProgress.isUnlocked())) {
                newTables.add(resolveTableName(tableId, catalog));
            }

            if (newProgress.isUnlocked() && oldProgress != null) {
                collectItemUnlocks(tableId, newProgress, oldProgress, catalog, itemNames, itemIcons);
            }
        }

        notifyUnlocks(newTables, itemNames, itemIcons);
    }

    // 仅对增量包中变更的表做 Diff 检测并通知
    private static void detectAndNotifyUnlocksForTables(ArchaeologyJournalState oldState,
                                                        ArchaeologyJournalState newState,
                                                        CompoundTag changedTables) {
        Map<ResourceLocation, TableDefinition> catalog = connectionState.serverCatalog;
        List<Component> newTables = new ArrayList<>();
        List<Component> itemNames = new ArrayList<>();
        List<ItemStack> itemIcons = new ArrayList<>();

        for (String key : changedTables.getAllKeys()) {
            ResourceLocation tableId = ResourceLocation.tryParse(key);
            if (tableId == null) continue;
            // 仅对追踪目录中的表触发 Toast，避免未追踪的表（不会在考古笔记中显示）弹出通知
            if (!catalog.containsKey(tableId)) {
                continue;
            }

            ArchaeologyJournalState.TableProgress newProgress = newState.getTable(tableId);
            if (newProgress == null) continue;

            ArchaeologyJournalState.TableProgress oldProgress = oldState.getTable(tableId);

            if (newProgress.isUnlocked() && (oldProgress == null || !oldProgress.isUnlocked())) {
                newTables.add(resolveTableName(tableId, catalog));
            }

            if (newProgress.isUnlocked() && oldProgress != null) {
                collectItemUnlocks(tableId, newProgress, oldProgress, catalog, itemNames, itemIcons);
            }
        }

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
            return def.displayName();
        }
        return Component.literal(tableId.getPath());
    }
}
