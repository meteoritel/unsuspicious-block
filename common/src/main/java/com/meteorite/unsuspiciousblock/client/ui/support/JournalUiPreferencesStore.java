package com.meteorite.unsuspiciousblock.client.ui.support;

import com.meteorite.unsuspiciousblock.client.ui.panel.RightPageContainer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** 考古笔记 UI 偏好的 per-world 客户端持久化与内存所有者。 */
public final class JournalUiPreferencesStore {

    static final String STORAGE_FILE = "journal_ui_preferences.dat";

    @Nullable
    private static volatile Path loadedPath;
    private static final JournalLocalPersistence.PendingWrites writes = new JournalLocalPersistence.PendingWrites();
    private static volatile boolean dirty;
    private static CompoundTag panelStates = new CompoundTag();
    private static CompoundTag simulationPreferences = new CompoundTag();

    @Nullable
    private static ResourceLocation lastSelectedTableId;
    @Nullable
    private static CatalogSorter.SortOrder lastCatalogSortOrder;
    private static boolean lastCatalogSortDescending;
    private static boolean lastCatalogHideLocked;
    private static String lastCatalogSearchText = "";
    private static boolean lastDirectoryHome = true;
    @Nullable
    private static ResourceLocation lastDirectoryCategory;
    private static Set<ResourceLocation> expandedDirectoryTables = Collections.emptySet();
    private static int lastDirectoryPage;
    private static int lastCategoryFocus;
    private static final Map<ResourceLocation, ResourceLocation> categorySelections = new LinkedHashMap<>();
    private static boolean lastLogSortDescending = true;
    @Nullable
    private static LogGrouper.GroupMode lastLogGroupMode;
    @Nullable
    private static RightPageContainer.Tab lastRightPageTab;
    private static Set<ResourceLocation> favorites = Collections.emptySet();

    private JournalUiPreferencesStore() {
    }

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

    public static synchronized CompoundTag getPanelStates() {
        return panelStates.copy();
    }

    public static synchronized void setPanelStates(CompoundTag state) {
        panelStates = state.copy();
        dirty = true;
    }

    @Nullable
    public static synchronized ResourceLocation getLastSelectedTableId() {
        return lastSelectedTableId;
    }

    /** 记住当前选择并标记待保存；null 表示尚未选表。 */
    public static synchronized void rememberLastSelectedTable(@Nullable ResourceLocation tableId) {
        lastSelectedTableId = tableId;
        dirty = true;
    }

    @Nullable
    public static synchronized CatalogSorter.SortOrder getLastCatalogSortOrder() {
        return lastCatalogSortOrder;
    }

    public static synchronized void setLastCatalogSortOrder(@Nullable CatalogSorter.SortOrder order) {
        lastCatalogSortOrder = order;
        dirty = true;
    }

    public static synchronized boolean getLastCatalogSortDescending() {
        return lastCatalogSortDescending;
    }

    public static synchronized void setLastCatalogSortDescending(boolean descending) {
        lastCatalogSortDescending = descending;
        dirty = true;
    }

    public static synchronized boolean getLastCatalogHideLocked() {
        return lastCatalogHideLocked;
    }

    public static synchronized void setLastCatalogHideLocked(boolean hideLocked) {
        lastCatalogHideLocked = hideLocked;
        dirty = true;
    }

    public static synchronized void setLastCatalogSearchText(@Nullable String text) {
        lastCatalogSearchText = text == null ? "" : text;
        dirty = true;
    }

    public static synchronized boolean isLastDirectoryHome() {
        return lastDirectoryHome;
    }

    @Nullable
    public static synchronized ResourceLocation getLastDirectoryCategory() {
        return lastDirectoryCategory;
    }

    public static synchronized Set<ResourceLocation> getExpandedDirectoryTables() {
        return expandedDirectoryTables;
    }

    /** 保存本次连接的目录位置与展开状态；这些运行期字段不写入 NBT。 */
    public static synchronized void rememberDirectoryState(boolean home, @Nullable ResourceLocation category,
                                                            Set<ResourceLocation> expanded, int page,
                                                            int categoryFocus) {
        lastDirectoryHome = home;
        lastDirectoryCategory = category;
        expandedDirectoryTables = Set.copyOf(expanded);
        lastDirectoryPage = Math.max(0, page);
        lastCategoryFocus = Math.max(0, categoryFocus);
    }

    public static synchronized int getLastDirectoryPage() {
        return lastDirectoryPage;
    }

    public static synchronized int getLastCategoryFocus() {
        return lastCategoryFocus;
    }

    @Nullable
    public static synchronized ResourceLocation getCategorySelection(ResourceLocation categoryId) {
        return categorySelections.get(categoryId);
    }

    /** 记住本次连接中各分类最后选中的表。 */
    public static synchronized void rememberCategorySelection(ResourceLocation categoryId, ResourceLocation tableId) {
        categorySelections.put(categoryId, tableId);
    }

    public static synchronized boolean getLastLogSortDescending() {
        return lastLogSortDescending;
    }

    public static synchronized void setLastLogSortDescending(boolean descending) {
        lastLogSortDescending = descending;
        dirty = true;
    }

    @Nullable
    public static synchronized LogGrouper.GroupMode getLastLogGroupMode() {
        return lastLogGroupMode;
    }

    public static synchronized void setLastLogGroupMode(@Nullable LogGrouper.GroupMode mode) {
        lastLogGroupMode = mode;
        dirty = true;
    }

    @Nullable
    public static synchronized RightPageContainer.Tab getLastRightPageTab() {
        return lastRightPageTab;
    }

    public static synchronized void setLastRightPageTab(@Nullable RightPageContainer.Tab tab) {
        lastRightPageTab = tab;
        dirty = true;
    }

