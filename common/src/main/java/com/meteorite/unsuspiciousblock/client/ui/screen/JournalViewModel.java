package com.meteorite.unsuspiciousblock.client.ui.screen;

import com.meteorite.unsuspiciousblock.loottable.catalog.DeclaredChance;

import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.client.ui.entry.ArchaeologyEntryItem;
import com.meteorite.unsuspiciousblock.client.ui.entry.ArchaeologyEntryLogRef;
import com.meteorite.unsuspiciousblock.client.ui.entry.ArchaeologyJournalEntry;
import com.meteorite.unsuspiciousblock.client.ui.panel.CatalogPanel;
import com.meteorite.unsuspiciousblock.client.ui.panel.DetailOverlayPanel;
import com.meteorite.unsuspiciousblock.client.ui.panel.ItemGridPanel;
import com.meteorite.unsuspiciousblock.client.ui.panel.RightPageContainer;
import com.meteorite.unsuspiciousblock.client.ui.support.ArchaeologyJournalClientState;
import com.meteorite.unsuspiciousblock.client.ui.support.CatalogSorter;
import com.meteorite.unsuspiciousblock.client.ui.support.JournalSearchQuery;
import com.meteorite.unsuspiciousblock.client.ui.support.LogGrouper;
import com.meteorite.unsuspiciousblock.journal.state.ArchaeologyJournalLogState;
import com.meteorite.unsuspiciousblock.journal.state.ArchaeologyJournalState;
import com.meteorite.unsuspiciousblock.loottable.analysis.LootConditionHandler;
import com.meteorite.unsuspiciousblock.loottable.catalog.CatalogQueryIndex;
import com.meteorite.unsuspiciousblock.loottable.catalog.Probability;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.CatalogCategoryDefinition;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.CatalogStructure;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.ChildTableProbability;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.ItemDefinition;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.LootAcquisitionPath;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.TableDefinition;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;

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

    private final ArchaeologyJournalState state;
    private ArchaeologyJournalLogState logState;
    private final Map<ResourceLocation, TableDefinition> catalogDefinitions = new LinkedHashMap<>();
    private final Map<ResourceLocation, ArchaeologyJournalEntry> allViews = new LinkedHashMap<>();
    private final List<ArchaeologyJournalEntry> tableViews = new ArrayList<>();
    private final List<RowMeta> rowMetadata = new ArrayList<>();
    private final List<CatalogPanel.CategoryEntryData> categoryViews = new ArrayList<>();
    private final Map<ResourceLocation, List<ResourceLocation>> parentIdsByChild = new LinkedHashMap<>();
    private final Map<ResourceLocation, Set<ResourceLocation>> categoryIdsByTable = new LinkedHashMap<>();
    private CatalogStructure structure = CatalogStructure.empty();
    private final Set<ResourceLocation> expandedRoots = new LinkedHashSet<>();
    private boolean categoryHome = true;
    @Nullable private ResourceLocation selectedCategory;
    private int selectedIndex = -1;
    private boolean selectionInitialized;
    private int totalTableCount;
    private int globalUnlockedTableCount;
    private JournalSearchQuery currentSearch = JournalSearchQuery.EMPTY;
    private CatalogSorter.SortOrder currentSortOrder = CatalogSorter.SortOrder.DEFAULT;
    private boolean sortDescending;
    private boolean hideLocked;
    private boolean logSortDescending = true;
    private LogGrouper.GroupMode currentGroupMode = LogGrouper.GroupMode.TIME;
    private long lastCatalogRevision;
    private long lastStateRevision;
    private long lastLogRevision;

    // —— 目录派生数据缓存（键均为 loadedCatalogRevision，即当前 catalogDefinitions 的版本）——
    // 已加载目录定义的版本：reloadCatalog 时更新，作为所有目录派生缓存的失效点
    private long loadedCatalogRevision = -1L;
    // 关系索引（子表 -> 父表、表 -> 分类）只在目录定义或分类结构变化时重建
    private long relationshipIndexRevision = Long.MIN_VALUE;
    // 目录内每张表的首条父路径；搜索与子表跳转共用
    private Map<ResourceLocation, List<ResourceLocation>> firstPathsByTableCache = Map.of();
    private long firstPathsRevision = Long.MIN_VALUE;
    // 表子树物品闭包；值来自 CatalogQueryIndex.subtreeItems，本身即为不可变列表
    private final Map<ResourceLocation, List<ItemDefinition>> subtreeItemsCache = new LinkedHashMap<>();
    private long subtreeItemsRevision = Long.MIN_VALUE;
    // 网格装配结果缓存：输入不变时直接返回同一份不可变结果
    @Nullable private GridCacheKey gridCacheKey;
    @Nullable private BuildGridResult gridCacheResult;

    // —— 名称文本缓存 ——
    // 输入 = 目录定义 + 客户端语言（显示名的 getString() 是一次翻译查询）；
    // 版本键 = (loadedCatalogRevision, 客户端语言码)，任一变化即整体失效
    private final Map<ResourceLocation, String> displayNameTexts = new LinkedHashMap<>();
    private final Map<ResourceLocation, String> lowercaseTableNames = new LinkedHashMap<>();
    private final Map<ResourceLocation, List<JournalSearchQuery.LowercaseItem>> lowercaseTableItems =
            new LinkedHashMap<>();
    private long nameCacheRevision = Long.MIN_VALUE;
    private String nameCacheLanguage = "";

    public JournalViewModel(ArchaeologyJournalState state) {
        this.state = state;
        this.logState = ArchaeologyJournalClientState.getLogState();
    }

    public void initRevisions() {
        this.lastCatalogRevision = ArchaeologyJournalClientState.getCatalogRevision();
        this.lastStateRevision = ArchaeologyJournalClientState.getStateRevision();
        this.lastLogRevision = ArchaeologyJournalClientState.getLogRevision();
    }

    public void setCurrentSearch(JournalSearchQuery search) {
        this.currentSearch = search;
        // 物品文本缓存只服务搜索匹配：离开搜索态即释放，避免大目录长期驻留整份物品小写名
        if (search.isEmpty()) {
            this.lowercaseTableItems.clear();
        }
    }
    public void setCurrentSortOrder(CatalogSorter.SortOrder order) { this.currentSortOrder = order; }
    public void setSortDescending(boolean descending) { this.sortDescending = descending; }
    public void setHideLocked(boolean hideLocked) { this.hideLocked = hideLocked; }
    public void setLogSortDescending(boolean descending) { this.logSortDescending = descending; }
    public void setCurrentGroupMode(LogGrouper.GroupMode mode) { this.currentGroupMode = mode; }
    public List<ArchaeologyJournalEntry> tableViews() { return this.tableViews; }
    public List<CatalogPanel.CategoryEntryData> categoryViews() { return this.categoryViews; }
    public int selectedIndex() { return this.selectedIndex; }
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
            ArchaeologyJournalClientState.rememberCategorySelection(
                    this.selectedCategory, selectedId);
        }
    }
    public boolean isCategoryHome() { return this.categoryHome && this.currentSearch.isEmpty(); }
    public boolean isCategoryHomeMode() { return this.categoryHome; }
    @Nullable public ResourceLocation selectedCategory() { return this.selectedCategory; }
    public Set<ResourceLocation> expandedRoots() { return Set.copyOf(this.expandedRoots); }

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

    // 为子表跳转准备目录状态：切换到可达分类并展开目标的全部祖先。
    public boolean prepareNavigationTo(ResourceLocation tableId) {
        if (!this.allViews.containsKey(tableId)) {
            return false;
        }
        Set<ResourceLocation> categories = this.categoryIdsByTable.getOrDefault(tableId, Set.of());
        if (categories.isEmpty()) {
            return false;
        }
        if (this.selectedCategory == null || !categories.contains(this.selectedCategory)) {
            this.selectedCategory = categories.stream().min(Comparator.comparing(ResourceLocation::toString)).orElse(null);
        }
        this.categoryHome = false;
        this.expandedRoots.addAll(firstPathsByTable().getOrDefault(tableId, List.of()));
        return true;
    }

    // 从当前父表的子表入口跳转时，保留当前目录路径，先展开父表再查找其下的子表行。
    public boolean prepareNavigationToChild(ResourceLocation parentId, ResourceLocation childId) {
        TableDefinition parent = this.catalogDefinitions.get(parentId);
        if (parent == null || !parent.childTables().contains(childId) || !prepareNavigationTo(childId)) {
            return false;
        }
        if (this.selectedIndex >= 0 && this.selectedIndex < this.rowMetadata.size()
                && this.tableViews.get(this.selectedIndex).id().equals(parentId)) {
            this.expandedRoots.addAll(this.rowMetadata.get(this.selectedIndex).parentPath());
        }
        this.expandedRoots.add(parentId);
        return true;
    }

    // 优先返回指定父表路径下的子表行，避免同一表同时作为分类根表时跳到错误位置。
    public int findNavigationRow(ResourceLocation tableId, @Nullable ResourceLocation parentTableId) {
        int fallback = -1;
        for (int index = 0; index < this.tableViews.size(); index++) {
            if (!this.tableViews.get(index).id().equals(tableId)) {
                continue;
            }
            if (fallback < 0) {
                fallback = index;
            }
            if (parentTableId != null && this.rowMetadata.get(index).parentPath().contains(parentTableId)) {
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

    public int totalTableCount() { return this.totalTableCount; }

    public int displayedUnlockedTableCount() {
        CatalogPanel.CategoryEntryData category = selectedCategoryView();
        return category != null ? category.unlocked() : this.globalUnlockedTableCount;
    }

    public int displayedTableCount() {
        CatalogPanel.CategoryEntryData category = selectedCategoryView();
        return category != null ? category.total() : this.totalTableCount;
    }

    @Nullable
    private CatalogPanel.CategoryEntryData selectedCategoryView() {
        if (this.categoryHome || this.selectedCategory == null) return null;
        return this.categoryViews.stream()
                .filter(category -> category.id().equals(this.selectedCategory))
                .findFirst().orElse(null);
    }
    public boolean isEmpty() { return this.tableViews.isEmpty(); }

    public void reloadCatalog() {
        this.catalogDefinitions.clear();
        this.catalogDefinitions.putAll(ArchaeologyJournalClientState.getCatalog());
        this.structure = ArchaeologyJournalClientState.getCatalogStructure();
        this.loadedCatalogRevision = ArchaeologyJournalClientState.getCatalogRevision();
    }

    public boolean refreshIfNeeded() {
        long catalogRevision = ArchaeologyJournalClientState.getCatalogRevision();
        long stateRevision = ArchaeologyJournalClientState.getStateRevision();
        long logRevision = ArchaeologyJournalClientState.getLogRevision();
        if (catalogRevision == this.lastCatalogRevision && stateRevision == this.lastStateRevision
                && logRevision == this.lastLogRevision) return false;
        if (stateRevision != this.lastStateRevision) {
            this.lastStateRevision = stateRevision;
            this.state.copyFrom(ArchaeologyJournalClientState.getState());
        }
        if (catalogRevision != this.lastCatalogRevision) {
            this.lastCatalogRevision = catalogRevision;
            reloadCatalog();
        }
        if (logRevision != this.lastLogRevision) {
            this.lastLogRevision = logRevision;
            this.logState = ArchaeologyJournalClientState.getLogState();
        }
        return true;
    }

    public void rebuildViewModels(@Nullable RightPageContainer rightPage, @Nullable CatalogPanel catalogPanel) {
        ResourceLocation selectedId = selectedTableId();
        this.allViews.clear();
        int unlocked = 0;
        for (Map.Entry<ResourceLocation, TableDefinition> entry : this.catalogDefinitions.entrySet()) {
            ResourceLocation id = entry.getKey();
            ArchaeologyJournalEntry view = ArchaeologyJournalEntry.of(id, entry.getValue(), this.state.getTable(id),
                    this.logState.getTable(id));
            this.allViews.put(id, view);
            if (view.unlocked()) unlocked++;
        }
        this.totalTableCount = this.allViews.size();
        this.globalUnlockedTableCount = unlocked;
        rebuildRelationshipIndexes();
        validateUnlockInvariant();
        rebuildCategories();
        this.expandedRoots.retainAll(this.catalogDefinitions.keySet());
        if (!this.categoryHome && this.currentSearch.isEmpty()
                && this.categoryViews.stream().noneMatch(category -> category.id().equals(this.selectedCategory))) {
            this.categoryHome = true;
            this.selectedCategory = null;
        }
        rebuildTableRows();

        ResourceLocation remembered = this.selectionInitialized ? null
                : ArchaeologyJournalClientState.getLastSelectedTableId();
        this.selectedIndex = resolveSelectedIndex(selectedId, remembered);
        if (!this.selectionInitialized && !this.tableViews.isEmpty()) this.selectionInitialized = true;

        if (catalogPanel != null) {
            if (isCategoryHome()) catalogPanel.setCategories(this.categoryViews);
            else catalogPanel.setEntries(buildCatalogEntries());
            catalogPanel.ensureIndexVisible(this.selectedIndex);
        }
        if (rightPage != null) {
            rightPage.getLogPanel().setSortDescending(this.logSortDescending);
            rightPage.getLogPanel().setGroupMode(this.currentGroupMode);
        }
    }

    private void rebuildCategories() {
        this.categoryViews.clear();
        for (CatalogCategoryDefinition definition : this.structure.categories()) {
            Set<ResourceLocation> members = this.categoryIdsByTable.entrySet().stream()
                    .filter(entry -> entry.getValue().contains(definition.id()))
                    .map(Map.Entry::getKey)
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
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
            this.categoryViews.add(new CatalogPanel.CategoryEntryData(
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
                .map(Map.Entry::getKey).map(this.allViews::get).filter(java.util.Objects::nonNull)
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
                .filter(java.util.Objects::nonNull)
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

    // 让名称缓存与当前"目录定义版本 + 客户端语言"对齐；不一致就整体失效重建。
    // 服务端补充译名不推进 catalogRevision，因此匹配文本最多滞后到下一次目录或语言变化（显示本身仍走实时 Component）。
    private void syncNameCaches() {
        String language = Minecraft.getInstance().getLanguageManager().getSelected();
        if (this.nameCacheRevision == this.loadedCatalogRevision && this.nameCacheLanguage.equals(language)) {
            return;
        }
        this.nameCacheRevision = this.loadedCatalogRevision;
        this.nameCacheLanguage = language;
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

    // 目录内每张表的首条父路径（搜索与子表跳转都要用），按目录 revision 缓存一次 DFS
    private Map<ResourceLocation, List<ResourceLocation>> firstPathsByTable() {
        if (this.firstPathsRevision != this.loadedCatalogRevision) {
            Map<ResourceLocation, List<ResourceLocation>> result = new LinkedHashMap<>();
            this.structure.rootCategories().keySet().stream().sorted(Comparator.comparing(ResourceLocation::toString))
                    .forEach(root -> collectFirstPaths(root, List.of(), result, new HashSet<>()));
            this.firstPathsByTableCache = Map.copyOf(result);
            this.firstPathsRevision = this.loadedCatalogRevision;
        }
        return this.firstPathsByTableCache;
    }

    private void collectFirstPaths(ResourceLocation current, List<ResourceLocation> parentPath,
                                   Map<ResourceLocation, List<ResourceLocation>> output,
                                   Set<ResourceLocation> branch) {
        if (!branch.add(current)) return;
        if (!parentPath.isEmpty()) output.putIfAbsent(current, parentPath);
        TableDefinition table = this.catalogDefinitions.get(current);
        if (table == null) return;
        List<ResourceLocation> next = new ArrayList<>(parentPath);
        next.add(current);
        for (ResourceLocation child : table.childTables()) {
            collectFirstPaths(child, List.copyOf(next), output, new HashSet<>(branch));
        }
    }

    private void validateUnlockInvariant() {
        for (TableDefinition parent : this.catalogDefinitions.values()) {
            ArchaeologyJournalEntry parentView = this.allViews.get(parent.id());
            if (parentView == null || parentView.unlocked()) continue;
            for (ResourceLocation childId : parent.childTables()) {
                if (this.structure.rootCategories().containsKey(childId)) continue;
                ArchaeologyJournalEntry child = this.allViews.get(childId);
                if (child != null && child.unlocked()) {
                    Constants.LOG.warn("考古笔记状态异常：子表 {} 已解锁，但父表 {} 未解锁", childId, parent.id());
                }
            }
        }
    }

    public List<CatalogPanel.CatalogEntryData> buildCatalogEntries() {
        List<CatalogPanel.CatalogEntryData> result = new ArrayList<>();
        for (int index = 0; index < this.tableViews.size(); index++) {
            ArchaeologyJournalEntry view = this.tableViews.get(index);
            RowMeta meta = this.rowMetadata.get(index);
            result.add(new CatalogPanel.CatalogEntryData(view.id(), view.displayName(), view.unlocked(), view.favorite(),
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
                        childProbability.scenarioProbabilities(),
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
        int parsedCount = (int) introItems.stream().filter(DetailOverlayPanel.IntroItem::unlocked).count();
        return new BuildGridResult(selected.id(), List.copyOf(gridItems), List.copyOf(childEntries), introItems,
                parsedCount, introItems.size(), selected.logRef());
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
        ArchaeologyJournalState.TableProgress progress = this.state.getTable(tableId);
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
                directPaths, item.injected(), level, item.scenarioProbabilities(),
                DeclaredChance.fromPaths(directPaths), simulationCount);
    }

    // 排序按服务端派生的当前输入值：未知与「需要条件」排到末尾，零命中排在 0%（不可达）之前。
    private static double gridItemSortKey(ItemGridPanel.GridItem item) {
        Probability probability = item.probability();
        if (probability == null || probability.isUnknown() || probability instanceof Probability.NeedsCondition) {
            return -1.0;
        }
        return probability.upperBound();
    }

    private int resolveSelectedIndex(@Nullable ResourceLocation selectedId, @Nullable ResourceLocation rememberedId) {
        if (this.tableViews.isEmpty()) return -1;
        int current = findTableIndex(selectedId);
        if (current >= 0) return current;
        if (this.selectedCategory != null && this.currentSearch.isEmpty()) {
            int categoryRemembered = findTableIndex(
                    ArchaeologyJournalClientState.getCategorySelection(this.selectedCategory));
            if (categoryRemembered >= 0) return categoryRemembered;
        }
        int remembered = findTableIndex(rememberedId);
        if (remembered >= 0) return remembered;
        for (int index = 0; index < this.tableViews.size(); index++) {
            if (this.tableViews.get(index).unlocked()) return index;
        }
        return -1;
    }

    private int findTableIndex(@Nullable ResourceLocation id) {
        if (id == null) return -1;
        for (int index = 0; index < this.tableViews.size(); index++) {
            if (this.tableViews.get(index).id().equals(id)) return index;
        }
        return -1;
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
                                  int parsedCount, int totalCount, ArchaeologyEntryLogRef logRef) {
    }
}
