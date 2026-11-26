package com.meteorite.unsuspiciousblock.client.ui.journal.catalog;

import java.util.Objects;

import net.minecraft.locale.Language;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.DeclaredChance;

import com.meteorite.unsuspiciousblock.client.ui.entry.ArchaeologyJournalEntry;
import com.meteorite.unsuspiciousblock.client.ui.panel.DetailOverlayPanel;
import com.meteorite.unsuspiciousblock.client.ui.panel.ItemGridPanel;
import com.meteorite.unsuspiciousblock.client.ui.support.ArchaeologyJournalClientState;
import com.meteorite.unsuspiciousblock.client.ui.support.ClientLootTableLanguageStore;
import com.meteorite.unsuspiciousblock.client.ui.support.JournalClientConnection;
import com.meteorite.unsuspiciousblock.client.ui.support.JournalUiPreferencesStore;
import com.meteorite.unsuspiciousblock.client.ui.support.CatalogSorter;
import com.meteorite.unsuspiciousblock.client.ui.support.JournalSearchQuery;
import com.meteorite.unsuspiciousblock.core.journal.state.ArchaeologyJournalState;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.CatalogQueryIndex;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.Probability;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.LootTableCatalog.CatalogCategoryDefinition;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.LootTableCatalog.CatalogStructure;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.LootTableCatalog.ChildTableProbability;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.LootTableCatalog.ItemDefinition;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.LootTableCatalog.LootAcquisitionPath;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.LootTableCatalog.TableDefinition;
import net.minecraft.core.registries.BuiltInRegistries;
import com.meteorite.unsuspiciousblock.core.loottable.signature.LootResultSignature;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 考古笔记视图模型——管理分类首页、父子目录、搜索排序与右页数据快照。
 */
public class JournalViewModel {

    private ArchaeologyJournalState state;
    private static final int CHILD_PREVIEW_SIZE = 3;

    private Map<ResourceLocation, TableDefinition> catalogDefinitions = Map.of();
    private Map<ResourceLocation, TableDefinition> visibleDefinitions = Map.of();
    private final Map<ResourceLocation, ArchaeologyJournalEntry> allViews = new LinkedHashMap<>();
    private final List<ArchaeologyJournalEntry> tableViews = new ArrayList<>();
    private final List<RowMeta> rowMetadata = new ArrayList<>();
    private final List<CategoryEntryData> categoryViews = new ArrayList<>();
    private final Map<ResourceLocation, List<ResourceLocation>> parentIdsByChild = new LinkedHashMap<>();
    private final Map<ResourceLocation, Set<ResourceLocation>> categoryIdsByTable = new LinkedHashMap<>();
    private final Map<ResourceLocation, Set<ResourceLocation>> membersByCategory = new LinkedHashMap<>();
    private final Map<ResourceLocation, List<ItemDefinition>> directItemsByTable = new LinkedHashMap<>();
    private CatalogStructure structure = CatalogStructure.empty();
    private final Set<ResourceLocation> expandedRoots = new LinkedHashSet<>();
    private boolean categoryHome = true;
    @Nullable
    private ResourceLocation selectedCategory;
    private int selectedIndex = -1;
    private int totalTableCount;
    private int globalUnlockedTableCount;
    private JournalSearchQuery currentSearch = JournalSearchQuery.EMPTY;
    private CatalogSorter.SortOrder currentSortOrder = CatalogSorter.SortOrder.DEFAULT;
    private boolean sortDescending;
    private boolean hideLocked;
    private long lastConnectionEpoch;
    private long lastScenarioRevision;
    private long lastNameRevision = -1;
    private Language lastLanguage;
    private long lastStateRevision;
    private boolean lastReadingReady;
    private long lastLogRevision;

    private @Nullable Map<ResourceLocation, List<ResourceLocation>> firstPathsByTableCache;
    private final Map<ResourceLocation, List<ItemDefinition>> subtreeItemsCache = new LinkedHashMap<>();
    private final Map<ResourceLocation, List<ItemDefinition>> completionItemsCache = new LinkedHashMap<>();
    private @Nullable TableDefinition gridDefinition;
    private @Nullable GridCacheKey gridCacheKey;
    private @Nullable BuildGridResult gridCacheResult;

    private final Map<ResourceLocation, String> displayNameTexts = new LinkedHashMap<>();
    private final Map<ResourceLocation, String> lowercaseTableNames = new LinkedHashMap<>();
    private final Map<ResourceLocation, List<JournalSearchQuery.LowercaseItem>> lowercaseTableItems =
            new LinkedHashMap<>();
    private long nameOverrideRevision = Long.MIN_VALUE;
    private @Nullable Map<ResourceLocation, TableDefinition> nameCacheCatalog;
    private Language nameCacheLanguage;

    public JournalViewModel() {
        this.state = ArchaeologyJournalClientState.getState();
    }

    public void initRevisions() {
        this.lastConnectionEpoch = JournalClientConnection.epoch();
        this.lastLogRevision = JournalClientConnection.log().sortRevision();
        this.lastScenarioRevision = JournalClientConnection.scenario().projectionRevision();
        this.lastNameRevision = ClientLootTableLanguageStore.revision();
        this.lastLanguage = Language.getInstance();
        this.state = ArchaeologyJournalClientState.getState();
        reloadCatalog();
        this.lastStateRevision = ArchaeologyJournalClientState.getStateRevision();
        this.lastReadingReady = ArchaeologyJournalClientState.isReadingReady();
    }