    public static synchronized boolean isFavorite(ResourceLocation id) {
        return favorites.contains(id);
    }

    /** 切换收藏并以不可变集合发布，待客户端 tick 保存。 */
    public static synchronized void toggleFavorite(ResourceLocation id) {
        Set<ResourceLocation> copy = new HashSet<>(favorites);
        if (!copy.add(id)) {
            copy.remove(id);
        }
        favorites = Set.copyOf(copy);
        dirty = true;
    }

    public static synchronized void tick() {
        flushIfDirty();
    }

    /** 冻结当前路径的脏快照，并重试尚未成功的待写数据。 */
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
        resetPreferences();
        dirty = false;
    }

    static synchronized void loadCurrentPath(Path path) {
        if (!path.equals(loadedPath)) {
            loadedPath = path;
            load(path);
        }
    }

    private static void load(Path path) {
        CompoundTag pending = writes.pending(path);
        JournalLocalPersistence.Loaded result = pending == null
                ? JournalLocalPersistence.load(path, Long.MAX_VALUE)
                : new JournalLocalPersistence.Loaded(pending, true);
        if (result.successful()) {
            applyTag(result.tag());
        }
    }

    private static boolean save() {
        Path path = loadedPath;
        if (path == null) {
            return false;
        }
        writes.stage(path, snapshot());
        writes.flush();
        return writes.isIdle(path);
    }

    private static void applyTag(CompoundTag tag) {
        resetPreferences();
        panelStates = tag.getCompound("panels").copy();
        simulationPreferences = tag.getCompound("simulationPreferences").copy();
        if (tag.contains("lastSelectedTable", Tag.TAG_STRING)) {
            lastSelectedTableId = ResourceLocation.tryParse(tag.getString("lastSelectedTable"));
        }
        if (tag.contains("lastCatalogSortOrder", Tag.TAG_STRING)) {
            try {
                lastCatalogSortOrder = CatalogSorter.SortOrder.valueOf(tag.getString("lastCatalogSortOrder"));
            } catch (IllegalArgumentException ignored) {
                lastCatalogSortOrder = null;
            }
        }
        if (tag.contains("lastCatalogSortDescending", Tag.TAG_BYTE)) {
            lastCatalogSortDescending = tag.getBoolean("lastCatalogSortDescending");
        }
        if (tag.contains("lastCatalogHideLocked", Tag.TAG_BYTE)) {
            lastCatalogHideLocked = tag.getBoolean("lastCatalogHideLocked");
        }
        if (tag.contains("lastCatalogSearchText", Tag.TAG_STRING)) {
            lastCatalogSearchText = tag.getString("lastCatalogSearchText");
        }
        if (tag.contains("lastLogSortDescending", Tag.TAG_BYTE)) {
            lastLogSortDescending = tag.getBoolean("lastLogSortDescending");
        }
        if (tag.contains("lastLogGroupMode", Tag.TAG_STRING)) {
            try {
                lastLogGroupMode = LogGrouper.GroupMode.valueOf(tag.getString("lastLogGroupMode"));
            } catch (IllegalArgumentException ignored) {
                lastLogGroupMode = null;
            }
        }
        if (tag.contains("lastRightPageTab", Tag.TAG_STRING)) {
            try {
                lastRightPageTab = RightPageContainer.Tab.valueOf(tag.getString("lastRightPageTab"));
            } catch (IllegalArgumentException ignored) {
                lastRightPageTab = null;
            }
        }
        ListTag favoriteList = tag.getList("favorites", Tag.TAG_STRING);
        Set<ResourceLocation> loadedFavorites = new HashSet<>();
        for (Tag entry : favoriteList) {
            ResourceLocation id = ResourceLocation.tryParse(entry.getAsString());
            if (id != null) {
                loadedFavorites.add(id);
            }
        }
        favorites = Set.copyOf(loadedFavorites);
    }

    private static CompoundTag snapshot() {
        CompoundTag tag = new CompoundTag();
        tag.put("panels", panelStates.copy());
        tag.put("simulationPreferences", simulationPreferences.copy());
        if (lastSelectedTableId != null) {
            tag.putString("lastSelectedTable", lastSelectedTableId.toString());
        }
        if (lastCatalogSortOrder != null) {
            tag.putString("lastCatalogSortOrder", lastCatalogSortOrder.name());
        }
        tag.putBoolean("lastCatalogSortDescending", lastCatalogSortDescending);
        tag.putBoolean("lastCatalogHideLocked", lastCatalogHideLocked);
        tag.putString("lastCatalogSearchText", lastCatalogSearchText);
        tag.putBoolean("lastLogSortDescending", lastLogSortDescending);
        if (lastLogGroupMode != null) {
            tag.putString("lastLogGroupMode", lastLogGroupMode.name());
        }
        if (lastRightPageTab != null) {
            tag.putString("lastRightPageTab", lastRightPageTab.name());
        }
        ListTag favoritesTag = new ListTag();
        for (ResourceLocation id : favorites) {
            favoritesTag.add(StringTag.valueOf(id.toString()));
        }
        tag.put("favorites", favoritesTag);
        return tag;
    }

    private static void resetPreferences() {
        lastSelectedTableId = null;
        lastCatalogSortOrder = null;
        lastCatalogSortDescending = false;
        lastCatalogHideLocked = false;
        lastCatalogSearchText = "";
        lastDirectoryHome = true;
        lastDirectoryCategory = null;
        expandedDirectoryTables = Collections.emptySet();
        lastDirectoryPage = 0;
        lastCategoryFocus = 0;
        categorySelections.clear();
        lastLogSortDescending = true;
        lastLogGroupMode = null;
        lastRightPageTab = null;
        favorites = Collections.emptySet();
    }
}
