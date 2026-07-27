package com.meteorite.unsuspiciousblock.journal.catalog;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.loottable.analysis.LootTableJsonParser;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.CatalogStructure;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.TableDefinition;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableNames;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.Reader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 考古手册目录加载器——构建收录闭包、引用层级与基于 type 的目录分类。
 */
public final class ArchaeologyJournalCatalog {
    private static final FileToIdConverter LOOT_TABLES = FileToIdConverter.json("loot_table");

    private ArchaeologyJournalCatalog() {
    }

    // 加载考古手册目录：构建引用图 → 检测循环引用 → 解析表 → 加载分类 → 组装结果
    public static LoadResult load(ResourceManager resourceManager, HolderLookup.Provider registries) {
        Map<ResourceLocation, Resource> resources = LOOT_TABLES.listMatchingResources(resourceManager);
        Map<ResourceLocation, List<ResourceLocation>> graph = buildReferenceGraph(resources);
        Set<ResourceLocation> explicitlyTrackedTables = new LinkedHashSet<>();
        for (ResourceLocation tableId : graph.keySet()) {
            if (LootTableNames.isArchaeologyLootTable(tableId)) explicitlyTrackedTables.add(tableId);
        }

        Set<ResourceLocation> initialClosure = collectReachable(explicitlyTrackedTables, graph, Set.of());
        Set<ResourceLocation> cycleTables = findCycleTables(initialClosure, graph);
        Set<ResourceLocation> validClosure = collectReachable(explicitlyTrackedTables, graph, cycleTables);

        LootTableJsonParser parser = new LootTableJsonParser(
                validClosure::contains, LootTableNames::resolveDisplayName, cycleTables);
        Map<ResourceLocation, TableDefinition> parsed = parser.load(resourceManager, registries);

        LinkedHashMap<ResourceLocation, TableDefinition> tables = new LinkedHashMap<>();
        for (Map.Entry<ResourceLocation, TableDefinition> entry : parsed.entrySet()) {
            List<ResourceLocation> children = graph.getOrDefault(entry.getKey(), List.of()).stream()
                    .filter(parsed::containsKey)
                    .toList();
            tables.put(entry.getKey(), entry.getValue().withChildTables(children));
            LootTableNames.ensureRegistered(entry.getKey());
            LootTableNames.resolveDisplayName(entry.getKey());
        }

        JournalCategoryLoader.CategorySet categories = JournalCategoryLoader.load(resourceManager);
        LinkedHashMap<ResourceLocation, ResourceLocation> rootCategories = new LinkedHashMap<>();
        for (ResourceLocation tableId : explicitlyTrackedTables) {
            TableDefinition table = tables.get(tableId);
            if (table == null) continue;
            rootCategories.put(table.id(), categories.classify(table.id(), table.type()));
        }
        CatalogStructure structure = new CatalogStructure(categories.definitions(), rootCategories);
        return new LoadResult(Map.copyOf(tables), structure);
    }

    // 构建战利品表引用图：遍历所有 loot_table 资源，解析每个表中的直接引用关系
    private static Map<ResourceLocation, List<ResourceLocation>> buildReferenceGraph(
            Map<ResourceLocation, Resource> resources) {
        LinkedHashMap<ResourceLocation, List<ResourceLocation>> graph = new LinkedHashMap<>();
        for (Map.Entry<ResourceLocation, Resource> entry : resources.entrySet()) {
            ResourceLocation tableId = LOOT_TABLES.fileToId(entry.getKey());
            LinkedHashSet<ResourceLocation> references = new LinkedHashSet<>();
            try (Reader reader = entry.getValue().openAsReader()) {
                collectDirectReferences(JsonParser.parseReader(reader), references);
            } catch (Exception exception) {
                Constants.LOG.warn("读取战利品表引用关系失败 {}", tableId, exception);
            }
            graph.put(tableId, List.copyOf(references));
        }
        return graph;
    }