    /**
     * 接收目录工具栏的同批查询输入，投影刷新由根装配点调用。
     */
    public void applyCatalogState(CatalogQueryState queryState) {
        this.currentSearch = queryState.search();
        // 物品文本缓存只服务搜索匹配：离开搜索态即释放，避免大目录长期驻留整份物品小写名
        if (this.currentSearch.isEmpty()) {
            this.lowercaseTableItems.clear();
        }
        this.currentSortOrder = queryState.sortOrder();
        this.sortDescending = queryState.sortDescending();
        this.hideLocked = queryState.hideLocked();
    }

    public List<ArchaeologyJournalEntry> tableViews() {
        return this.tableViews;
    }

    /** 当前目录是否收录该表。 */
    public boolean hasCatalogTable(ResourceLocation table) {
        return catalogDefinitions.containsKey(table);
    }

    public boolean isNavigationTargetValid(ResourceLocation table) {
        var entry = allViews.get(table);
        return entry != null && entry.unlocked();
    }

    public List<CategoryEntryData> categoryViews() {
        return List.copyOf(this.categoryViews);
    }

    public int selectedIndex() {
        return this.selectedIndex;
    }

    public void setSelectedIndex(int index) {
        this.selectedIndex = index;
        if (index < 0 || index >= this.tableViews.size()) {
            return;
        }

        ResourceLocation selectedId = this.tableViews.get(index).id();
        // 搜索结果来自全目录，选中时同步正式分类与父级展开状态，确保退出搜索后仍能定位同一条目。
        if (!this.currentSearch.isEmpty()) {
            prepareNavigationTo(selectedId);
        }
        if (this.selectedCategory != null
                && this.categoryIdsByTable.getOrDefault(selectedId, Set.of()).contains(this.selectedCategory)) {
            JournalUiPreferencesStore.rememberCategorySelection(
                    this.selectedCategory, selectedId);
        }
    }

    public boolean isCategoryHome() {
        return this.categoryHome && this.currentSearch.isEmpty();
    }

    public boolean isCategoryHomeMode() {
        return this.categoryHome;
    }

    @Nullable
    public ResourceLocation selectedCategory() {
        return this.selectedCategory;
    }

    public Set<ResourceLocation> expandedRoots() {
        return Set.copyOf(this.expandedRoots);
    }

    public void showCategoryHome() {
        this.categoryHome = true;
        this.selectedIndex = -1;
    }

    public void enterCategory(ResourceLocation categoryId) {
        this.categoryHome = false;
        this.selectedCategory = categoryId;
        this.selectedIndex = -1;
    }

    public void restoreDirectoryState(boolean home, @Nullable ResourceLocation categoryId,
                                      Set<ResourceLocation> expanded) {
        this.categoryHome = home;
        this.selectedCategory = categoryId;
        this.expandedRoots.clear();
        this.expandedRoots.addAll(expanded);
    }

    public void toggleExpanded(int rowIndex) {
        if (rowIndex < 0 || rowIndex >= this.rowMetadata.size()) return;
        ResourceLocation id = this.tableViews.get(rowIndex).id();
        if (!this.rowMetadata.get(rowIndex).hasChildren()) return;
        if (!this.expandedRoots.add(id)) this.expandedRoots.remove(id);
    }

    // 默认定位优先使用目标自己的顶层入口。
    public boolean prepareNavigationTo(ResourceLocation tableId) {
        List<ResourceLocation> parentPath = firstPathsByTable().get(tableId);
        if (parentPath == null) return false;
        ResourceLocation rootId = parentPath.isEmpty() ? tableId : parentPath.getFirst();
        this.selectedCategory = this.structure.rootCategories().get(rootId);
        this.categoryHome = false;
        this.expandedRoots.addAll(parentPath);
        return true;
    }

    /** 从当前出现路径展开子表，同一表在其他父路径的出现位置不参与定位。 */
    public @Nullable RowLocation prepareNavigationToChild(ResourceLocation childId) {
        RowLocation parent = selectedLocation();
        if (parent == null || !catalogDefinitions.get(parent.table()).childTables().contains(childId)) return null;
        selectedCategory = parent.category();
        categoryHome = false;
        List<ResourceLocation> path = new ArrayList<>(parent.parentPath());
        path.add(parent.table());
        expandedRoots.addAll(path);
        return new RowLocation(parent.category(), path, childId);
    }

    /** 返回当前投影中同一出现位置的行。 */
    public int findRow(RowLocation location) {
        for (int index = 0; index < tableViews.size(); index++) {
            if (location.equals(rowLocation(index))) return index;
        }
        return -1;
    }

    /** 表级阅读目标优先定位自身的顶层入口。 */
    public int findNavigationRow(ResourceLocation tableId) {
        int fallback = -1;
        for (int index = 0; index < tableViews.size(); index++) {
            if (!tableViews.get(index).id().equals(tableId)) continue;
            if (rowMetadata.get(index).parentPath().isEmpty()) return index;
            if (fallback < 0) fallback = index;
        }
        return fallback;
    }

