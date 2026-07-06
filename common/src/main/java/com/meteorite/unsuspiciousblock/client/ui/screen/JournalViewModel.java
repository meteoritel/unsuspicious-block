package com.meteorite.unsuspiciousblock.client.ui.screen;

import com.meteorite.unsuspiciousblock.client.ui.entry.ArchaeologyEntryItem;
import com.meteorite.unsuspiciousblock.client.ui.entry.ArchaeologyEntryLogRef;
import com.meteorite.unsuspiciousblock.client.ui.entry.ArchaeologyJournalEntry;
import com.meteorite.unsuspiciousblock.client.ui.panel.CatalogPanel;
import com.meteorite.unsuspiciousblock.client.ui.panel.ItemGridPanel;
import com.meteorite.unsuspiciousblock.client.ui.panel.RightPageContainer;
import com.meteorite.unsuspiciousblock.client.ui.support.ArchaeologyJournalClientState;
import com.meteorite.unsuspiciousblock.client.ui.support.CatalogSorter;
import com.meteorite.unsuspiciousblock.client.ui.support.JournalSearchQuery;
import com.meteorite.unsuspiciousblock.client.ui.support.LogGrouper;
import com.meteorite.unsuspiciousblock.journal.state.ArchaeologyJournalLogState;
import com.meteorite.unsuspiciousblock.journal.state.ArchaeologyJournalState;
import com.meteorite.unsuspiciousblock.loottable.ArchaeologyLootTableCatalog.TableDefinition;
import com.meteorite.unsuspiciousblock.loottable.ProbabilityFormat;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 考古笔记的视图模型，管理目录数据、搜索/排序状态和数据刷新逻辑。
 * <p>
 * 从 {@link ArchaeologyJournalScreen} 中提取，职责包括：
 * <ul>
 *   <li>持有目录定义、玩家进度、日志状态等数据快照</li>
 *   <li>管理目录搜索/排序状态和日志搜索/排序状态</li>
 *   <li>刷新时检测服务端版本变更并重建数据</li>
 *   <li>根据搜索/排序条件筛选目录条目并维护选中索引</li>
 * </ul>
 */
public class JournalViewModel {

    private final ArchaeologyJournalState state;
    private ArchaeologyJournalLogState logState;
    private final List<ArchaeologyJournalEntry> tableViews = new ArrayList<>();
    private final Map<ResourceLocation, TableDefinition> catalogDefinitions = new LinkedHashMap<>();
    private int selectedIndex = -1;
    private boolean selectionInitialized;
    // 全局战利品表统计（不受 hideLocked / 搜索过滤影响），用于底部进度显示
    private int totalTableCount;
    private int globalUnlockedTableCount;

    // 目录搜索/排序状态
    private JournalSearchQuery currentSearch = JournalSearchQuery.EMPTY;
    private CatalogSorter.SortOrder currentSortOrder = CatalogSorter.SortOrder.DEFAULT;
    private boolean sortDescending = false;
    private boolean hideLocked = false;

    // 日志排序状态
    private boolean logSortDescending = true;
    private LogGrouper.GroupMode currentGroupMode = LogGrouper.GroupMode.TIME;

    // 版本追踪
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

    // —— 目录搜索/排序访问器 ——
    public void setCurrentSearch(JournalSearchQuery search) {
        this.currentSearch = search;
    }

    public void setCurrentSortOrder(CatalogSorter.SortOrder order) {
        this.currentSortOrder = order;
    }

    public void setSortDescending(boolean descending) {
        this.sortDescending = descending;
    }

    public void setHideLocked(boolean hideLocked) {
        this.hideLocked = hideLocked;
    }

    // —— 日志排序访问器 ——

    public void setLogSortDescending(boolean descending) {
        this.logSortDescending = descending;
    }

    public void setCurrentGroupMode(LogGrouper.GroupMode mode) {
        this.currentGroupMode = mode;
    }

    // —— 数据访问 ——

    public List<ArchaeologyJournalEntry> tableViews() {
        return tableViews;
    }

    public int selectedIndex() {
        return selectedIndex;
    }

    public void setSelectedIndex(int index) {
        this.selectedIndex = index;
    }

    @Nullable
    public ResourceLocation selectedTableId() {
        ArchaeologyJournalEntry selected = selectedTable();
        return selected != null ? selected.id() : null;
    }

