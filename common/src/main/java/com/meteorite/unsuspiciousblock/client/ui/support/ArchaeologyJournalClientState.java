package com.meteorite.unsuspiciousblock.client.ui.support;

import com.meteorite.unsuspiciousblock.client.ui.toast.JournalUnlockToast;
import com.meteorite.unsuspiciousblock.loottable.ArchaeologyLootTableCatalog;
import com.meteorite.unsuspiciousblock.loottable.ArchaeologyLootTableCatalog.TableDefinition;
import com.meteorite.unsuspiciousblock.journal.state.ArchaeologyJournalLogState;
import com.meteorite.unsuspiciousblock.journal.state.ArchaeologyJournalState;
import com.meteorite.unsuspiciousblock.network.payload.s2c.SyncArchaeologyCatalogPayload;
import com.meteorite.unsuspiciousblock.network.payload.s2c.SyncJournalLogPayload;
import com.meteorite.unsuspiciousblock.network.payload.s2c.SyncJournalLogSnapshotPayload;
import com.meteorite.unsuspiciousblock.network.payload.s2c.SyncJournalStatePayload;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

public final class ArchaeologyJournalClientState {
    private static volatile Map<ResourceLocation, TableDefinition> serverCatalog = Collections.emptyMap();
    private static volatile ArchaeologyJournalState journalState = new ArchaeologyJournalState();
    @Nullable
    private static volatile ResourceLocation lastSelectedTableId;
    private static final AtomicLong catalogRevision = new AtomicLong();
    private static final AtomicLong stateRevision = new AtomicLong();
    // 标记服务端状态是否已完成首次同步；首次同步时不弹 Toast，避免重进世界时重复通知
    private static volatile boolean stateInitialized = false;

    private ArchaeologyJournalClientState() {
    }

    public static void receiveCatalog(SyncArchaeologyCatalogPayload payload) {
        ArchaeologyJournalLogLocalStore.tick();
        serverCatalog = Collections.unmodifiableMap(new LinkedHashMap<>(payload.catalog()));
        catalogRevision.incrementAndGet();
    }

    public static void receiveState(SyncJournalStatePayload payload) {
        ArchaeologyJournalLogLocalStore.tick();
        if (payload.state() == null) return;

        if (!stateInitialized) {
            // 首次同步：仅保存状态，不与空状态比较以避免误触发 Toast
            ArchaeologyJournalState updated = new ArchaeologyJournalState();
            updated.readFrom(payload.state());
            journalState = updated;
            stateRevision.incrementAndGet();
            stateInitialized = true;
            return;
        }

        // 在更新前保存旧状态，用于检测新增解锁
        ArchaeologyJournalState oldState = journalState.copy();
        ArchaeologyJournalState updated = new ArchaeologyJournalState();
        updated.readFrom(payload.state());
        journalState = updated;
        stateRevision.incrementAndGet();

        // 检测新解锁的表和物品，触发 Toast 通知
        detectAndNotifyUnlocks(oldState, updated);
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
        journalState = new ArchaeologyJournalState();
    }

    // 比较旧状态和新状态，检测新解锁的表和物品并触发 Toast
    private static void detectAndNotifyUnlocks(ArchaeologyJournalState oldState, ArchaeologyJournalState newState) {
        Map<ResourceLocation, TableDefinition> catalog = serverCatalog;

        for (Map.Entry<ResourceLocation, ArchaeologyJournalState.TableProgress> entry : newState.getTables().entrySet()) {
            ResourceLocation tableId = entry.getKey();
            ArchaeologyJournalState.TableProgress newProgress = entry.getValue();
            ArchaeologyJournalState.TableProgress oldProgress = oldState.getTable(tableId);

            // 检测表的新解锁
            if (newProgress.isUnlocked() && (oldProgress == null || !oldProgress.isUnlocked())) {
                Component tableName = resolveTableName(tableId, catalog);
                JournalUnlockToast.addTableUnlock(tableName);
            }

            // 检测物品的新解锁
            if (newProgress.isUnlocked() && oldProgress != null) {
                // 获取该表的定义以查找物品显示名
                TableDefinition tableDef = catalog.get(tableId);
                for (ArchaeologyLootTableCatalog.ItemDefinition itemDef : tableDef != null ? tableDef.items() : java.util.List.<ArchaeologyLootTableCatalog.ItemDefinition>of()) {
                    if (newProgress.isItemUnlocked(itemDef.signature())
                            && !oldProgress.isItemUnlocked(itemDef.signature())) {
                        ItemStack icon = itemDef.signature().createPreviewStack();
                        if (icon.isEmpty()) {
                            icon = new ItemStack(BuiltInRegistries.ITEM.get(itemDef.id()));
                        }
                        JournalUnlockToast.addItemUnlock(itemDef.displayName(), icon);
                    }
                }
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
