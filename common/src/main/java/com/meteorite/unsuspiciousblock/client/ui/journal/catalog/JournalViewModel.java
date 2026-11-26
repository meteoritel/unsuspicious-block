package com.meteorite.unsuspiciousblock.client.ui.journal.catalog;

import java.util.Objects;
import java.util.stream.Collectors;

import net.minecraft.locale.Language;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.DeclaredChance;

import com.meteorite.unsuspiciousblock.client.ui.entry.ArchaeologyEntryItem;
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
import com.meteorite.unsuspiciousblock.core.loottable.analysis.LootConditionHandler;
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
    private final Map<ResourceLocation, TableDefinition> catalogDefinitions = new LinkedHashMap<>();
    private final Map<ResourceLocation, ArchaeologyJournalEntry> allViews = new LinkedHashMap<>();
    private final List<ArchaeologyJournalEntry> tableViews = new ArrayList<>();
    private final List<RowMeta> rowMetadata = new ArrayList<>();
    private final List<CategoryEntryData> categoryViews = new ArrayList<>();
    private final Map<ResourceLocation, List<ResourceLocation>> parentIdsByChild = new LinkedHashMap<>();
    private final Map<ResourceLocation, Set<ResourceLocation>> categoryIdsByTable = new LinkedHashMap<>();
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
    private long lastCatalogRevision;
    private long lastNameRevision = -1;
    private Language lastLanguage;
    private long lastStateRevision;
    private boolean lastReadingReady;
    private long lastLogRevision;

    // —— 目录派生数据缓存（键均为 loadedCatalogRevision，即当前 catalogDefinitions 的版本）——
    // 已加载目录定义的版本：reloadCatalog 时更新，作为所有目录派生缓存的失效点
    private long loadedCatalogRevision = -1L;
    // 关系索引（子表 -> 父表、表 -> 分类）只在目录定义或分类结构变化时重建
    private long relationshipIndexRevision = Long.MIN_VALUE;
    // 从分类顶层出发的最短父路径，顶层入口的路径为空。
    private Map<ResourceLocation, List<ResourceLocation>> firstPathsByTableCache = Map.of();
    private long firstPathsRevision = Long.MIN_VALUE;
    // 表子树物品闭包；值来自 CatalogQueryIndex.subtreeItems，本身即为不可变列表
    private final Map<ResourceLocation, List<ItemDefinition>> subtreeItemsCache = new LinkedHashMap<>();
    private long subtreeItemsRevision = Long.MIN_VALUE;
    // 网格装配结果缓存：输入不变时直接返回同一份不可变结果
    @Nullable
    private GridCacheKey gridCacheKey;
    @Nullable
    private BuildGridResult gridCacheResult;

    // —— 名称文本缓存 ——
    // 输入 = 目录定义 + 客户端语言（显示名的 getString() 是一次翻译查询）；
    // 版本键 = (loadedCatalogRevision, 客户端语言码)，任一变化即整体失效
    private final Map<ResourceLocation, String> displayNameTexts = new LinkedHashMap<>();
    private final Map<ResourceLocation, String> lowercaseTableNames = new LinkedHashMap<>();
    private final Map<ResourceLocation, List<JournalSearchQuery.LowercaseItem>> lowercaseTableItems =
            new LinkedHashMap<>();
    private long nameOverrideRevision = Long.MIN_VALUE;
    private long nameCacheRevision = Long.MIN_VALUE;
    private Language nameCacheLanguage;

    public JournalViewModel() {
        this.state = ArchaeologyJournalClientState.getState();
    }

    public void initRevisions() {
        this.lastConnectionEpoch = JournalClientConnection.epoch();
        this.lastLogRevision = JournalClientConnection.log().sortRevision();
        this.lastCatalogRevision = ArchaeologyJournalClientState.getCatalogRevision();
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

    // 从当前父表的子表入口跳转时，保留当前目录路径，先展开父表再查找其下的子表行。
    public boolean prepareNavigationToChild(ResourceLocation parentId, ResourceLocation childId) {
        TableDefinition parent = this.catalogDefinitions.get(parentId);
        if (parent == null || !parent.childTables().contains(childId)) {
            return false;
        }
        if (this.currentSearch.isEmpty() && parentId.equals(selectedTableId())) {
            this.expandedRoots.addAll(this.rowMetadata.get(this.selectedIndex).parentPath());
        } else if (!prepareNavigationTo(parentId)) {
            return false;
        }
        this.expandedRoots.add(parentId);
        return true;
    }

    // 指定父表时优先选中直接子表行，普通定位优先选中顶层入口。
    public int findNavigationRow(ResourceLocation tableId, @Nullable ResourceLocation parentTableId) {
        int fallback = -1;
        for (int index = 0; index < this.tableViews.size(); index++) {
            if (!this.tableViews.get(index).id().equals(tableId)) {
                continue;
            }
            List<ResourceLocation> path = this.rowMetadata.get(index).parentPath();
            if (fallback < 0 || path.isEmpty()) {
                fallback = index;
            }
            ResourceLocation directParent = path.isEmpty() ? null : path.getLast();
            if (Objects.equals(parentTableId, directParent)) {
                return index;
            }
        }
        return fallback;
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

    public void reloadCatalog() {
        this.catalogDefinitions.clear();
        this.catalogDefinitions.putAll(ArchaeologyJournalClientState.getCatalog());
        this.structure = ArchaeologyJournalClientState.getCatalogStructure();
        this.loadedCatalogRevision = ArchaeologyJournalClientState.getCatalogRevision();
    }

    /** 需要从根 Screen 提交的最小刷新范围。 */
    public enum Refresh { NONE, ORDER, CONTENT }

    public Refresh refreshIfNeeded() {
        long epoch = JournalClientConnection.epoch();
        long logRevision = JournalClientConnection.log().sortRevision();
        boolean connectionChanged = epoch != this.lastConnectionEpoch;
        this.lastConnectionEpoch = epoch;
        long catalogRevision = ArchaeologyJournalClientState.getCatalogRevision();
        long stateRevision = ArchaeologyJournalClientState.getStateRevision();
        boolean readingReady = ArchaeologyJournalClientState.isReadingReady();
        boolean readinessChanged = readingReady != lastReadingReady;
        lastReadingReady = readingReady;
        long names = ClientLootTableLanguageStore.revision();
        Language language = Language.getInstance();
        boolean namesChanged = names != this.lastNameRevision || language != lastLanguage;
        this.lastNameRevision = names;
        this.lastLanguage = language;
        boolean logChanged = logRevision != this.lastLogRevision;
        this.lastLogRevision = logRevision;
        boolean progressChanged = stateRevision != this.lastStateRevision;
        boolean catalogChanged = catalogRevision != this.lastCatalogRevision;
        if (connectionChanged || progressChanged) {
            this.lastStateRevision = stateRevision;
            this.state = ArchaeologyJournalClientState.getState();
        }
        if (connectionChanged || catalogChanged) {
            this.lastCatalogRevision = catalogRevision;
            reloadCatalog();
        }
        if (connectionChanged || readinessChanged || namesChanged || catalogChanged || progressChanged) {
            return Refresh.CONTENT;
        }
        if (logChanged && this.currentSortOrder == CatalogSorter.SortOrder.UPDATE_TIME) return Refresh.ORDER;
        return Refresh.NONE;
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
            ArchaeologyJournalEntry view = ArchaeologyJournalEntry.of(id, entry.getValue(), this.state.getTable(id));
            this.allViews.put(id, view);
            if (view.unlocked()) unlocked++;
        }
        this.totalTableCount = this.allViews.size();
        this.globalUnlockedTableCount = unlocked;
        rebuildRelationshipIndexes();
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
            Set<ResourceLocation> members = this.categoryIdsByTable.entrySet().stream()
                    .filter(entry -> entry.getValue().contains(definition.id()))
                    .map(Map.Entry::getKey)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
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
        if (this.nameCacheRevision == this.loadedCatalogRevision && this.nameCacheLanguage == language
                && this.nameOverrideRevision == overrides) {
            return;
        }
        this.nameCacheRevision = this.loadedCatalogRevision;
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
            ArchaeologyJournalEntry view = this.allViews.get(id);
            if (view == null) return List.of();
            List<JournalSearchQuery.LowercaseItem> items = new ArrayList<>(view.items().size());
            for (ArchaeologyEntryItem item : view.items()) {
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
            return this.currentSearch.matchesTableByItemLowercase(view.id(),
                    lowercaseTableName(view.id()), view.type(), lowercaseTableItems(view.id()));
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
        return Comparator.comparing((ArchaeologyJournalEntry view) -> !subtreeContainsFavorite(view.id()))
                .thenComparing(base);
    }

    private boolean subtreeContainsFavorite(ResourceLocation id) {
        Set<ResourceLocation> members = new HashSet<>();
        collectDescendants(id, members);
        return members.stream().map(this.allViews::get).anyMatch(view -> view != null && view.favorite());
    }

    private void collectDescendants(ResourceLocation id, Set<ResourceLocation> output) {
        if (!output.add(id)) return;
        TableDefinition table = this.catalogDefinitions.get(id);
        if (table != null) table.childTables().forEach(child -> collectDescendants(child, output));
    }

    // 关系索引只由目录定义与分类结构派生：目录 revision 未变时直接复用，逐字符/逐次选择不再重建
    private void rebuildRelationshipIndexes() {
        if (this.relationshipIndexRevision == this.loadedCatalogRevision) {
            return;
        }
        this.relationshipIndexRevision = this.loadedCatalogRevision;
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

        this.categoryIdsByTable.clear();
        this.structure.rootCategories().forEach((root, category) -> {
            Set<ResourceLocation> members = new HashSet<>();
            collectDescendants(root, members);
            members.stream().filter(this.allViews::containsKey).forEach(id ->
                    this.categoryIdsByTable.computeIfAbsent(id, ignored -> new LinkedHashSet<>()).add(category));
        });
    }

    // 从所有顶层入口同时逐层访问，直接收录的表优先保留自己的入口。
    private Map<ResourceLocation, List<ResourceLocation>> firstPathsByTable() {
        if (this.firstPathsRevision != this.loadedCatalogRevision) {
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
            this.firstPathsRevision = this.loadedCatalogRevision;
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

    @Nullable
    public BuildGridResult buildGridItems() {
        ArchaeologyJournalEntry selected = selectedTable();
        if (selected == null) {
            this.gridCacheKey = null;
            this.gridCacheResult = null;
            return null;
        }
        // 缓存键覆盖格子装配的全部输入：表 id、目录定义版本、进度/日志版本、搜索（决定高亮）；
        // 命中即返回上次装配好的不可变结果，方向键在同一表上来回移动不再重建
        GridCacheKey key = new GridCacheKey(selected.id(), this.loadedCatalogRevision, this.lastStateRevision,
                this.lastLogRevision, this.currentSearch.mode(), this.currentSearch.rawQuery());
        if (key.equals(this.gridCacheKey) && this.gridCacheResult != null) {
            return this.gridCacheResult;
        }
        BuildGridResult result = rebuildGridItems(selected);
        this.gridCacheKey = key;
        this.gridCacheResult = result;
        return result;
    }

    // 装配网格数据；返回的列表一律不可变，可安全交给缓存与面板复用
    private BuildGridResult rebuildGridItems(ArchaeologyJournalEntry selected) {
        TableDefinition selectedDefinition = this.catalogDefinitions.get(selected.id());
        int simulationCount = selectedDefinition != null ? selectedDefinition.simulationCount() : 0;
        List<ItemGridPanel.GridItem> gridItems = new ArrayList<>();
        for (ArchaeologyEntryItem item : selected.items()) {
            ItemGridPanel.GridItem gridItem = buildDirectGridItem(item, simulationCount);
            if (gridItem != null) {
                gridItems.add(gridItem);
            }
        }
        gridItems.sort(Comparator.comparing(ItemGridPanel.GridItem::primarySourceChildTable,
                        Comparator.nullsFirst(Comparator.comparing(String::valueOf)))
                .thenComparingDouble(value -> -gridItemSortKey(value)));
        List<ItemGridPanel.ChildTableEntry> childEntries = new ArrayList<>();
        if (selectedDefinition != null) {
            for (ChildTableProbability childProbability : selectedDefinition.childTableProbabilities()) {
                ArchaeologyJournalEntry child = this.allViews.get(childProbability.tableId());
                if (child == null) continue;
                List<DetailOverlayPanel.IntroItem> discoveredItems = buildIntroItems(child.id()).stream()
                        .filter(DetailOverlayPanel.IntroItem::unlocked).toList();
                List<ItemStack> previewItems = discoveredItems.stream().limit(3)
                        .map(DetailOverlayPanel.IntroItem::stack).toList();
                childEntries.add(new ItemGridPanel.ChildTableEntry(
                        child.id(), child.displayName(), !discoveredItems.isEmpty(),
                        // 子表入口与物品同口径：读服务端派生的当前输入状态，不再取跨场景最大值
                        childProbability.probability(),
                        // 入口条件由服务端派生（路径共同条件 + 注入边门槛）：注入边不在任何 JSON 里，
                        // 客户端从物品路径本地重推必然漏掉它，泥地打捞入口就曾因此没有原因可讲
                        childProbability.conditions(), previewItems,
                        selectedDefinition.items().stream()
                                .flatMap(item -> item.acquisitionPaths().stream())
                                .anyMatch(path -> child.id().equals(path.sourceChildTable()) && path.luckAffected()),
                        selectedDefinition.simulationCount()));
            }
        }
        List<DetailOverlayPanel.IntroItem> introItems = buildIntroItems(selected.id());
        List<ItemDefinition> required = CatalogQueryIndex.completionItems(this.catalogDefinitions, selected.id());
        ArchaeologyJournalState.TableProgress progress =
                ArchaeologyJournalClientState.projectProgress(selected.id(), this.state.getTable(selected.id()));
        int parsedCount = progress == null ? 0 : (int) required.stream()
                .filter(item -> progress.isItemUnlocked(item.signature())).count();
        return new BuildGridResult(selected.id(), List.copyOf(gridItems), List.copyOf(childEntries), introItems,
                parsedCount, required.size());
    }

    // 表子树物品闭包：CatalogQueryIndex.subtreeItems 是一次整棵子树的 DFS，选中表与每个直接子表各调一次；
    // 按目录 revision 缓存，目录未变时复用同一份不可变列表
    private List<ItemDefinition> subtreeItems(ResourceLocation tableId) {
        if (this.subtreeItemsRevision != this.loadedCatalogRevision) {
            this.subtreeItemsCache.clear();
            this.subtreeItemsRevision = this.loadedCatalogRevision;
        }
        return this.subtreeItemsCache.computeIfAbsent(tableId,
                id -> CatalogQueryIndex.subtreeItems(this.catalogDefinitions, id));
    }

    // Intro 按需映射当前表及全部后代物品，避免在每个树节点重复缓存完整子树视图。
    private List<DetailOverlayPanel.IntroItem> buildIntroItems(ResourceLocation tableId) {
        List<ItemDefinition> definitions = subtreeItems(tableId);
        ArchaeologyJournalState.TableProgress storedProgress = this.state.getTable(tableId);
        ArchaeologyJournalState.TableProgress progress = ArchaeologyJournalClientState.projectProgress(tableId, storedProgress);
        List<DetailOverlayPanel.IntroItem> result = new ArrayList<>(definitions.size());
        Set<LootResultSignature> currentSignatures = new HashSet<>();
        for (ItemDefinition definition : definitions) {
            currentSignatures.add(definition.signature());
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
            for (LootResultSignature signature : storedProgress.getItemSignatures()) {
                if (signature.type() != LootResultSignature.SignatureType.PLAIN || currentSignatures.contains(signature)
                        || !storedProgress.isItemUnlocked(signature)) continue;
                ResourceLocation itemId = signature.itemId();
                Component name = BuiltInRegistries.ITEM.get(itemId).getDescription();
                Component history = Component.translatable("screen.unsuspiciousblock.archaeology_journal.item_history", name);
                boolean highlighted = this.currentSearch.matchesItem(itemId, name.getString());
                result.add(new DetailOverlayPanel.IntroItem(itemId, history, signature, true,
                        storedProgress.getItemCount(signature), highlighted));
            }
        }
        return List.copyOf(result);
    }

    @Nullable
    private ItemGridPanel.GridItem buildDirectGridItem(ArchaeologyEntryItem item, int simulationCount) {
        List<LootAcquisitionPath> directPaths = item.acquisitionPaths().stream()
                .filter(path -> path.sourceChildTable() == null)
                .toList();
        if (!item.acquisitionPaths().isEmpty() && directPaths.isEmpty()) {
            return null;
        }
        boolean highlighted = this.currentSearch.mode() != JournalSearchQuery.Mode.ITEM_NAME
                || this.currentSearch.matchesItem(item.id(), item.displayName().getString());
        // 等级来自条目自身（服务端同一份判定），不再在客户端另起规则、也不再比较 tooltip 文案
        LootConditionHandler.UncertaintyLevel level = item.uncertaintyLevel();
        // 概率直接读服务端派生的当前输入状态（可适用性 × 计算状态），客户端不再跨场景取最大值：
        // 取最大值等于把"你站在沼泽里"那个数当作你的处境展示（D1）。
        return new ItemGridPanel.GridItem(item.id(), item.displayName(), item.tooltipHint(),
                item.probability(), item.unlocked(), item.count(), item.signature(), highlighted,
                directPaths, item.injected(), level,
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
        return findNavigationRow(id, null);
    }

    private record RowMeta(int depth, boolean child, boolean hasChildren, boolean expanded,
                           List<ResourceLocation> parentPath, List<ResourceLocation> categoryIds) {
    }

    // 网格缓存键：表 id + 目录定义版本 + 进度/日志版本 + 搜索模式与原始输入（共同决定格子高亮）
    private record GridCacheKey(ResourceLocation tableId, long catalogRevision, long stateRevision,
                                long logRevision, JournalSearchQuery.Mode searchMode, String searchRawQuery) {
    }

    public record BuildGridResult(ResourceLocation tableId, List<ItemGridPanel.GridItem> gridItems,
                                  List<ItemGridPanel.ChildTableEntry> childTables,
                                  List<DetailOverlayPanel.IntroItem> introItems,
                                  int parsedCount, int totalCount) {
    }
}
