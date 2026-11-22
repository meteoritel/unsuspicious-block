package com.meteorite.unsuspiciousblock.core.loottable.catalog;

import com.meteorite.unsuspiciousblock.core.loottable.catalog.LootTableCatalog.ItemDefinition;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.LootTableCatalog.TableDefinition;
import com.meteorite.unsuspiciousblock.core.loottable.signature.LootResultSignature;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/** 沿发布的目录层级聚合物品，按签名去重。 */
public final class CatalogQueryIndex {
    private CatalogQueryIndex() {
    }

    /** 返回表及后代的展示条目，含运行时发现的物品。 */
    public static List<ItemDefinition> subtreeItems(Map<ResourceLocation, TableDefinition> tables,
                                                    ResourceLocation tableId) {
        return subtreeItems(tables, tableId, item -> true);
    }

    /** 获取表及后代因排除组件而折叠的物品。 */
    public static Set<ResourceLocation> foldedItems(Map<ResourceLocation, TableDefinition> tables,
            Map<ResourceLocation, Set<ResourceLocation>> folded, ResourceLocation tableId) {
        if (folded.isEmpty()) return Set.of();
        Set<ResourceLocation> items = new LinkedHashSet<>();
        Set<ResourceLocation> visited = new LinkedHashSet<>();
        ArrayDeque<ResourceLocation> queue = new ArrayDeque<>();
        queue.add(tableId);
        while (!queue.isEmpty()) {
            ResourceLocation id = queue.removeFirst();
            if (!visited.add(id)) continue;
            TableDefinition table = tables.get(id);
            if (table == null) continue;
            items.addAll(folded.getOrDefault(id, Set.of()));
            queue.addAll(table.childTables());
        }
        return items;
    }

    /** 返回表及后代的已声明签名，用于完成度和一次性奖励。 */
    public static List<ItemDefinition> completionItems(Map<ResourceLocation, TableDefinition> tables,
                                                       ResourceLocation tableId) {
        return subtreeItems(tables, tableId, item -> item.origins().contains(LootOriginKind.STATIC)
                || item.origins().contains(LootOriginKind.MOD_INTEGRATION));
    }

    private static List<ItemDefinition> subtreeItems(Map<ResourceLocation, TableDefinition> tables,
                                                     ResourceLocation tableId, Predicate<ItemDefinition> include) {
        Map<LootResultSignature, ItemDefinition> items = new LinkedHashMap<>();
        collect(tables, tableId, include, items, new LinkedHashSet<>());
        return List.copyOf(items.values());
    }

    private static void collect(Map<ResourceLocation, TableDefinition> tables, ResourceLocation tableId,
                                Predicate<ItemDefinition> include, Map<LootResultSignature, ItemDefinition> items,
                                Set<ResourceLocation> visited) {
        TableDefinition table = tables.get(tableId);
        if (table == null || !visited.add(tableId)) {
            return;
        }
        for (ItemDefinition item : table.items()) {
            if (include.test(item)) {
                items.putIfAbsent(item.signature(), item);
            }
        }
        for (ResourceLocation child : table.childTables()) {
            collect(tables, child, include, items, visited);
        }
    }
}
