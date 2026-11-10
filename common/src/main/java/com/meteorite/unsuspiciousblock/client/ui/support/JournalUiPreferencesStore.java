package com.meteorite.unsuspiciousblock.client.ui.support;

import com.meteorite.unsuspiciousblock.client.ui.panel.RightPageContainer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/**
 * 考古笔记 UI 偏好的 per-world 客户端持久化。
 * <p>
 * 与 {@link ArchaeologyJournalLogLocalStore} 共用存储目录，但使用独立文件，
 * 保存玩家在该存档下的 UI 偏好：收藏集合、上次选中条目、目录/日志排序状态、搜索文本等。
 * 服务端权威数据（解锁进度、日志）不在此处保存。
 */
public final class JournalUiPreferencesStore {

    static final String STORAGE_FILE = "journal_ui_preferences.dat";

    @Nullable
    private static volatile Path loadedPath;
    private static final JournalLocalPersistence.PendingWrites writes = new JournalLocalPersistence.PendingWrites();
    private static volatile boolean dirty;
    private static CompoundTag panelStates = new CompoundTag();
    private static CompoundTag simulationPreferences = new CompoundTag();

    @Nullable
    public static synchronized String getSimulationPreference(String key) {
        return simulationPreferences.contains(key, Tag.TAG_STRING) ? simulationPreferences.getString(key) : null;
    }

    public static synchronized void setSimulationPreference(String key, String value) {
        simulationPreferences.putString(key, value);
        while (simulationPreferences.size() > 1024) {
            simulationPreferences.remove(simulationPreferences.getAllKeys().iterator().next());
        }
        dirty = true;
    }

    // 面板状态使用现有按存档、按玩家隔离的偏好文件，无需每加一个面板就改序列化字段。
    public static synchronized CompoundTag getPanelStates() {
        return panelStates.copy();
    }

    public static synchronized void setPanelStates(CompoundTag state) {
        panelStates = state.copy();
        dirty = true;
    }

    private JournalUiPreferencesStore() {
    }

    // 连接宿主完成加载后，每 tick 合并待写快照。
    public static synchronized void tick() {
        flushIfDirty();
    }

    // 标记偏好已修改，下次 tick 时刷盘
    public static void markDirty() {
        dirty = true;
    }

    // 断线/退世界前主动刷盘，避免丢失最近一次修改
    public static synchronized void flushIfDirty() {
        if (dirty && loadedPath != null) {
            if (save()) {
                dirty = false;
            }
        } else {
            writes.flush();
        }
    }

    static synchronized void beginConnection() {
        flushIfDirty();
        loadedPath = null;
        panelStates = new CompoundTag();
        simulationPreferences = new CompoundTag();
        dirty = false;
    }

    static synchronized void loadCurrentPath(Path path) {
        if (!path.equals(loadedPath)) {
            loadedPath = path;
            load(path);
        }
    }

    // 从 NBT 读取偏好并写回 ClientState 的 static 字段
    private static void load(Path path) {
        CompoundTag pending = writes.pending(path);
        JournalLocalPersistence.Loaded result = pending == null
                ? JournalLocalPersistence.load(path, Long.MAX_VALUE)
                : new JournalLocalPersistence.Loaded(pending, true);
        if (result.successful()) {
            applyToClientState(result.tag());
        }
    }

    private static boolean save() {
        Path path = loadedPath;
        if (path == null) {
            return false;
        }
        writes.stage(path, snapshotFromClientState());
        writes.flush();
        return writes.isIdle(path);
    }

