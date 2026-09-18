package com.meteorite.unsuspiciousblock.journal.catalog;

import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.loottable.analysis.CompiledLootTable;
import com.meteorite.unsuspiciousblock.loottable.analysis.LootTableCompiler;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.CatalogStructure;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.TableDefinition;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableNames;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableProjector;
import com.meteorite.unsuspiciousblock.loottable.catalog.StaticTableProjection;
import com.meteorite.unsuspiciousblock.loottable.graph.LootTableReferenceGraph;
import com.meteorite.unsuspiciousblock.loottable.graph.RuntimeLootLinks;
import com.meteorite.unsuspiciousblock.loottable.source.LootTableSourceSnapshot;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 考古笔记目录加载器——基于引用图构建收录闭包与引用层级，并按声明类型分类。
 * <p>
 * 引用关系的唯一权威是 {@link LootTableReferenceGraph}：直接子表、可达集、循环集合
 * 与子树遍历都从图取出，本类不再自行扫描 JSON 或实现环检测。
 */
public final class ArchaeologyJournalCatalog {

    private ArchaeologyJournalCatalog() {
    }

    /**
     * 加载考古笔记目录：建图 → 算初始可达集与环排除集 → 解析有效表 → 加载分类 → 组装结果。
     *
     * @param sourceSnapshot 本轮资源快照（有效原文 + 完整资源栈）
     * @param resourceManager 仅用于加载目录分类资源，不再用于读取战利品表
     */
    public static LoadResult load(LootTableSourceSnapshot sourceSnapshot, ResourceManager resourceManager,
                                  HolderLookup.Provider registries) {
        LootTableReferenceGraph referenceGraph = LootTableReferenceGraph.build(
                sourceSnapshot, RuntimeLootLinks.syntheticEdges());

        Set<ResourceLocation> trackedRoots = new LinkedHashSet<>();
        for (ResourceLocation tableId : referenceGraph.nodes()) {
            if (LootTableNames.isArchaeologyLootTable(tableId)) {
                trackedRoots.add(tableId);
            }
        }

        Set<ResourceLocation> initialClosure = referenceGraph.reachableFrom(trackedRoots);
        Set<ResourceLocation> cycleTables = reportCycles(referenceGraph, initialClosure);
        Set<ResourceLocation> validClosure = referenceGraph.reachableFrom(trackedRoots, cycleTables);

        // 编译每张表的上下文无关语义，再由投影器沿 JSON 引用把它们链接成静态投影
        Map<ResourceLocation, CompiledLootTable> compiledTables =
                new LootTableCompiler(registries).compile(sourceSnapshot, validClosure);
        LootTableProjector projector = new LootTableProjector(
                referenceGraph, compiledTables, cycleTables, registries);

        // 按表 id 排序遍历，保持与原解析路径一致的展示名登记顺序
        List<ResourceLocation> orderedTables = new ArrayList<>(validClosure);
        orderedTables.sort(Comparator.comparing(ResourceLocation::toString));

        LinkedHashMap<ResourceLocation, TableDefinition> tables = new LinkedHashMap<>();
        for (ResourceLocation tableId : orderedTables) {
            LootTableNames.ensureRegistered(tableId);
            Component displayName = LootTableNames.resolveDisplayName(tableId);
            StaticTableProjection projection = projector.project(tableId);
            // 无物品的表不进目录；子表入口由投影层按同样规则过滤，两边一致
            if (projection == null || projection.isEmpty()) {
                continue;
            }
            tables.put(tableId, new TableDefinition(tableId, displayName, projection.declaredType(),
                    projection.items(), 0, projection.childTables()));
        }
        // 批量解析结束：先汇总输出缺失 key 警告（汇总过程中登记待补全条目），再统一落盘
        LootTableNames.logMissingTranslationSummary();

        JournalCategoryLoader.CategorySet categories = JournalCategoryLoader.load(resourceManager);
        LinkedHashMap<ResourceLocation, ResourceLocation> rootCategories = new LinkedHashMap<>();
        for (ResourceLocation tableId : trackedRoots) {
            TableDefinition table = tables.get(tableId);
            if (table == null) continue;
            rootCategories.put(table.id(), categories.classify(table.id(), table.type()));
        }
        CatalogStructure structure = new CatalogStructure(categories.definitions(), rootCategories);
        return new LoadResult(Map.copyOf(tables), structure, referenceGraph);
    }

    /**
     * 输出追踪根初始可达集内的循环告警，返回需要排除出闭包的环上表集合。
     * <p>
     * 环的发现与排除只作用于该 scope：与考古目录无关的第三方表循环不新增告警，
     * 也不会影响本目录的收录结果。
     */
    private static Set<ResourceLocation> reportCycles(LootTableReferenceGraph graph,
                                                      Set<ResourceLocation> scope) {
        Set<ResourceLocation> cycleTables = new LinkedHashSet<>();
        for (Set<ResourceLocation> component : graph.cyclicComponentsIn(scope)) {
            cycleTables.addAll(component);
            Constants.LOG.warn("检测到战利品表循环引用，排除闭环: {}", component);
        }
        return cycleTables;
    }

    /** 目录加载结果：收录到的表、分类结构，以及本轮引用图（供哈希等下游复用同一份拓扑）。 */
    public record LoadResult(Map<ResourceLocation, TableDefinition> tables, CatalogStructure structure,
                             LootTableReferenceGraph referenceGraph) {
    }
}