    @Nullable
    public ArchaeologyJournalEntry selectedTable() {
        if (this.selectedIndex < 0 || this.selectedIndex >= this.tableViews.size()) {
            return null;
        }
        return this.tableViews.get(this.selectedIndex);
    }

    // 全局已解锁战利品表数量（不受 hideLocked / 搜索过滤影响）
    public int unlockedTableCount() {
        return this.globalUnlockedTableCount;
    }

    // 全局战利品表总数（不受 hideLocked / 搜索过滤影响）
    public int totalTableCount() {
        return this.totalTableCount;
    }

    public boolean isEmpty() {
        return tableViews.isEmpty();
    }

    // —— 数据刷新 ——

    /** 重新加载服务端目录定义 */
    public void reloadCatalog() {
        this.catalogDefinitions.clear();
        this.catalogDefinitions.putAll(ArchaeologyJournalClientState.getCatalog());
    }

    /**
     * 检查服务端版本变更并刷新数据，返回是否有变更。
     * 有变更时由调用方负责触发 UI 重建。
     */
    public boolean refreshIfNeeded() {
        long currentCatalogRevision = ArchaeologyJournalClientState.getCatalogRevision();
        long currentStateRevision = ArchaeologyJournalClientState.getStateRevision();
        long currentLogRevision = ArchaeologyJournalClientState.getLogRevision();
        boolean catalogChanged = currentCatalogRevision != this.lastCatalogRevision;
        boolean stateChanged = currentStateRevision != this.lastStateRevision;
        boolean logChanged = currentLogRevision != this.lastLogRevision;

        if (!catalogChanged && !stateChanged && !logChanged) {
            return false;
        }

        if (stateChanged) {
            this.lastStateRevision = currentStateRevision;
            this.state.copyFrom(ArchaeologyJournalClientState.getState());
        }

        if (catalogChanged) {
            this.lastCatalogRevision = currentCatalogRevision;
            this.reloadCatalog();
        }

        if (logChanged) {
            this.lastLogRevision = currentLogRevision;
            this.logState = ArchaeologyJournalClientState.getLogState();
        }

        return true;
    }

    /**
     * 根据当前搜索/排序条件重建目录视图列表和选中索引。
     * 同时将日志搜索/排序状态应用到日志面板。
     *
     * @param rightPage 右侧页面容器，用于同步日志状态；可为 null（首次初始化时）
     * @param catalogPanel 目录面板，用于更新条目数据；可为 null
     */
    public void rebuildViewModels(@Nullable RightPageContainer rightPage, @Nullable CatalogPanel catalogPanel) {
        ResourceLocation selectedId = selectedTableId();
        ResourceLocation rememberedId = this.selectionInitialized
                ? null
                : ArchaeologyJournalClientState.getLastSelectedTableId();

        this.tableViews.clear();
        // 重新统计全局解锁进度（不受 hideLocked / 搜索过滤影响）
        int unlockedCount = 0;
        for (Map.Entry<ResourceLocation, TableDefinition> entry : this.catalogDefinitions.entrySet()) {
            ResourceLocation id = entry.getKey();
            TableDefinition definition = entry.getValue();
            ArchaeologyJournalState.TableProgress progress = this.state.getTable(id);
            ArchaeologyJournalLogState.TableLogHistory logHistory = this.logState.getTable(id);
            ArchaeologyJournalEntry view = ArchaeologyJournalEntry.of(id, definition, progress, logHistory);

            if (view.unlocked()) {
                unlockedCount++;
            }

            // 隐藏未解锁条目（用户偏好）
            if (this.hideLocked && !view.unlocked()) {
                continue;
            }

            // 应用搜索过滤
            if (!this.currentSearch.isEmpty()) {
                boolean matches = this.currentSearch.matchesTableByItem(
                        id, view.displayName().getString(), view.type(), view.items());
                if (!matches) continue;
            }
            this.tableViews.add(view);
        }
        this.totalTableCount = this.catalogDefinitions.size();
        this.globalUnlockedTableCount = unlockedCount;

        // 应用排序
        this.tableViews.sort(CatalogSorter.getComparator(this.currentSortOrder, this.sortDescending));

        this.selectedIndex = resolveSelectedIndex(selectedId, rememberedId);
        if (!this.selectionInitialized && !this.tableViews.isEmpty()) {
            this.selectionInitialized = true;
        }

        if (catalogPanel != null) {
            catalogPanel.setEntries(buildCatalogEntries());
            catalogPanel.ensureIndexVisible(this.selectedIndex);
        }

        // 重新应用日志排序/分组状态
        if (rightPage != null) {
            rightPage.getLogPanel().setSortDescending(this.logSortDescending);
            rightPage.getLogPanel().setGroupMode(this.currentGroupMode);
        }
    }

