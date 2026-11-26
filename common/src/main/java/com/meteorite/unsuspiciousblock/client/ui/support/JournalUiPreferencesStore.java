package com.meteorite.unsuspiciousblock.client.ui.support;

import com.meteorite.unsuspiciousblock.client.ui.journal.JournalReadingState;
import com.meteorite.unsuspiciousblock.client.ui.journal.JournalReadingStateCodec;
import com.meteorite.unsuspiciousblock.Constants;
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
    private static boolean earlyReading;
    private static final Set<String> earlyFields = new HashSet<>();
    private static final Set<ResourceLocation> earlyFavoriteToggles = new HashSet<>();

    private static final String SIMULATION = "simulationPreferences";
    private static final String FAVORITES = "favorites";
    private static final String CATALOG_SORT = "lastCatalogSortOrder";
    private static final String CATALOG_DESCENDING = "lastCatalogSortDescending";
    private static final String CATALOG_HIDE_LOCKED = "lastCatalogHideLocked";
    private static final String LOG_SORT = "lastLogSortDescending";
    private static final String LOG_GROUP = "lastLogGroupMode";
    private static JournalReadingState readingState = JournalReadingState.empty();
    private static boolean writable = true;
    private static CompoundTag simulationPreferences = new CompoundTag();

    @Nullable
    private static CatalogSorter.SortOrder lastCatalogSortOrder;
    private static boolean lastCatalogSortDescending;
    private static boolean lastCatalogHideLocked;
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
        if (loadedPath == null) earlyFields.add(SIMULATION);
        dirty = true;
    }

    /** 当前路径的持久阅读投影，目录位置仍属于连接内记忆。 */
    public static synchronized JournalReadingState readingState() { return readingState; }

    /** 保存同一次捕获的阅读目标，自动排除批选。 */
    public static synchronized void rememberReadingState(JournalReadingState state) {
        readingState = state.persistent();
        if (loadedPath == null) earlyReading = true;
        dirty = true;
    }

    @Nullable
    public static synchronized CatalogSorter.SortOrder getLastCatalogSortOrder() {
        return lastCatalogSortOrder;
    }

    public static synchronized void setLastCatalogSortOrder(@Nullable CatalogSorter.SortOrder order) {
        lastCatalogSortOrder = order;
        if (loadedPath == null) earlyFields.add(CATALOG_SORT);
        dirty = true;
    }

    public static synchronized boolean getLastCatalogSortDescending() {
        return lastCatalogSortDescending;
    }

    public static synchronized void setLastCatalogSortDescending(boolean descending) {
        lastCatalogSortDescending = descending;
        if (loadedPath == null) earlyFields.add(CATALOG_DESCENDING);
        dirty = true;
    }

    public static synchronized boolean getLastCatalogHideLocked() {
        return lastCatalogHideLocked;
    }

    public static synchronized void setLastCatalogHideLocked(boolean hideLocked) {
        lastCatalogHideLocked = hideLocked;
        if (loadedPath == null) earlyFields.add(CATALOG_HIDE_LOCKED);
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
        if (loadedPath == null) earlyFields.add(LOG_SORT);
        dirty = true;
    }

    @Nullable
    public static synchronized LogGrouper.GroupMode getLastLogGroupMode() {
        return lastLogGroupMode;
    }

    public static synchronized void setLastLogGroupMode(@Nullable LogGrouper.GroupMode mode) {
        lastLogGroupMode = mode;
        if (loadedPath == null) earlyFields.add(LOG_GROUP);
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
        if (loadedPath == null && !earlyFavoriteToggles.add(id)) earlyFavoriteToggles.remove(id);
        if (loadedPath == null) earlyFields.add(FAVORITES);
        dirty = true;
    }

    public static synchronized void tick() {
        flushIfDirty();
    }

    /** 冻结当前路径的脏快照，并重试尚未成功的待写数据。 */
    public static synchronized void flushIfDirty() {
        if (dirty && loadedPath != null && writable) {
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
        readingState = JournalReadingState.empty();
        writable = true;
        simulationPreferences = new CompoundTag();
        resetPreferences();
        dirty = false;
        earlyFields.clear();
        earlyFavoriteToggles.clear();
        earlyReading = false;
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
        writable = result.successful();
        if (!writable) return;
        try {
            CompoundTag tag = JournalReadingStateCodec.migrate(result.tag());
            CompoundTag early = snapshot();
            JournalReadingState reading = readingState;
            for (String field : earlyFields) {
                if (field.equals(SIMULATION) || field.equals(FAVORITES)) continue;
                Tag value = early.get(field);
                if (value != null) tag.put(field, value);
                else tag.remove(field);
            }
            if (earlyFields.contains(SIMULATION)) {
                tag.put(SIMULATION, tag.getCompound(SIMULATION).merge(early.getCompound(SIMULATION)));
            }
            applyTag(tag);
            if (earlyReading) readingState = reading;
            if (!earlyFavoriteToggles.isEmpty()) {
                Set<ResourceLocation> merged = new HashSet<>(favorites);
                for (ResourceLocation id : earlyFavoriteToggles) {
                    if (!merged.add(id)) merged.remove(id);
                }
                favorites = Set.copyOf(merged);
            }
            earlyFields.clear();
            earlyFavoriteToggles.clear();
            earlyReading = false;
        } catch (IllegalArgumentException exception) {
            writable = false;
            Constants.LOG.warn("[{}] 笔记 UI 偏好格式无法读取，保留源文件: {}", Constants.MOD_ID, path, exception);
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
        lastCatalogSortOrder = null;
        lastLogGroupMode = null;
        readingState = JournalReadingStateCodec.read(tag);
        simulationPreferences = tag.getCompound(SIMULATION).copy();
        if (tag.contains(CATALOG_SORT, Tag.TAG_STRING)) {
            try {
                lastCatalogSortOrder = CatalogSorter.SortOrder.valueOf(tag.getString(CATALOG_SORT));
            } catch (IllegalArgumentException ignored) {
                lastCatalogSortOrder = null;
            }
        }
        if (tag.contains(CATALOG_DESCENDING, Tag.TAG_BYTE)) {
            lastCatalogSortDescending = tag.getBoolean(CATALOG_DESCENDING);
        }
        if (tag.contains(CATALOG_HIDE_LOCKED, Tag.TAG_BYTE)) {
            lastCatalogHideLocked = tag.getBoolean(CATALOG_HIDE_LOCKED);
        }
        if (tag.contains(LOG_SORT, Tag.TAG_BYTE)) {
            lastLogSortDescending = tag.getBoolean(LOG_SORT);
        }
        if (tag.contains(LOG_GROUP, Tag.TAG_STRING)) {
            try {
                lastLogGroupMode = LogGrouper.GroupMode.valueOf(tag.getString(LOG_GROUP));
            } catch (IllegalArgumentException ignored) {
                lastLogGroupMode = null;
            }
        }
        ListTag favoriteList = tag.getList(FAVORITES, Tag.TAG_STRING);
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
        JournalReadingStateCodec.write(tag, readingState);
        tag.put(SIMULATION, simulationPreferences.copy());
        if (lastCatalogSortOrder != null) {
            tag.putString(CATALOG_SORT, lastCatalogSortOrder.name());
        }
        tag.putBoolean(CATALOG_DESCENDING, lastCatalogSortDescending);
        tag.putBoolean(CATALOG_HIDE_LOCKED, lastCatalogHideLocked);
        tag.putBoolean(LOG_SORT, lastLogSortDescending);
        if (lastLogGroupMode != null) {
            tag.putString(LOG_GROUP, lastLogGroupMode.name());
        }
        ListTag favoritesTag = new ListTag();
        for (ResourceLocation id : favorites) {
            favoritesTag.add(StringTag.valueOf(id.toString()));
        }
        tag.put(FAVORITES, favoritesTag);
        return tag;
    }

    private static void resetPreferences() {
        readingState = JournalReadingState.empty();
        lastCatalogSortOrder = null;
        lastCatalogSortDescending = false;
        lastCatalogHideLocked = false;
        lastDirectoryHome = true;
        lastDirectoryCategory = null;
        expandedDirectoryTables = Collections.emptySet();
        lastDirectoryPage = 0;
        lastCategoryFocus = 0;
        categorySelections.clear();
        lastLogSortDescending = true;
        lastLogGroupMode = null;
        favorites = Collections.emptySet();
    }
}