    /** 捕获行身份，供导航历史和子页返回使用。 */
    public @Nullable RowLocation selectedLocation() {
        return selectedIndex < 0 ? null : rowLocation(selectedIndex);
    }

    private RowLocation rowLocation(int index) {
        ResourceLocation table = tableViews.get(index).id();
        List<ResourceLocation> path = rowMetadata.get(index).parentPath();
        ResourceLocation root = path.isEmpty() ? table : path.getFirst();
        return new RowLocation(structure.rootCategories().get(root), path, table);
    }

    /** 目录行的完整出现位置，与共享的表级进度身份分别使用。 */
    public record RowLocation(@Nullable ResourceLocation category, List<ResourceLocation> parentPath, ResourceLocation table) {
        public RowLocation {
            parentPath = List.copyOf(parentPath);
        }
    }

    public boolean rowHasChildren(int rowIndex) {
        return rowIndex >= 0 && rowIndex < this.rowMetadata.size()
                && this.rowMetadata.get(rowIndex).hasChildren();
    }

    public boolean rowExpanded(int rowIndex) {
        return rowIndex >= 0 && rowIndex < this.rowMetadata.size()
                && this.rowMetadata.get(rowIndex).expanded();
    }

    public boolean rowIsChild(int rowIndex) {
        return rowIndex >= 0 && rowIndex < this.rowMetadata.size() && this.rowMetadata.get(rowIndex).child();
    }