    /**
     * 构建当前选中条目的物品网格数据。
     *
     * @return 网格物品列表；若无选中条目则返回 null
     */
    @Nullable
    public BuildGridResult buildGridItems() {
        ArchaeologyJournalEntry selected = selectedTable();
        if (selected == null) return null;

        List<ItemGridPanel.GridItem> gridItems = new ArrayList<>();
        for (ArchaeologyEntryItem iv : selected.items()) {
            // 仅物品级搜索（$ 前缀）才启用网格遮罩；表级搜索（名称/@/%）不影响物品高亮
            boolean highlighted = this.currentSearch.mode() != JournalSearchQuery.Mode.ITEM_NAME
                    || this.currentSearch.matchesItem(
                            iv.id(), iv.displayName().getString());
            gridItems.add(new ItemGridPanel.GridItem(
                    iv.id(), iv.displayName(), iv.tooltipHint(),
                    iv.probability(), iv.unlocked(), iv.count(), iv.signature(), highlighted,
                    iv.sourceChildTable()));
        }
        // 排序：先按表来源（根表 null 优先；子表按 ResourceLocation 字典序升序），再按概率降序
        // 注：用取负实现概率降序，避免整体 reversed() 同时反转 nullsFirst 与字典序
        gridItems.sort(Comparator
                .comparing(ItemGridPanel.GridItem::sourceChildTable,
                        Comparator.nullsFirst(Comparator.comparing(String::valueOf)))
                .thenComparingDouble(value -> -JournalViewModel.gridItemSortKey(value)));
        return new BuildGridResult(
                selected.id(), gridItems,
                selected.parsedCount(), selected.totalCount(),
                selected.logRef()
        );
    }

    // 网格物品排序键：返回概率比例值（0~1），用于从大到小排序。
    // 概率无法解析（null/"?"）或 10000 次模拟未掉落（"<0.01%"）统一返回 -1.0，排序时落在末尾。
    private static double gridItemSortKey(ItemGridPanel.GridItem item) {
        String prob = item.probability();
        if (prob == null || prob.equals("?") || prob.equals("<0.01%")) {
            return -1.0;
        }
        return ProbabilityFormat.parsePercentToFraction(prob);
    }

    public List<CatalogPanel.CatalogEntryData> buildCatalogEntries() {
        List<CatalogPanel.CatalogEntryData> catalogEntries = new ArrayList<>();
        for (ArchaeologyJournalEntry tv : this.tableViews) {
            catalogEntries.add(new CatalogPanel.CatalogEntryData(tv.id(), tv.displayName(), tv.unlocked(), tv.favorite()));
        }
        return catalogEntries;
    }

    private int resolveSelectedIndex(@Nullable ResourceLocation selectedId, @Nullable ResourceLocation rememberedId) {
        if (this.tableViews.isEmpty()) {
            return -1;
        }

        int currentIndex = findTableIndex(selectedId);
        if (currentIndex >= 0) {
            return currentIndex;
        }

        int rememberedIndex = findTableIndex(rememberedId);
        if (rememberedIndex >= 0) {
            return rememberedIndex;
        }

        int firstUnlockedIndex = findFirstUnlockedIndex();
        return Math.max(firstUnlockedIndex, 0);
    }

    private int findTableIndex(@Nullable ResourceLocation tableId) {
        if (tableId == null) return -1;
        for (int i = 0; i < this.tableViews.size(); i++) {
            if (this.tableViews.get(i).id().equals(tableId)) {
                return i;
            }
        }
        return -1;
    }

    private int findFirstUnlockedIndex() {
        for (int i = 0; i < this.tableViews.size(); i++) {
            if (this.tableViews.get(i).unlocked()) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 构建网格物品的结果记录，避免返回多个零散值。
     */
    public record BuildGridResult(
            ResourceLocation tableId,
            List<ItemGridPanel.GridItem> gridItems,
            int parsedCount,
            int totalCount,
            ArchaeologyEntryLogRef logRef
    ) {}
}