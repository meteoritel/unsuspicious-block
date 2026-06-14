package com.meteorite.unsuspiciousblock.client.ui.support;

import com.meteorite.unsuspiciousblock.loottable.ArchaeologyLootTableCatalog;
import com.meteorite.unsuspiciousblock.loottable.ArchaeologyLootTableCatalog.TableDefinition;
import com.meteorite.unsuspiciousblock.journal.state.ArchaeologyJournalLogState;
import com.meteorite.unsuspiciousblock.journal.state.ArchaeologyJournalState;
import com.meteorite.unsuspiciousblock.network.payload.c2s.RequestCatalogPayload;
import com.meteorite.unsuspiciousblock.network.payload.s2c.SyncArchaeologyCatalogPayload;
import com.meteorite.unsuspiciousblock.network.payload.s2c.SyncCatalogHashPayload;
import com.meteorite.unsuspiciousblock.network.payload.s2c.SyncJournalLogPayload;
import com.meteorite.unsuspiciousblock.network.payload.s2c.SyncJournalLogSnapshotPayload;
import com.meteorite.unsuspiciousblock.network.payload.s2c.SyncJournalStateIncrementalPayload;
import com.meteorite.unsuspiciousblock.network.payload.s2c.SyncJournalStatePayload;
import com.meteorite.unsuspiciousblock.platform.Services;
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
 * 考古手册客户端状态缓存。
 * <p>
 * 管理服务端推送的目录、玩家进度和日志数据，
 * 并通过回调通知 UI 层解锁事件（如 Toast 弹窗）。
 */
public final class ArchaeologyJournalClientState {

    // 解锁通知回调：表解锁通知、物品解锁通知
    // 由平台代码注入，将状态层与 UI 层解耦
    private static volatile Consumer<List<Component>> tableUnlockNotifier;
    private static volatile BiConsumer<List<Component>, List<ItemStack>> itemUnlockNotifier;
    private static volatile Map<ResourceLocation, TableDefinition> serverCatalog = Collections.emptyMap();
    private static volatile ArchaeologyJournalState journalState = new ArchaeologyJournalState();
    @Nullable
    private static volatile ResourceLocation lastSelectedTableId;
    private static final AtomicLong catalogRevision = new AtomicLong();
    private static final AtomicLong stateRevision = new AtomicLong();
    // 客户端缓存的目录哈希，用于按需同步比对
    @Nullable
    private static volatile String cachedCatalogHash;
    // 标记服务端状态是否已完成首次同步；首次同步时不弹 Toast，避免重进世界时重复通知
    private static volatile boolean stateInitialized = false;
    // 上次触发 Toast 通知的服务端版本号；仅当 incomingRevision > lastNotifiedRevision 时才重新检测
    private static volatile long lastNotifiedRevision = -1L;

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

    public static void receiveCatalog(SyncArchaeologyCatalogPayload payload) {
        ArchaeologyJournalLogLocalStore.tick();
        serverCatalog = Collections.unmodifiableMap(new LinkedHashMap<>(payload.catalog()));
        catalogRevision.incrementAndGet();
        // 收到完整目录后，哈希由服务端下次同步时更新，此处不修改
    }

    // 收到目录哈希后与本地缓存对比，不一致时请求完整目录
    public static void receiveCatalogHash(SyncCatalogHashPayload payload) {
        String newHash = payload.catalogHash();
        String localHash = cachedCatalogHash;
        if (localHash != null && localHash.equals(newHash)) {
            // 哈希一致，无需重新下载
            return;
        }
        // 哈希不一致或首次收到，请求完整目录
        Services.NETWORK.sendToServer(new RequestCatalogPayload());
        cachedCatalogHash = newHash;
    }

    public static void receiveState(SyncJournalStatePayload payload) {
        ArchaeologyJournalLogLocalStore.tick();
        if (payload.state() == null) return;

        long incomingRevision = payload.revision();

        if (!stateInitialized) {
            // 首次同步：仅保存状态，不与空状态比较以避免误触发 Toast
            ArchaeologyJournalState updated = new ArchaeologyJournalState();
            updated.readFrom(payload.state());
            journalState = updated;
            stateRevision.set(incomingRevision);
            stateInitialized = true;
            lastNotifiedRevision = incomingRevision;
            return;
        }

        // 版本号相同或更旧，跳过 Toast 检测
        if (incomingRevision <= lastNotifiedRevision) {
            ArchaeologyJournalState updated = new ArchaeologyJournalState();
            updated.readFrom(payload.state());
            journalState = updated;
            stateRevision.set(incomingRevision);
            return;
        }

        // 在更新前保存旧状态，用于检测新增解锁
        ArchaeologyJournalState oldState = journalState.copy();
        ArchaeologyJournalState updated = new ArchaeologyJournalState();
        updated.readFrom(payload.state());
        journalState = updated;
        stateRevision.set(incomingRevision);

        // 检测新解锁的表和物品，触发 Toast 通知
        detectAndNotifyUnlocks(oldState, updated);
        lastNotifiedRevision = incomingRevision;
    }