    public List<Component> rowReferencingParentNames(int rowIndex) {
        if (rowIndex < 0 || rowIndex >= this.rowMetadata.size()) return List.of();
        ResourceLocation childId = this.tableViews.get(rowIndex).id();
        Set<ResourceLocation> rowCategories = new LinkedHashSet<>(this.rowMetadata.get(rowIndex).categoryIds());
        return this.parentIdsByChild.getOrDefault(childId, List.of()).stream()
                .filter(parentId -> rowCategories.isEmpty()
                        || this.categoryIdsByTable.getOrDefault(parentId, Set.of()).stream()
                        .anyMatch(rowCategories::contains))
                .sorted(Comparator.comparing((ResourceLocation parentId) -> parentDisplayName(parentId).getString(),
                                String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(ResourceLocation::toString))
                .map(this::parentDisplayName)
                .toList();
    }

    private Component parentDisplayName(ResourceLocation parentId) {
        ArchaeologyJournalEntry parent = this.allViews.get(parentId);
        return parent != null ? parent.displayName() : Component.literal(parentId.getPath());
    }

    @Nullable
    public ResourceLocation selectedTableId() {
        ArchaeologyJournalEntry selected = selectedTable();
        return selected != null ? selected.id() : null;
    }

    @Nullable
    public ArchaeologyJournalEntry selectedTable() {
        return this.selectedIndex >= 0 && this.selectedIndex < this.tableViews.size()
                ? this.tableViews.get(this.selectedIndex) : null;
    }

    public int totalTableCount() {
        return this.totalTableCount;
    }

    public int displayedUnlockedTableCount() {
        CategoryEntryData category = selectedCategoryView();
        return category != null ? category.unlocked() : this.globalUnlockedTableCount;
    }

    public int displayedTableCount() {
        CategoryEntryData category = selectedCategoryView();
        return category != null ? category.total() : this.totalTableCount;
    }

    @Nullable
    private CategoryEntryData selectedCategoryView() {
        if (this.categoryHome || this.selectedCategory == null) return null;
        return this.categoryViews.stream()
                .filter(category -> category.id().equals(this.selectedCategory))
                .findFirst().orElse(null);
    }

    public boolean isEmpty() {
        return this.tableViews.isEmpty();
    }

    private void reloadCatalog() {
        catalogDefinitions = ArchaeologyJournalClientState.getBaseCatalog();
        structure = ArchaeologyJournalClientState.getCatalogStructure();
        visibleDefinitions = ArchaeologyJournalClientState.getCatalog();
        directItemsByTable.clear();
        visibleDefinitions.forEach((id, definition) -> directItemsByTable.put(id,
                definition.items().stream().filter(CatalogQueryIndex::hasDirectSource).toList()));
        rebuildRelationshipIndexes();
        firstPathsByTableCache = null;
        subtreeItemsCache.clear();
        completionItemsCache.clear();
        gridDefinition = null;
        nameCacheCatalog = null;
    }

    /** 根 Screen 需要提交的最小刷新范围。 */
    public enum Refresh { NONE, ORDER, RIGHT_PAGE, CONTENT }

    public Refresh refreshIfNeeded() {
        long epoch = JournalClientConnection.epoch();
        long logRevision = JournalClientConnection.log().sortRevision();
        boolean connectionChanged = epoch != lastConnectionEpoch;
        lastConnectionEpoch = epoch;
        boolean catalogChanged = catalogDefinitions != ArchaeologyJournalClientState.getBaseCatalog();
        long stateRevision = ArchaeologyJournalClientState.getStateRevision();
        boolean readingReady = ArchaeologyJournalClientState.isReadingReady();
        boolean readinessChanged = readingReady != lastReadingReady;
        lastReadingReady = readingReady;
        long names = ClientLootTableLanguageStore.revision();
        Language language = Language.getInstance();
        boolean namesChanged = names != lastNameRevision || language != lastLanguage;
        lastNameRevision = names;
        lastLanguage = language;
        boolean logChanged = logRevision != lastLogRevision;
        lastLogRevision = logRevision;
        boolean progressChanged = stateRevision != lastStateRevision;
        if (connectionChanged || progressChanged) {
            lastStateRevision = stateRevision;
            state = ArchaeologyJournalClientState.getState();
        }
        if (connectionChanged || catalogChanged) reloadCatalog();
        long scenarioRevision = JournalClientConnection.scenario().projectionRevision();
        boolean scenarioChanged = scenarioRevision != lastScenarioRevision;
        lastScenarioRevision = scenarioRevision;
        boolean candidatesChanged = scenarioChanged && refreshScenarioCandidates();
        if (connectionChanged || readinessChanged || namesChanged || catalogChanged || progressChanged || candidatesChanged) {
            return Refresh.CONTENT;
        }
        if (logChanged && currentSortOrder == CatalogSorter.SortOrder.UPDATE_TIME) {
            return Refresh.ORDER;
        }
        return scenarioChanged ? Refresh.RIGHT_PAGE : Refresh.NONE;
    }

    // 场景结果可能带来运行时发现。概率变化保留目录，候选变化才更新搜索、计数与介绍。
    private boolean refreshScenarioCandidates() {
        var next = ArchaeologyJournalClientState.getCatalog();
        if (next == visibleDefinitions) return false;
        boolean changed = false;
        for (var entry : next.entrySet()) {
            ResourceLocation id = entry.getKey();
            var previous = visibleDefinitions.get(id);
            if (entry.getValue() == previous || sameCandidates(previous.items(), entry.getValue().items())) continue;
            directItemsByTable.put(id, entry.getValue().items().stream().filter(CatalogQueryIndex::hasDirectSource).toList());
            lowercaseTableItems.remove(id);
            changed = true;
        }
        visibleDefinitions = next;
        if (changed) {
            subtreeItemsCache.clear();
            gridDefinition = null;
        }
        return changed;
    }

    private static boolean sameCandidates(List<ItemDefinition> before, List<ItemDefinition> after) {
        if (before.size() != after.size()) return false;
        for (int index = 0; index < before.size(); index++) {
            ItemDefinition a = before.get(index);
            ItemDefinition b = after.get(index);
            if (!a.signature().equals(b.signature()) || !a.displayName().equals(b.displayName())
                    || CatalogQueryIndex.hasDirectSource(a) != CatalogQueryIndex.hasDirectSource(b)) return false;
        }
        return true;
    }

    /** 排序变化沿相同表和父路径恢复选中行。 */
    public void reorderRows() {
        ResourceLocation table = selectedTableId();
        List<ResourceLocation> path = table == null ? List.of() : this.rowMetadata.get(this.selectedIndex).parentPath();
        rebuildTableRows();
        this.selectedIndex = resolveSelectedIndex(table, path);
    }

    /**
     * 同批重建分类、目录行与选择，视图提交由根 Screen 统一完成。
     */
    public void rebuildViewModels() {
        ResourceLocation selectedId = selectedTableId();
        List<ResourceLocation> selectedPath = selectedId == null ? List.of()
                : this.rowMetadata.get(this.selectedIndex).parentPath();
        this.allViews.clear();
        int unlocked = 0;
        for (Map.Entry<ResourceLocation, TableDefinition> entry : this.catalogDefinitions.entrySet()) {
            ResourceLocation id = entry.getKey();
            var progress = state.getTable(id);
            ArchaeologyJournalEntry view = new ArchaeologyJournalEntry(id, ClientLootTableLanguageStore.displayName(id),
                    entry.getValue().type(), directItemsByTable.get(id).size(), progress != null && progress.isUnlocked(),
                    JournalUiPreferencesStore.isFavorite(id));
            this.allViews.put(id, view);
            if (view.unlocked()) unlocked++;
        }
        this.totalTableCount = this.allViews.size();
        this.globalUnlockedTableCount = unlocked;
        rebuildCategories();
        this.expandedRoots.retainAll(this.catalogDefinitions.keySet());
        if (!this.categoryHome && this.currentSearch.isEmpty()
                && this.categoryViews.stream().noneMatch(category -> category.id().equals(this.selectedCategory))) {
            this.categoryHome = true;
            this.selectedCategory = null;
        }
        rebuildTableRows();

        this.selectedIndex = resolveSelectedIndex(selectedId, selectedPath);

    }

    private void rebuildCategories() {
        this.categoryViews.clear();
        for (CatalogCategoryDefinition definition : this.structure.categories()) {
            Set<ResourceLocation> members = membersByCategory.getOrDefault(definition.id(), Set.of());
            if (members.isEmpty()) continue;
            int unlocked = (int) members.stream().map(this.allViews::get)
                    .filter(view -> view != null && view.unlocked()).count();
            if (this.hideLocked && unlocked == 0) continue;
            Item iconItem = BuiltInRegistries.ITEM.get(definition.iconItem());
            if (iconItem == Items.AIR) iconItem = Items.BOOK;
            Component name = definition.translationKey().isBlank()
                    ? Component.literal(definition.fallbackName())
                    : Component.translatableWithFallback(definition.translationKey(), definition.fallbackName());
            Component description = definition.descriptionKey().isBlank()
                    ? Component.literal(definition.descriptionFallback())
                    : Component.translatableWithFallback(definition.descriptionKey(), definition.descriptionFallback());
            this.categoryViews.add(new CategoryEntryData(
                    definition.id(), name, description, new ItemStack(iconItem), unlocked, members.size()));
        }
    }

    private void rebuildTableRows() {
        this.tableViews.clear();
        this.rowMetadata.clear();
        if (isCategoryHome()) return;
        if (!this.currentSearch.isEmpty()) {
            rebuildSearchRows();
            return;
        }
        if (this.selectedCategory == null) return;
        List<ArchaeologyJournalEntry> roots = this.structure.rootCategories().entrySet().stream()
                .filter(entry -> entry.getValue().equals(this.selectedCategory))
                .map(Map.Entry::getKey).map(this.allViews::get).filter(Objects::nonNull)
                .filter(view -> !this.hideLocked || view.unlocked())
                .sorted(rootComparator())
                .toList();
        for (ArchaeologyJournalEntry root : roots) {
            addRow(root, 0, false, List.of(), List.of(this.selectedCategory));
            if (this.expandedRoots.contains(root.id())) {
                flattenChildren(root.id(), 1, List.of(root.id()), new HashSet<>());
            }
        }
    }

    private void rebuildSearchRows() {
        Map<ResourceLocation, List<ResourceLocation>> firstPaths = firstPathsByTable();
        List<ArchaeologyJournalEntry> matches = this.allViews.values().stream()
                .filter(view -> !this.hideLocked || view.unlocked())
                .filter(this::matchesCurrentSearch)
                .sorted(sorter())
                .toList();
        for (ArchaeologyJournalEntry view : matches) {
            List<ResourceLocation> parentPath = firstPaths.getOrDefault(view.id(), List.of());
            addRow(view, 0, !parentPath.isEmpty(), parentPath,
                    List.copyOf(this.categoryIdsByTable.getOrDefault(view.id(), Set.of())));
        }
    }

    private void flattenChildren(ResourceLocation parentId, int depth, List<ResourceLocation> path,
                                 Set<ResourceLocation> branch) {
        // 上游 rebuildTableRows 已对 selectedCategory 判空，此处防御 IDE 可空性告警
        if (this.selectedCategory == null) return;
        if (!branch.add(parentId)) return;
        TableDefinition parent = this.catalogDefinitions.get(parentId);
        if (parent == null) return;
        List<ArchaeologyJournalEntry> children = parent.childTables().stream().map(this.allViews::get)
                .filter(Objects::nonNull)
                .filter(view -> !this.hideLocked || view.unlocked())
                .sorted(Comparator
                        .comparing((ArchaeologyJournalEntry view) -> !hasChildTables(view.id()))
                        .thenComparing(sorter()))
                .toList();
        for (ArchaeologyJournalEntry child : children) {
            addRow(child, depth, true, path, List.of(this.selectedCategory));
            if (this.expandedRoots.contains(child.id())
                    && !this.catalogDefinitions.get(child.id()).childTables().isEmpty()) {
                List<ResourceLocation> nextPath = new ArrayList<>(path);
                nextPath.add(child.id());
                flattenChildren(child.id(), depth + 1, List.copyOf(nextPath), new HashSet<>(branch));
            }
        }
    }

    private void addRow(ArchaeologyJournalEntry view, int depth, boolean child,
                        List<ResourceLocation> parentPath, List<ResourceLocation> categoryIds) {
        this.tableViews.add(view);
        boolean hasChildren = hasChildTables(view.id());
        this.rowMetadata.add(new RowMeta(depth, child, hasChildren, this.expandedRoots.contains(view.id()),
                parentPath, categoryIds));
    }

    private boolean hasChildTables(ResourceLocation tableId) {
        TableDefinition table = this.catalogDefinitions.get(tableId);
        return table != null && !table.childTables().isEmpty();
    }

    // —— 名称缓存与排序 ——

    // 目录定义、客户端语言与服务端补充表名各自推进版本。
    private void syncNameCaches() {
        Language language = Language.getInstance();
        long overrides = ClientLootTableLanguageStore.revision();
        if (this.nameCacheCatalog == this.catalogDefinitions && this.nameCacheLanguage == language
                && this.nameOverrideRevision == overrides) {
            return;
        }
        this.nameCacheCatalog = this.catalogDefinitions;
        this.nameCacheLanguage = language;
        this.nameOverrideRevision = overrides;
        this.displayNameTexts.clear();
        this.lowercaseTableNames.clear();
        this.lowercaseTableItems.clear();
    }

    // 目录显示名文本（原样大小写，供排序比较）
    private String displayNameText(ResourceLocation tableId) {
        syncNameCaches();
        return this.displayNameTexts.computeIfAbsent(tableId, id -> {
            ArchaeologyJournalEntry view = this.allViews.get(id);
            return view != null ? view.displayName().getString() : id.getPath();
        });
    }

    // 目录显示名的小写文本（供表级搜索匹配）
    private String lowercaseTableName(ResourceLocation tableId) {
        syncNameCaches();
        return this.lowercaseTableNames.computeIfAbsent(tableId,
                id -> displayNameText(id).toLowerCase(Locale.ROOT));
    }

    // 表内物品的小写匹配文本（供物品级搜索匹配）；只在物品级搜索时才构建，避免普通搜索预建整份物品文本
    private List<JournalSearchQuery.LowercaseItem> lowercaseTableItems(ResourceLocation tableId) {
        syncNameCaches();
        return this.lowercaseTableItems.computeIfAbsent(tableId, id -> {
            List<ItemDefinition> definitions = directItemsByTable.get(id);
            List<JournalSearchQuery.LowercaseItem> items = new ArrayList<>(definitions.size());
            for (ItemDefinition item : definitions) {
                items.add(JournalSearchQuery.LowercaseItem.of(item.id(), item.displayName().getString()));
            }
            return List.copyOf(items);
        });
    }

    // 表级搜索匹配：锁定表只按完整 id 命中；物品级模式才构建物品文本缓存
    private boolean matchesCurrentSearch(ArchaeologyJournalEntry view) {
        if (!view.unlocked()) {
            return this.currentSearch.matchesLockedTableId(view.id());
        }
        if (this.currentSearch.isEmpty()) {
            return true;
        }
        if (this.currentSearch.mode() == JournalSearchQuery.Mode.ITEM_NAME) {
            return this.currentSearch.matchesAnyItem(lowercaseTableItems(view.id()));
        }
        return this.currentSearch.matchesTableLowercase(view.id(), lowercaseTableName(view.id()), view.type());
    }

    // 排序比较器：名称文本走缓存，避免每次比较都做一次翻译查询
    private Comparator<ArchaeologyJournalEntry> sorter() {
        return CatalogSorter.getComparator(this.currentSortOrder, this.sortDescending,
                view -> displayNameText(view.id()));
    }

    private Comparator<ArchaeologyJournalEntry> rootComparator() {
        Comparator<ArchaeologyJournalEntry> base = sorter();
        if (this.currentSortOrder != CatalogSorter.SortOrder.FAVORITE) return base;
        Set<ResourceLocation> favoriteAncestors = new HashSet<>();
        ArrayDeque<ResourceLocation> pending = new ArrayDeque<>();
        allViews.values().stream().filter(ArchaeologyJournalEntry::favorite).map(ArchaeologyJournalEntry::id)
                .forEach(pending::addLast);
        while (!pending.isEmpty()) {
            ResourceLocation id = pending.removeFirst();
            if (favoriteAncestors.add(id)) pending.addAll(parentIdsByChild.getOrDefault(id, List.of()));
        }
        return Comparator.comparing((ArchaeologyJournalEntry view) -> !favoriteAncestors.contains(view.id()))
                .thenComparing(base);
    }

    private void collectDescendants(ResourceLocation id, Set<ResourceLocation> output) {
        if (!output.add(id)) return;
        TableDefinition table = this.catalogDefinitions.get(id);
        if (table != null) table.childTables().forEach(child -> collectDescendants(child, output));
    }

    private void rebuildRelationshipIndexes() {
        this.parentIdsByChild.clear();
        Map<ResourceLocation, LinkedHashSet<ResourceLocation>> parents = new LinkedHashMap<>();
        for (TableDefinition parent : this.catalogDefinitions.values()) {
            for (ResourceLocation childId : parent.childTables()) {
                if (this.catalogDefinitions.containsKey(childId)) {
                    parents.computeIfAbsent(childId, ignored -> new LinkedHashSet<>()).add(parent.id());
                }
            }
        }
        parents.forEach((childId, parentIds) ->
                this.parentIdsByChild.put(childId, List.copyOf(parentIds)));

        categoryIdsByTable.clear();
        membersByCategory.clear();
        structure.rootCategories().forEach((root, category) ->
                collectDescendants(root, membersByCategory.computeIfAbsent(category, ignored -> new LinkedHashSet<>())));
        membersByCategory.forEach((category, members) -> {
            members.retainAll(catalogDefinitions.keySet());
            for (ResourceLocation table : members) {
                categoryIdsByTable.computeIfAbsent(table, ignored -> new LinkedHashSet<>()).add(category);
            }
        });
    }

    // 从所有顶层入口同时逐层访问，直接收录的表优先保留自己的入口。
    private Map<ResourceLocation, List<ResourceLocation>> firstPathsByTable() {
        if (this.firstPathsByTableCache == null) {
            Map<ResourceLocation, List<ResourceLocation>> result = new LinkedHashMap<>();
            List<ResourceLocation> roots = this.structure.rootCategories().keySet().stream().sorted().toList();
            roots.forEach(root -> result.put(root, List.of()));
            ArrayDeque<ResourceLocation> pending = new ArrayDeque<>(roots);
            while (!pending.isEmpty()) {
                ResourceLocation parent = pending.removeFirst();
                TableDefinition definition = this.catalogDefinitions.get(parent);
                if (definition == null || definition.childTables().isEmpty()) continue;
                List<ResourceLocation> parentPath = new ArrayList<>(result.get(parent));
                parentPath.add(parent);
                List<ResourceLocation> path = List.copyOf(parentPath);
                for (ResourceLocation child : definition.childTables()) {
                    if (result.putIfAbsent(child, path) == null) pending.addLast(child);
                }
            }
            this.firstPathsByTableCache = Map.copyOf(result);
        }
        return this.firstPathsByTableCache;
    }

    public List<CatalogEntryData> buildCatalogEntries() {
        List<CatalogEntryData> result = new ArrayList<>();
        for (int index = 0; index < this.tableViews.size(); index++) {
            ArchaeologyJournalEntry view = this.tableViews.get(index);
            RowMeta meta = this.rowMetadata.get(index);
            result.add(new CatalogEntryData(view.id(), view.displayName(), view.unlocked(), view.favorite(),
                    meta.depth(), meta.child(), meta.hasChildren(), meta.expanded(),
                    meta.parentPath(), meta.categoryIds()));
        }
        return result;
    }

    /** 右页只投影当前表的场景定义及其直接子表的发现进度。 */
    public @Nullable BuildGridResult buildGridItems() {
        ResourceLocation table = selectedTableId();
        if (table == null) {
            gridDefinition = null;
            gridCacheKey = null;
            gridCacheResult = null;
            return null;
        }
        TableDefinition definition = visibleDefinitions.get(table);
        var progress = state.getTable(table);
        var childProgress = definition.childTableProbabilities().stream()
                .map(child -> state.getTable(child.tableId())).toList();
        GridCacheKey key = new GridCacheKey(progress, childProgress, lastNameRevision, lastLanguage, currentSearch.rawQuery());
        if (definition == gridDefinition && key.equals(gridCacheKey)) return gridCacheResult;

        gridCacheResult = rebuildGridItems(definition, progress);
        gridCacheKey = key;
        gridDefinition = definition;
        return gridCacheResult;
    }

    private BuildGridResult rebuildGridItems(TableDefinition definition, @Nullable ArchaeologyJournalState.TableProgress stored) {
        ResourceLocation table = definition.id();
        var progress = ArchaeologyJournalClientState.projectProgress(table, stored);
        List<ItemGridPanel.GridItem> gridItems = new ArrayList<>();
        Set<ResourceLocation> luckAffectedChildren = new HashSet<>();
        for (ItemDefinition item : definition.items()) {
            List<LootAcquisitionPath> direct = new ArrayList<>();
            for (LootAcquisitionPath path : item.acquisitionPaths()) {
                if (path.sourceChildTable() == null) direct.add(path);
                else if (path.luckAffected()) luckAffectedChildren.add(path.sourceChildTable());
            }
            if (item.acquisitionPaths().isEmpty() || !direct.isEmpty()) {
                gridItems.add(buildDirectGridItem(item, List.copyOf(direct), progress, definition.simulationCount()));
            }
        }
        gridItems.sort(Comparator.comparingDouble(item -> -gridItemSortKey(item)));

        List<ItemGridPanel.ChildTableEntry> childEntries = new ArrayList<>();
        for (ChildTableProbability child : definition.childTableProbabilities()) {
            if (!catalogDefinitions.containsKey(child.tableId())) continue;
            List<ItemStack> preview = childPreview(child.tableId());
            childEntries.add(new ItemGridPanel.ChildTableEntry(child.tableId(),
                    ClientLootTableLanguageStore.displayName(child.tableId()), !preview.isEmpty(),
                    child.probability(), child.conditions(), preview, luckAffectedChildren.contains(child.tableId()),
                    definition.simulationCount()));
        }
        List<ItemDefinition> required = completionItemsCache.computeIfAbsent(table,
                id -> CatalogQueryIndex.completionItems(catalogDefinitions, id));
        int parsedCount = progress == null ? 0 : (int) required.stream()
                .filter(item -> progress.isItemUnlocked(item.signature())).count();
        return new BuildGridResult(table, List.copyOf(gridItems), List.copyOf(childEntries),
                buildIntroItems(table, stored, progress), parsedCount, required.size());
    }

    private List<ItemDefinition> subtreeItems(ResourceLocation tableId) {
        return subtreeItemsCache.computeIfAbsent(tableId, id -> CatalogQueryIndex.subtreeItems(visibleDefinitions, id));
    }

    private List<ItemStack> childPreview(ResourceLocation table) {
        var stored = state.getTable(table);
        if (stored == null) return List.of();
        var progress = ArchaeologyJournalClientState.projectProgress(table, stored);
        List<ItemStack> preview = new ArrayList<>(CHILD_PREVIEW_SIZE);
        List<ItemDefinition> definitions = subtreeItems(table);
        for (ItemDefinition item : definitions) {
            if (progress.isItemUnlocked(item.signature())) {
                preview.add(DetailOverlayPanel.IntroItem.previewStack(item.id(), item.signature()));
                if (preview.size() == CHILD_PREVIEW_SIZE) return List.copyOf(preview);
            }
        }
        for (var signature : historicalItems(stored, definitions)) {
            preview.add(DetailOverlayPanel.IntroItem.previewStack(signature.itemId(), signature));
            if (preview.size() == CHILD_PREVIEW_SIZE) break;
        }
        return List.copyOf(preview);
    }

    // Intro 按需映射当前表及全部后代物品，避免在每个树节点重复缓存完整子树视图。
    private List<DetailOverlayPanel.IntroItem> buildIntroItems(ResourceLocation tableId,
            @Nullable ArchaeologyJournalState.TableProgress storedProgress,
            @Nullable ArchaeologyJournalState.TableProgress progress) {
        List<ItemDefinition> definitions = subtreeItems(tableId);
        List<DetailOverlayPanel.IntroItem> result = new ArrayList<>(definitions.size());
        for (ItemDefinition definition : definitions) {
            ArchaeologyJournalState.ItemProgress itemProgress = progress != null
                    ? progress.getItemProgress(definition.signature()) : null;
            boolean unlocked = itemProgress != null && itemProgress.isUnlocked();
            int count = unlocked ? itemProgress.getCount() : 0;
            boolean highlighted = this.currentSearch.mode() != JournalSearchQuery.Mode.ITEM_NAME
                    || this.currentSearch.matchesItem(definition.id(), definition.displayName().getString());
            result.add(new DetailOverlayPanel.IntroItem(
                    definition.id(), definition.displayName(), definition.signature(), unlocked, count, highlighted));
        }
        if (storedProgress != null) {
            for (LootResultSignature signature : historicalItems(storedProgress, definitions)) {
                ResourceLocation itemId = signature.itemId();
                Component name = BuiltInRegistries.ITEM.get(itemId).getDescription();
                Component history = Component.translatable("screen.unsuspiciousblock.archaeology_journal.item_history", name);
                boolean highlighted = currentSearch.matchesItem(itemId, name.getString());
                result.add(new DetailOverlayPanel.IntroItem(itemId, history, signature, true,
                        storedProgress.getItemCount(signature), highlighted));
            }
        }
        return List.copyOf(result);
    }

    private static Iterable<LootResultSignature> historicalItems(ArchaeologyJournalState.TableProgress stored,
                                                                List<ItemDefinition> definitions) {
        Set<LootResultSignature> current = new HashSet<>();
        definitions.forEach(item -> current.add(item.signature()));
        return () -> stored.getItemSignatures().stream()
                .filter(signature -> signature.type() == LootResultSignature.SignatureType.PLAIN
                        && !current.contains(signature) && stored.isItemUnlocked(signature))
                .iterator();
    }

    private ItemGridPanel.GridItem buildDirectGridItem(ItemDefinition item, List<LootAcquisitionPath> directPaths,
            @Nullable ArchaeologyJournalState.TableProgress progress, int simulationCount) {
        boolean highlighted = currentSearch.mode() != JournalSearchQuery.Mode.ITEM_NAME
                || currentSearch.matchesItem(item.id(), item.displayName().getString());
        boolean unlocked = progress != null && progress.isItemUnlocked(item.signature());
        int count = progress == null ? 0 : progress.getItemCount(item.signature());
        return new ItemGridPanel.GridItem(item.id(), item.displayName(), item.tooltipHint(),
                item.probability(), unlocked, count, item.signature(), highlighted,
                directPaths, item.injected(), item.uncertaintyLevel(),
                DeclaredChance.fromPaths(directPaths), simulationCount, item.observedFunctions());
    }

    // 排序按服务端派生的当前输入值：未知与「需要条件」排到末尾，零命中排在 0%（不可达）之前。
    private static double gridItemSortKey(ItemGridPanel.GridItem item) {
        Probability probability = item.probability();
        if (probability == null || probability.isUnknown() || probability instanceof Probability.NeedsCondition) {
            return -1.0;
        }
        return probability.upperBound();
    }

    private int resolveSelectedIndex(@Nullable ResourceLocation selectedId, List<ResourceLocation> selectedPath) {
        if (this.tableViews.isEmpty()) return -1;
        int current = findTableIndex(selectedId, selectedPath);
        if (current >= 0) return current;
        if (this.selectedCategory != null && this.currentSearch.isEmpty()) {
            int categoryRemembered = findTableIndex(
                    JournalUiPreferencesStore.getCategorySelection(this.selectedCategory), List.of());
            if (categoryRemembered >= 0) return categoryRemembered;
        }
        for (int index = 0; index < this.tableViews.size(); index++) {
            if (this.tableViews.get(index).unlocked()) return index;
        }
        return -1;
    }

    private int findTableIndex(@Nullable ResourceLocation id, List<ResourceLocation> parentPath) {
        if (id == null) return -1;
        for (int index = 0; index < this.tableViews.size(); index++) {
            if (this.tableViews.get(index).id().equals(id)
                    && this.rowMetadata.get(index).parentPath().equals(parentPath)) return index;
        }
        return findNavigationRow(id);
    }

    private record RowMeta(int depth, boolean child, boolean hasChildren, boolean expanded,
                           List<ResourceLocation> parentPath, List<ResourceLocation> categoryIds) {
    }

    private record GridCacheKey(@Nullable ArchaeologyJournalState.TableProgress progress,
                                List<ArchaeologyJournalState.TableProgress> childProgress,
                                long nameRevision, Language language, String search) {
    }

    public record BuildGridResult(ResourceLocation tableId, List<ItemGridPanel.GridItem> gridItems,
                                  List<ItemGridPanel.ChildTableEntry> childTables,
                                  List<DetailOverlayPanel.IntroItem> introItems,
                                  int parsedCount, int totalCount) {
    }
}