    // 递归收集 JSON 元素中的直接战利品表引用（type 为 loot_table 或 minecraft:loot_table 的条目）
    private static void collectDirectReferences(JsonElement element, Set<ResourceLocation> output) {
        if (element == null || element.isJsonNull()) return;
        if (element.isJsonArray()) {
            element.getAsJsonArray().forEach(child -> collectDirectReferences(child, output));
            return;
        }
        if (!element.isJsonObject()) return;
        JsonObject object = element.getAsJsonObject();
        if (object.has("type") && object.get("type").isJsonPrimitive()) {
            String type = object.get("type").getAsString();
            if (type.equals("loot_table") || type.equals("minecraft:loot_table")) {
                JsonElement idElement = object.has("value") ? object.get("value") : object.get("name");
                if (idElement != null && idElement.isJsonPrimitive()) {
                    ResourceLocation id = ResourceLocation.tryParse(idElement.getAsString());
                    if (id != null) output.add(id);
                }
                return;
            }
        }
        object.entrySet().forEach(entry -> collectDirectReferences(entry.getValue(), output));
    }

    // 从根节点出发 BFS 收集所有可达节点，排除 specified 集合中的节点
    private static Set<ResourceLocation> collectReachable(Set<ResourceLocation> roots,
                                                           Map<ResourceLocation, List<ResourceLocation>> graph,
                                                           Set<ResourceLocation> excluded) {
        LinkedHashSet<ResourceLocation> result = new LinkedHashSet<>();
        ArrayList<ResourceLocation> queue = new ArrayList<>(roots);
        for (int index = 0; index < queue.size(); index++) {
            ResourceLocation current = queue.get(index);
            if (excluded.contains(current) || !graph.containsKey(current) || !result.add(current)) continue;
            for (ResourceLocation child : graph.getOrDefault(current, List.of())) {
                if (!excluded.contains(child)) queue.add(child);
            }
        }
        return result;
    }

    // 在给定节点集合中检测循环引用，返回所有参与循环的表 ID
    private static Set<ResourceLocation> findCycleTables(Set<ResourceLocation> nodes,
                                                          Map<ResourceLocation, List<ResourceLocation>> graph) {
        Map<ResourceLocation, VisitState> states = new HashMap<>();
        List<ResourceLocation> stack = new ArrayList<>();
        Set<ResourceLocation> cycleTables = new HashSet<>();
        Set<String> reported = new HashSet<>();
        for (ResourceLocation node : nodes) {
            if (!states.containsKey(node)) dfsCycles(node, nodes, graph, states, stack, cycleTables, reported);
        }
        return cycleTables;
    }

    // DFS 遍历检测循环引用，发现环时记录日志并将环中所有节点加入排除集合
    private static void dfsCycles(ResourceLocation node, Set<ResourceLocation> nodes,
                                  Map<ResourceLocation, List<ResourceLocation>> graph,
                                  Map<ResourceLocation, VisitState> states, List<ResourceLocation> stack,
                                  Set<ResourceLocation> cycleTables, Set<String> reported) {
        states.put(node, VisitState.VISITING);
        stack.add(node);
        for (ResourceLocation child : graph.getOrDefault(node, List.of())) {
            if (!nodes.contains(child)) continue;
            VisitState state = states.get(child);
            if (state == VisitState.VISITING) {
                int start = stack.indexOf(child);
                List<ResourceLocation> cycle = new ArrayList<>(stack.subList(start, stack.size()));
                cycleTables.addAll(cycle);
                cycle.add(child);
                String signature = cycle.toString();
                if (reported.add(signature)) {
                    Constants.LOG.warn("检测到战利品表循环引用，排除闭环: {}", cycle);
                }
            } else if (state == null) {
                dfsCycles(child, nodes, graph, states, stack, cycleTables, reported);
            }
        }
        stack.removeLast();
        states.put(node, VisitState.VISITED);
    }

    public record LoadResult(Map<ResourceLocation, TableDefinition> tables, CatalogStructure structure) {
    }

    private enum VisitState {
        VISITING,
        VISITED
    }
}