    // 接收增量状态同步包：仅合并变更的表
    public static void receiveStateIncremental(SyncJournalStateIncrementalPayload payload) {
        ArchaeologyJournalLogLocalStore.tick();
        if (payload.changedTables() == null) return;

        long incomingRevision = payload.revision();

        if (!stateInitialized) {
            // 增量包到达但尚未完成首次全量同步，忽略
            return;
        }

        // 版本号相同或更旧，跳过
        if (incomingRevision <= lastNotifiedRevision) {
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
        ArchaeologyJournalState oldState = journalState.partialCopy(changedIds);
        journalState.mergeFromIncremental(payload.changedTables(), incomingRevision);
        stateRevision.set(incomingRevision);

        // 仅对变更的表做 Diff 检测
        detectAndNotifyUnlocksForTables(oldState, journalState, payload.changedTables());
        lastNotifiedRevision = incomingRevision;
    }

    public static void receiveLogUpdate(SyncJournalLogPayload payload) {
        ArchaeologyJournalLogLocalStore.applyUpdate(payload);
    }

    public static void receiveLogSnapshot(SyncJournalLogSnapshotPayload payload) {
        ArchaeologyJournalLogLocalStore.applySnapshot(payload);
    }

    public static void tick() {
        ArchaeologyJournalLogLocalStore.tick();
    }

    public static Map<ResourceLocation, TableDefinition> getCatalog() {
        return serverCatalog;
    }

    public static long getCatalogRevision() {
        return catalogRevision.get();
    }

    public static long getStateRevision() {
        return stateRevision.get();
    }

    public static long getLogRevision() {
        return ArchaeologyJournalLogLocalStore.getRevision();
    }

    public static ArchaeologyJournalState getState() {
        return journalState;
    }

    public static ArchaeologyJournalLogState getLogState() {
        return ArchaeologyJournalLogLocalStore.getState().copy();
    }

    @Nullable
    public static ResourceLocation getLastSelectedTableId() {
        return lastSelectedTableId;
    }

    public static void rememberLastSelectedTable(@Nullable ResourceLocation tableId) {
        lastSelectedTableId = tableId;
    }

    // 断线时重置，使下次连入能正确处理首次同步
    public static void resetOnDisconnect() {
        stateInitialized = false;
        lastNotifiedRevision = -1L;
        journalState = new ArchaeologyJournalState();
        cachedCatalogHash = null;
    }

    // 比较旧状态和新状态，检测新解锁的表和物品并通知
    private static void detectAndNotifyUnlocks(ArchaeologyJournalState oldState, ArchaeologyJournalState newState) {
        Map<ResourceLocation, TableDefinition> catalog = serverCatalog;
        List<Component> newTables = new ArrayList<>();
        List<Component> itemNames = new ArrayList<>();
        List<ItemStack> itemIcons = new ArrayList<>();

        for (Map.Entry<ResourceLocation, ArchaeologyJournalState.TableProgress> entry : newState.getTables().entrySet()) {
            ResourceLocation tableId = entry.getKey();
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
                                                        net.minecraft.nbt.CompoundTag changedTables) {
        Map<ResourceLocation, TableDefinition> catalog = serverCatalog;
        List<Component> newTables = new ArrayList<>();
        List<Component> itemNames = new ArrayList<>();
        List<ItemStack> itemIcons = new ArrayList<>();

        for (String key : changedTables.getAllKeys()) {
            ResourceLocation tableId = ResourceLocation.tryParse(key);
            if (tableId == null) continue;

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
        for (ArchaeologyLootTableCatalog.ItemDefinition itemDef : tableDef != null ? tableDef.items() : java.util.List.<ArchaeologyLootTableCatalog.ItemDefinition>of()) {
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