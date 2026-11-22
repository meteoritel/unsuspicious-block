package com.meteorite.unsuspiciousblock.core.journal.catalog;

import com.meteorite.unsuspiciousblock.core.loottable.diagnostics.LootDebugMode;
import com.meteorite.unsuspiciousblock.core.loottable.LootTrackingRules;
import com.meteorite.unsuspiciousblock.core.loottable.analysis.CompiledLootTable;
import com.meteorite.unsuspiciousblock.core.loottable.analysis.LootFunctionPreviewContext;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.LootTableCatalog.CatalogStructure;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.LootTableCatalog.TableDefinition;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.LootTableNames;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.LootTableProjector;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.StaticTableProjection;
import com.meteorite.unsuspiciousblock.core.loottable.graph.LootTableReferenceGraph;
import com.meteorite.unsuspiciousblock.core.journal.catalog.ArchaeologyJournalServerCatalog.ResourceAnalysis;
import com.meteorite.unsuspiciousblock.core.loottable.injection.DeclaredInjectionExpansion;
import com.meteorite.unsuspiciousblock.core.loottable.injection.DeclaredInjectionExpansion.ExpandedTable;
import com.meteorite.unsuspiciousblock.core.loottable.injection.DeclaredLootInjection;
import com.meteorite.unsuspiciousblock.core.loottable.signature.SignatureExcludedComponents;
import com.meteorite.unsuspiciousblock.core.loottable.source.LootTableSourceSnapshot;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayDeque;
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

    /** 沿收录根表投影生效表，保留收录根表的独立分类入口。 */
    static LoadResult load(long generation, ResourceAnalysis resources, HolderLookup.Provider registries,
                           LootFunctionPreviewContext previewContext, LootTrackingRules rules,
                           SignatureExcludedComponents signatureExclusions) {
        LootTableReferenceGraph referenceGraph = resources.graph();
        LootTableSourceSnapshot sourceSnapshot = resources.sources();
        Set<ResourceLocation> trackedRoots = new LinkedHashSet<>();
        Set<ResourceLocation> excluded = new LinkedHashSet<>();
        for (ResourceLocation tableId : referenceGraph.nodes()) {
            if (!LootDebugMode.allows(tableId)) {
                excluded.add(tableId);
            } else if (rules.isRootCandidate(tableId)) {
                trackedRoots.add(tableId);
            }
        }

        Set<ResourceLocation> initialClosure = referenceGraph.reachableFrom(trackedRoots, excluded);
        Set<ResourceLocation> cycleTables = new LinkedHashSet<>();
        for (var component : referenceGraph.cyclicComponentsIn(initialClosure)) {
            cycleTables.addAll(component);
        }
        excluded.addAll(cycleTables);
        Set<ResourceLocation> validClosure = referenceGraph.reachableFrom(trackedRoots, excluded);

        // 编译每张表的上下文无关语义，再由投影器沿 JSON 引用把它们链接成静态投影
        Map<ResourceLocation, CompiledLootTable> compiledTables =
                resources.compiler().compile(validClosure);
        LootTableProjector projector = new LootTableProjector(compiledTables, excluded, registries,
                previewContext, signatureExclusions);

        // 按表 id 排序遍历，保持与原解析路径一致的展示名登记顺序
        List<ResourceLocation> orderedTables = new ArrayList<>(validClosure);
        orderedTables.sort(Comparator.comparing(ResourceLocation::toString));

        LinkedHashMap<ResourceLocation, TableDefinition> tables = new LinkedHashMap<>();
        Map<ResourceLocation, Set<ResourceLocation>> foldedItems = new LinkedHashMap<>();
        for (ResourceLocation tableId : orderedTables) {
            if (!compiledTables.containsKey(tableId)) continue;
            Component displayName = Component.translatableWithFallback(
                    LootTableNames.translationKey(tableId), LootTableNames.fallbackName(tableId));
            StaticTableProjection projection = projector.project(tableId);
            if (!projection.foldedItems().isEmpty()) foldedItems.put(tableId, projection.foldedItems());
            tables.put(tableId, new TableDefinition(tableId, displayName, projection.declaredType(),
                    projection.items(), 0, referenceGraph.directChildren(tableId).stream()
                            .filter(compiledTables::containsKey).toList()));
        }
        Map<ResourceLocation, List<DeclaredLootInjection>> declarations = new LinkedHashMap<>();
        tables.keySet().forEach(id -> declarations.put(id, resources.declarations().apply(id)));
        Map<ResourceLocation, ExpandedTable> expansions = DeclaredInjectionExpansion.build(
                tables, declarations, resources.injectionEdges(), registries);
        Map<ResourceLocation, TableDefinition> collected = collectMembers(trackedRoots, expansions);

        JournalCategoryLoader.CategorySet categories = resources.categories();
        Map<ResourceLocation, List<ResourceLocation>> parents = new LinkedHashMap<>();
        collected.values().forEach(table -> table.childTables().forEach(child ->
                parents.computeIfAbsent(child, ignored -> new ArrayList<>()).add(table.id())));
        Map<ResourceLocation, ResourceLocation> rootCategories = new LinkedHashMap<>();
        Map<ResourceLocation, ResourceLocation> defaultTools = new LinkedHashMap<>();
        collected.forEach((id, table) -> {
            ResourceLocation category = categories.classify(id, table.type());
            if (trackedRoots.contains(id)) {
                rootCategories.put(id, category);
            }
            defaultTools.put(id, categories.defaultTool(category));
        });
        parents.replaceAll((id, ids) -> ids.stream().sorted().toList());
        CatalogStructure structure = new CatalogStructure(categories.definitions(), rootCategories);
        LootTableAnalysisSession session = new LootTableAnalysisSession(generation, sourceSnapshot,
                referenceGraph, compiledTables);
        return new LoadResult(session, structure, Map.copyOf(collected), Map.copyOf(defaultTools), expansions,
                Map.copyOf(declarations), Map.copyOf(parents), Set.copyOf(cycleTables), Map.copyOf(foldedItems));
    }

    private static Map<ResourceLocation, TableDefinition> collectMembers(Set<ResourceLocation> roots,
                                                                        Map<ResourceLocation, ExpandedTable> expansions) {
        Map<ResourceLocation, TableDefinition> members = new LinkedHashMap<>();
        ArrayDeque<ResourceLocation> queue = new ArrayDeque<>(roots);
        while (!queue.isEmpty()) {
            ResourceLocation id = queue.removeFirst();
            ExpandedTable expansion = expansions.get(id);
            if (expansion == null || members.containsKey(id)) continue;
            TableDefinition table = expansion.display();
            members.put(id, table);
            queue.addAll(table.childTables());
        }
        return members;
    }

    /** 正式目录与规则预览共用的静态候选、分类与引用关系。 */
    public record LoadResult(LootTableAnalysisSession session, CatalogStructure structure,
                             Map<ResourceLocation, TableDefinition> staticTables,
                             Map<ResourceLocation, ResourceLocation> defaultTools,
                             Map<ResourceLocation, ExpandedTable> expansions,
                             Map<ResourceLocation, List<DeclaredLootInjection>> declarations,
                             Map<ResourceLocation, List<ResourceLocation>> parents,
                             Set<ResourceLocation> cyclicTables,
                             Map<ResourceLocation, Set<ResourceLocation>> foldedItems) {
    }
}
