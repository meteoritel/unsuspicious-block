package com.meteorite.unsuspiciousblock.client.ui.screen;

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
import com.meteorite.unsuspiciousblock.loottable.analysis.LootConditionHandlers;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.CatalogCategoryDefinition;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.CatalogStructure;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.ChildTableProbability;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.ItemDefinition;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.LootAcquisitionPath;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.TableDefinition;
import com.meteorite.unsuspiciousblock.loottable.signature.LootResultSignature;
import com.meteorite.unsuspiciousblock.loottable.simulation.ProbabilityFormat;
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
import java.util.Map;
import java.util.Set;

/**
 * 考古手册视图模型——管理分类首页、父子目录、搜索排序与右页数据快照。
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

    public JournalViewModel(ArchaeologyJournalState state) {
        this.state = state;
        this.logState = ArchaeologyJournalClientState.getLogState();
    }

    public void initRevisions() {
        this.lastCatalogRevision = ArchaeologyJournalClientState.getCatalogRevision();
        this.lastStateRevision = ArchaeologyJournalClientState.getStateRevision();
        this.lastLogRevision = ArchaeologyJournalClientState.getLogRevision();
    }

    public void setCurrentSearch(JournalSearchQuery search) { this.currentSearch = search; }
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
        if (this.currentSearch.isEmpty() && this.selectedCategory != null
                && index >= 0 && index < this.tableViews.size()) {
            ArchaeologyJournalClientState.rememberCategorySelection(
                    this.selectedCategory, this.tableViews.get(index).id());
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

    public int unlockedTableCount() { return this.globalUnlockedTableCount; }
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
                .filter(view -> view.unlocked()
                        ? this.currentSearch.matchesTableByItem(view.id(), view.displayName().getString(),
                        view.type(), view.items())
                        : this.currentSearch.matchesLockedTableId(view.id()))
                .sorted(CatalogSorter.getComparator(this.currentSortOrder, this.sortDescending))
                .toList();
        for (ArchaeologyJournalEntry view : matches) {
            List<ResourceLocation> parentPath = firstPaths.getOrDefault(view.id(), List.of());
            addRow(view, 0, !parentPath.isEmpty(), parentPath,
                    List.copyOf(this.categoryIdsByTable.getOrDefault(view.id(), Set.of())));
        }
    }

    private void flattenChildren(ResourceLocation parentId, int depth, List<ResourceLocation> path,
                                 Set<ResourceLocation> branch) {
        if (!branch.add(parentId)) return;
        TableDefinition parent = this.catalogDefinitions.get(parentId);
        if (parent == null) return;
        List<ArchaeologyJournalEntry> children = parent.childTables().stream().map(this.allViews::get)
                .filter(java.util.Objects::nonNull)
                .filter(view -> !this.hideLocked || view.unlocked())
                .sorted(Comparator
                        .comparing((ArchaeologyJournalEntry view) -> !hasChildTables(view.id()))
                        .thenComparing(CatalogSorter.getComparator(
                                this.currentSortOrder, this.sortDescending)))
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

    private Comparator<ArchaeologyJournalEntry> rootComparator() {
        Comparator<ArchaeologyJournalEntry> base = CatalogSorter.getComparator(this.currentSortOrder, this.sortDescending);
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

        this.categoryIdsByTable.clear();
        this.structure.rootCategories().forEach((root, category) -> {
            Set<ResourceLocation> members = new HashSet<>();
            collectDescendants(root, members);
            members.stream().filter(this.allViews::containsKey).forEach(id ->
                    this.categoryIdsByTable.computeIfAbsent(id, ignored -> new LinkedHashSet<>()).add(category));
        });
    }

    private Map<ResourceLocation, List<ResourceLocation>> firstPathsByTable() {
        Map<ResourceLocation, List<ResourceLocation>> result = new LinkedHashMap<>();
        this.structure.rootCategories().keySet().stream().sorted(Comparator.comparing(ResourceLocation::toString))
                .forEach(root -> collectFirstPaths(root, List.of(), result, new HashSet<>()));
        return result;
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
                    Constants.LOG.warn("考古手册状态异常：子表 {} 已解锁，但父表 {} 未解锁", childId, parent.id());
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
        if (selected == null) return null;
        List<ItemGridPanel.GridItem> gridItems = new ArrayList<>();
        for (ArchaeologyEntryItem item : selected.items()) {
            ItemGridPanel.GridItem gridItem = buildDirectGridItem(item, true);
            if (gridItem != null) {
                gridItems.add(gridItem);
            }
        }
        gridItems.sort(Comparator.comparing(ItemGridPanel.GridItem::primarySourceChildTable,
                        Comparator.nullsFirst(Comparator.comparing(String::valueOf)))
                .thenComparingDouble(value -> -gridItemSortKey(value)));
        List<ItemGridPanel.ChildTableEntry> childEntries = new ArrayList<>();
        TableDefinition selectedDefinition = this.catalogDefinitions.get(selected.id());
        if (selectedDefinition != null) {
            for (ChildTableProbability childProbability : selectedDefinition.childTableProbabilities()) {
                ArchaeologyJournalEntry child = this.allViews.get(childProbability.tableId());
                if (child == null) continue;
                List<ItemGridPanel.GridItem> previewItems = buildChildPreviewItems(
                        child.id(), new HashSet<>());
                childEntries.add(new ItemGridPanel.ChildTableEntry(
                        child.id(), child.displayName(), childProbability.probability(),
                        childProbability.scenarioProbabilities(), previewItems));
            }
        }
        List<DetailOverlayPanel.IntroItem> introItems = buildIntroItems(selected.id());
        int parsedCount = (int) introItems.stream().filter(DetailOverlayPanel.IntroItem::unlocked).count();
        return new BuildGridResult(selected.id(), gridItems, childEntries, introItems,
                parsedCount, introItems.size(), selected.logRef());
    }

    // Intro 按需映射当前表及全部后代物品，避免在每个树节点重复缓存完整子树视图。
    private List<DetailOverlayPanel.IntroItem> buildIntroItems(ResourceLocation tableId) {
        Map<LootResultSignature, ItemDefinition> definitionsBySignature = new LinkedHashMap<>();
        collectSubtreeItems(tableId, definitionsBySignature, new HashSet<>());
        ArchaeologyJournalState.TableProgress progress = this.state.getTable(tableId);
        List<DetailOverlayPanel.IntroItem> result = new ArrayList<>(definitionsBySignature.size());
        for (ItemDefinition definition : definitionsBySignature.values()) {
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

    // 深度优先收集唯一物品签名；共享子表与循环引用只处理一次。
    private void collectSubtreeItems(ResourceLocation tableId,
                                     Map<LootResultSignature, ItemDefinition> output,
                                     Set<ResourceLocation> visited) {
        if (!visited.add(tableId)) {
            return;
        }
        TableDefinition definition = this.catalogDefinitions.get(tableId);
        if (definition == null) {
            return;
        }
        for (ItemDefinition item : definition.items()) {
            output.putIfAbsent(item.signature(), item);
        }
        for (ResourceLocation childId : definition.childTables()) {
            collectSubtreeItems(childId, output, visited);
        }
    }

    @Nullable
    private ItemGridPanel.GridItem buildDirectGridItem(ArchaeologyEntryItem item, boolean applySearch) {
        List<LootAcquisitionPath> directPaths = item.acquisitionPaths().stream()
                .filter(path -> path.sourceChildTable() == null)
                .toList();
        if (!item.acquisitionPaths().isEmpty() && directPaths.isEmpty()) {
            return null;
        }
        boolean highlighted = !applySearch || this.currentSearch.mode() != JournalSearchQuery.Mode.ITEM_NAME
                || this.currentSearch.matchesItem(item.id(), item.displayName().getString());
        boolean approximate = item.tooltipHint() != null && item.tooltipHint().getString().equals(
                Component.translatable(
                        "screen.unsuspiciousblock.archaeology_journal.item_hint.approximate").getString());
        LootConditionHandler.UncertaintyLevel level = approximate
                ? LootConditionHandler.UncertaintyLevel.RUNTIME : LootConditionHandler.UncertaintyLevel.NONE;
        for (LootAcquisitionPath path : directPaths) {
            LootConditionHandler.UncertaintyLevel candidate = LootConditionHandlers
                    .computeUncertaintyLevel(path.allConditions(), false);
            if (candidate.ordinal() > level.ordinal()) level = candidate;
        }
        return new ItemGridPanel.GridItem(item.id(), item.displayName(), item.tooltipHint(),
                item.probability(), item.unlocked(), item.count(), item.signature(), highlighted,
                directPaths, item.injected(), level, item.scenarioProbabilities());
    }

    // 纯转发表没有直接物品时，向下寻找首批可展示后代；visited 防止循环引用。
    private List<ItemGridPanel.GridItem> buildChildPreviewItems(
            ResourceLocation tableId, Set<ResourceLocation> visited) {
        if (!visited.add(tableId)) {
            return List.of();
        }
        ArchaeologyJournalEntry table = this.allViews.get(tableId);
        if (table == null) {
            return List.of();
        }
        List<ItemGridPanel.GridItem> directItems = table.items().stream()
                .map(item -> buildDirectGridItem(item, false))
                .filter(java.util.Objects::nonNull)
                .toList();
        if (!directItems.isEmpty()) {
            return directItems;
        }
        TableDefinition definition = this.catalogDefinitions.get(tableId);
        if (definition == null) {
            return List.of();
        }
        List<ItemGridPanel.GridItem> descendants = new ArrayList<>();
        for (ResourceLocation childId : definition.childTables()) {
            descendants.addAll(buildChildPreviewItems(childId, visited));
            if (descendants.size() >= 3) {
                break;
            }
        }
        return List.copyOf(descendants);
    }

    private static double gridItemSortKey(ItemGridPanel.GridItem item) {
        double scenarioMaximum = item.scenarioProbabilities().stream()
                .map(com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.ScenarioProbability::probability)
                .filter(probability -> probability != null && !probability.equals("?")
                        && !probability.equals("<0.01%"))
                .mapToDouble(ProbabilityFormat::parsePercentToFraction)
                .max().orElse(-1.0);
        if (scenarioMaximum >= 0.0) {
            return scenarioMaximum;
        }
        String probability = item.probability();
        return probability == null || probability.equals("?") || probability.equals("<0.01%")
                ? -1.0 : ProbabilityFormat.parsePercentToFraction(probability);
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

    public record BuildGridResult(ResourceLocation tableId, List<ItemGridPanel.GridItem> gridItems,
                                  List<ItemGridPanel.ChildTableEntry> childTables,
                                  List<DetailOverlayPanel.IntroItem> introItems,
                                  int parsedCount, int totalCount, ArchaeologyEntryLogRef logRef) {
    }
}