    // 从 NBT 还原到 ClientState 内存字段
    private static void applyToClientState(CompoundTag tag) {
        panelStates = tag.getCompound("panels").copy();
        simulationPreferences = tag.getCompound("simulationPreferences").copy();
        // 加载期间抑制 markDirty，避免加载即触发回写
        ArchaeologyJournalClientState.setPersistenceSuppress(true);
        try {
            if (tag.contains("lastSelectedTable", Tag.TAG_STRING)) {
                ResourceLocation id = ResourceLocation.tryParse(tag.getString("lastSelectedTable"));
                ArchaeologyJournalClientState.rememberLastSelectedTable(id);
            }
            if (tag.contains("lastCatalogSortOrder", Tag.TAG_STRING)) {
                try {
                    ArchaeologyJournalClientState.setLastCatalogSortOrder(
                            CatalogSorter.SortOrder.valueOf(tag.getString("lastCatalogSortOrder")));
                } catch (IllegalArgumentException ignored) {
                    // 枚举名变更时静默丢弃
                }
            }
            if (tag.contains("lastCatalogSortDescending", Tag.TAG_BYTE)) {
                ArchaeologyJournalClientState.setLastCatalogSortDescending(tag.getBoolean("lastCatalogSortDescending"));
            }
            if (tag.contains("lastCatalogHideLocked", Tag.TAG_BYTE)) {
                ArchaeologyJournalClientState.setLastCatalogHideLocked(tag.getBoolean("lastCatalogHideLocked"));
            }
            if (tag.contains("lastCatalogSearchText", Tag.TAG_STRING)) {
                ArchaeologyJournalClientState.setLastCatalogSearchText(tag.getString("lastCatalogSearchText"));
            }
            if (tag.contains("lastLogSortDescending", Tag.TAG_BYTE)) {
                ArchaeologyJournalClientState.setLastLogSortDescending(tag.getBoolean("lastLogSortDescending"));
            }
            if (tag.contains("lastLogGroupMode", Tag.TAG_STRING)) {
                try {
                    ArchaeologyJournalClientState.setLastLogGroupMode(
                            LogGrouper.GroupMode.valueOf(tag.getString("lastLogGroupMode")));
                } catch (IllegalArgumentException ignored) {
                }
            }
            if (tag.contains("lastRightPageTab", Tag.TAG_STRING)) {
                try {
                    ArchaeologyJournalClientState.setLastRightPageTab(
                            RightPageContainer.Tab.valueOf(tag.getString("lastRightPageTab")));
                } catch (IllegalArgumentException ignored) {
                }
            }
            // 收藏集合
            Set<ResourceLocation> favSet = new HashSet<>();
            if (tag.contains("favorites", Tag.TAG_LIST)) {
                for (Tag entry : tag.getList("favorites", Tag.TAG_STRING)) {
                    ResourceLocation id = ResourceLocation.tryParse(entry.getAsString());
                    if (id != null) {
                        favSet.add(id);
                    }
                }
            }
            ArchaeologyJournalClientState.replaceFavorites(favSet);
        } finally {
            ArchaeologyJournalClientState.setPersistenceSuppress(false);
        }
    }

    // 从 ClientState 内存字段抓取快照
    private static CompoundTag snapshotFromClientState() {
        CompoundTag tag = new CompoundTag();
        tag.put("panels", panelStates.copy());
        tag.put("simulationPreferences", simulationPreferences.copy());
        // 各 getter 背后是 volatile 字段，必须先用局部变量捕获单次快照再判空，
        // 避免「判空时读到非 null、取值时读到 null」的竞态 NPE
        ResourceLocation lastSelectedTableId = ArchaeologyJournalClientState.getLastSelectedTableId();
        if (lastSelectedTableId != null) {
            tag.putString("lastSelectedTable", lastSelectedTableId.toString());
        }
        CatalogSorter.SortOrder lastCatalogSortOrder = ArchaeologyJournalClientState.getLastCatalogSortOrder();
        if (lastCatalogSortOrder != null) {
            tag.putString("lastCatalogSortOrder", lastCatalogSortOrder.name());
        }
        tag.putBoolean("lastCatalogSortDescending", ArchaeologyJournalClientState.getLastCatalogSortDescending());
        tag.putBoolean("lastCatalogHideLocked", ArchaeologyJournalClientState.getLastCatalogHideLocked());
        String lastCatalogSearchText = ArchaeologyJournalClientState.getLastCatalogSearchText();
        if (lastCatalogSearchText != null) {
            tag.putString("lastCatalogSearchText", lastCatalogSearchText);
        }
        tag.putBoolean("lastLogSortDescending", ArchaeologyJournalClientState.getLastLogSortDescending());
        LogGrouper.GroupMode lastLogGroupMode = ArchaeologyJournalClientState.getLastLogGroupMode();
        if (lastLogGroupMode != null) {
            tag.putString("lastLogGroupMode", lastLogGroupMode.name());
        }
        RightPageContainer.Tab lastRightPageTab = ArchaeologyJournalClientState.getLastRightPageTab();
        if (lastRightPageTab != null) {
            tag.putString("lastRightPageTab", lastRightPageTab.name());
        }
        // 收藏集合
        var favoritesList = new net.minecraft.nbt.ListTag();
        for (ResourceLocation id : ArchaeologyJournalClientState.snapshotFavorites()) {
            if (id != null) {
                favoritesList.add(net.minecraft.nbt.StringTag.valueOf(id.toString()));
            }
        }
        tag.put("favorites", favoritesList);
        return tag;
    }

}
