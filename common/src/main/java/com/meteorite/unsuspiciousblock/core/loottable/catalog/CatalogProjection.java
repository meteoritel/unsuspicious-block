package com.meteorite.unsuspiciousblock.core.loottable.catalog;

import com.meteorite.unsuspiciousblock.core.loottable.analysis.ConditionIdentity;
import com.meteorite.unsuspiciousblock.core.loottable.analysis.LootConditionInfo;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.LootTableCatalog.ItemDefinition;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.LootTableCatalog.LootAcquisitionPath;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.LootTableCatalog.TableDefinition;
import net.minecraft.resources.ResourceLocation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 路径条件的交集与合并，统一使用条件携带的语义或出处身份。 */
public final class CatalogProjection {
    private CatalogProjection() {}

    public static List<LootConditionInfo> commonChildConditions(TableDefinition parent, ResourceLocation child) {
        Map<ConditionIdentity, LootConditionInfo> common = null;
        for (ItemDefinition item : parent.items()) {
            for (LootAcquisitionPath path : item.acquisitionPaths()) {
                if (!child.equals(path.sourceChildTable())) continue;
                Map<ConditionIdentity, LootConditionInfo> indexed = indexConditions(path.allConditions());
                if (common == null) common = indexed;
                else common.keySet().retainAll(indexed.keySet());
            }
        }
        return common == null ? List.of() : List.copyOf(common.values());
    }

    public static List<LootConditionInfo> mergeConditions(List<LootConditionInfo> first,
                                                          List<LootConditionInfo> second) {
        Map<ConditionIdentity, LootConditionInfo> merged = indexConditions(first);
        second.forEach(condition -> merged.putIfAbsent(condition.identity(), condition));
        return List.copyOf(merged.values());
    }

    private static Map<ConditionIdentity, LootConditionInfo> indexConditions(List<LootConditionInfo> conditions) {
        Map<ConditionIdentity, LootConditionInfo> result = new LinkedHashMap<>();
        conditions.forEach(condition -> result.putIfAbsent(condition.identity(), condition));
        return result;
    }

}
