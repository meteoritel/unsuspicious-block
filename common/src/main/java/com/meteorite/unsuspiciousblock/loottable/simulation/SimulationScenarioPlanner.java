package com.meteorite.unsuspiciousblock.loottable.simulation;

import com.meteorite.unsuspiciousblock.loottable.analysis.LootConditionInfo;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.ItemDefinition;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.LootAcquisitionPath;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.TableDefinition;
import com.meteorite.unsuspiciousblock.enchantment.ModEnchantments;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.ItemEnchantments;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 从静态获取路径中提取会改变可用性的条件，并生成数量受控的自洽模拟场景。
 */
public final class SimulationScenarioPlanner {
    private static final int MAX_SCENARIOS = 8;
    private static final ResourceLocation FISHING = ResourceLocation.withDefaultNamespace("gameplay/fishing");
    private static final ResourceLocation LOCATION_CHECK = ResourceLocation.withDefaultNamespace("location_check");
    private static final ResourceLocation MUD_DREDGING =
            ResourceLocation.fromNamespaceAndPath("unsuspiciousblock", "mud_dredging");
    private static final ResourceLocation MUD_DREDGING_TABLE =
            ResourceLocation.fromNamespaceAndPath("unsuspiciousblock", "gameplay/fishing/mud_dredging");
    private static final ResourceLocation INVERTED = ResourceLocation.withDefaultNamespace("inverted");
    private static final ResourceLocation ANY_OF = ResourceLocation.withDefaultNamespace("any_of");
    private static final ResourceLocation ALL_OF = ResourceLocation.withDefaultNamespace("all_of");
    private static final Set<ResourceLocation> SCENARIO_CONDITIONS = Set.of(
            ResourceLocation.withDefaultNamespace("match_tool"),
            ResourceLocation.withDefaultNamespace("block_state_property"),
            ResourceLocation.withDefaultNamespace("damage_source_properties"),
            ResourceLocation.withDefaultNamespace("entity_properties"),
            ResourceLocation.withDefaultNamespace("location_check"),
            ResourceLocation.withDefaultNamespace("weather_check"),
            ResourceLocation.withDefaultNamespace("time_check"),
            ResourceLocation.withDefaultNamespace("entity_scores"),
            ResourceLocation.fromNamespaceAndPath("unsuspiciousblock", "mud_dredging"));

    private SimulationScenarioPlanner() {
    }

    // 每条获取路径形成一个最小场景，避免对所有条件做无界笛卡尔积
    public static List<SimulationScenario> plan(ResourceLocation tableId, TableDefinition table,
                                                ServerLevel level) {
        SimulationProfile baseProfile = SimulationProfile.eligibleConditions(level, tableId);
        Map<String, LootConditionInfo> conditionByFingerprint = new LinkedHashMap<>();
        Map<String, Map<String, Boolean>> candidates = new LinkedHashMap<>();
        candidates.put("default", Map.of());
        Set<String> allFingerprints = new LinkedHashSet<>();

        for (ItemDefinition item : table.items()) {
            for (LootAcquisitionPath path : item.acquisitionPaths()) {
                for (Map<String, Boolean> requirement : requirementsFor(path.allConditions(), conditionByFingerprint)) {
                    if (!requirement.isEmpty()) {
                        allFingerprints.addAll(requirement.keySet());
                        candidates.putIfAbsent(canonical(requirement), requirement);
                    }
                }
            }
        }
        List<SimulationScenario> result = new ArrayList<>();
        Set<String> emittedKeys = new LinkedHashSet<>();
        for (Map<String, Boolean> candidate : candidates.values()) {
            if (result.size() >= MAX_SCENARIOS) {
                break;
            }
            Map<String, Boolean> normalized = new LinkedHashMap<>();
            for (String fingerprint : allFingerprints) {
                normalized.put(fingerprint, candidate.getOrDefault(fingerprint, false));
            }
            Set<String> applicable = new LinkedHashSet<>();
            for (ItemDefinition item : table.items()) {
                if (isApplicable(item, normalized, conditionByFingerprint)) {
                    applicable.add(item.signature().toStoredKey());
                }
            }
            List<LootConditionInfo> assumptions = describe(normalized, conditionByFingerprint);
            String key = normalized.isEmpty() ? "default" : canonical(normalized);
            if (!emittedKeys.add(key)) {
                continue;
            }
            result.add(new SimulationScenario(key,
                    baseProfile.withConditionOutcomes(normalized, Map.of()), assumptions, applicable));
        }
        if (MUD_DREDGING_TABLE.equals(tableId)) {
            result = expandMudDredgingTableScenarios(result, level);
        } else if (FISHING.equals(tableId)) {
            appendMudDredgingScenarios(result, baseProfile, level);
        }
        return List.copyOf(result);
    }

    // 父表自身的群系分支各按附魔 I/II/III 独立模拟
    private static List<SimulationScenario> expandMudDredgingTableScenarios(
            List<SimulationScenario> baseScenarios, ServerLevel level) {
        List<SimulationScenario> result = new ArrayList<>();
        for (SimulationScenario base : baseScenarios) {
            for (int enchantmentLevel = 1; enchantmentLevel <= 3; enchantmentLevel++) {
                ItemStack tool = mudDredgingTool(level, enchantmentLevel);
                List<LootConditionInfo> assumptions = new ArrayList<>(base.assumptions());
                assumptions.addFirst(new LootConditionInfo(MUD_DREDGING, Component.translatable(
                        "screen.unsuspiciousblock.archaeology_journal.condition.mud_dredging_level",
                        enchantmentLevel), null));
                result.add(new SimulationScenario(base.key() + ";level=" + enchantmentLevel,
                        base.profile().withTool(tool), assumptions, base.applicableSignatures()));
            }
        }
        return result;
    }

    // 原始 fishing JSON 看不到平台注入池，显式补充三档附魔乘两个群系分支
    private static void appendMudDredgingScenarios(List<SimulationScenario> result,
                                                   SimulationProfile baseProfile,
                                                   ServerLevel level) {
        for (int enchantmentLevel = 1; enchantmentLevel <= 3; enchantmentLevel++) {
            ItemStack tool = mudDredgingTool(level, enchantmentLevel);
            for (boolean swamp : List.of(false, true)) {
                Map<ResourceLocation, Boolean> defaults = Map.of(LOCATION_CHECK, swamp);
                SimulationProfile profile = baseProfile.withTool(tool).withConditionOutcomes(
                        baseProfile.conditionOutcomes(), defaults);
                List<LootConditionInfo> assumptions = List.of(
                        new LootConditionInfo(MUD_DREDGING, Component.translatable(
                                "screen.unsuspiciousblock.archaeology_journal.condition.mud_dredging_level",
                                enchantmentLevel), null),
                        new LootConditionInfo(LOCATION_CHECK, Component.translatable(
                                "screen.unsuspiciousblock.archaeology_journal.condition."
                                        + (swamp ? "mud_dredging_bonus_biome" : "mud_dredging_normal_biome")),
                                null));
                result.add(new SimulationScenario(
                        "mud_dredging:level=" + enchantmentLevel + ";swamp=" + (swamp ? 1 : 0),
                        profile, assumptions, Set.of()));
            }
        }
    }

    private static ItemStack mudDredgingTool(ServerLevel level, int enchantmentLevel) {
        var enchantment = level.holderLookup(Registries.ENCHANTMENT)
                .getOrThrow(ModEnchantments.MUD_DREDGING);
        ItemStack tool = new ItemStack(Items.FISHING_ROD);
        ItemEnchantments.Mutable enchantments = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
        enchantments.set(enchantment, enchantmentLevel);
        tool.set(DataComponents.ENCHANTMENTS, enchantments.toImmutable());
        return tool;
    }

    private static boolean isApplicable(ItemDefinition item, Map<String, Boolean> scenario,
                                        Map<String, LootConditionInfo> conditionByFingerprint) {
        if (item.acquisitionPaths().isEmpty()) {
            return true;
        }
        for (LootAcquisitionPath path : item.acquisitionPaths()) {
            for (Map<String, Boolean> requirement : requirementsFor(path.allConditions(), conditionByFingerprint)) {
                if (scenario.entrySet().containsAll(requirement.entrySet())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static List<Map<String, Boolean>> requirementsFor(
            List<LootConditionInfo> conditions, Map<String, LootConditionInfo> conditionByFingerprint) {
        List<Map<String, Boolean>> result = List.of(Map.of());
        for (LootConditionInfo condition : conditions) {
            result = combineAnd(result, requirementsFor(condition, false, conditionByFingerprint));
            if (result.isEmpty()) {
                break;
            }
        }
        return result;
    }

    private static List<Map<String, Boolean>> requirementsFor(
            LootConditionInfo condition, boolean negated,
            Map<String, LootConditionInfo> conditionByFingerprint) {
        ResourceLocation type = condition.conditionType();
        if (INVERTED.equals(type) && !condition.children().isEmpty()) {
            return requirementsFor(condition.children().getFirst(), !negated, conditionByFingerprint);
        }
        if (ALL_OF.equals(type) || ANY_OF.equals(type)) {
            boolean useAnd = ALL_OF.equals(type) != negated;
            List<Map<String, Boolean>> result = useAnd ? List.of(Map.of()) : new ArrayList<>();
            for (LootConditionInfo child : condition.children()) {
                List<Map<String, Boolean>> childRequirements =
                        requirementsFor(child, negated, conditionByFingerprint);
                if (useAnd) {
                    result = combineAnd(result, childRequirements);
                } else {
                    result.addAll(childRequirements);
                }
            }
            return deduplicate(result);
        }
        if (!SCENARIO_CONDITIONS.contains(type)) {
            return List.of(Map.of());
        }
        String fingerprint = LootConditionFingerprint.of(condition);
        conditionByFingerprint.putIfAbsent(fingerprint, condition);
        return List.of(Map.of(fingerprint, !negated));
    }

    private static List<Map<String, Boolean>> combineAnd(List<Map<String, Boolean>> left,
                                                         List<Map<String, Boolean>> right) {
        List<Map<String, Boolean>> result = new ArrayList<>();
        for (Map<String, Boolean> first : left) {
            for (Map<String, Boolean> second : right) {
                Map<String, Boolean> merged = new LinkedHashMap<>(first);
                boolean compatible = true;
                for (Map.Entry<String, Boolean> entry : second.entrySet()) {
                    Boolean existing = merged.putIfAbsent(entry.getKey(), entry.getValue());
                    if (existing != null && !existing.equals(entry.getValue())) {
                        compatible = false;
                        break;
                    }
                }
                if (compatible) {
                    result.add(merged);
                }
            }
        }
        return deduplicate(result);
    }

    private static List<Map<String, Boolean>> deduplicate(List<Map<String, Boolean>> values) {
        Map<String, Map<String, Boolean>> unique = new LinkedHashMap<>();
        for (Map<String, Boolean> value : values) {
            unique.putIfAbsent(canonical(value), value);
        }
        return new ArrayList<>(unique.values());
    }

    private static List<LootConditionInfo> describe(Map<String, Boolean> outcomes,
                                                    Map<String, LootConditionInfo> conditions) {
        List<LootConditionInfo> result = new ArrayList<>();
        for (Map.Entry<String, Boolean> entry : outcomes.entrySet()) {
            LootConditionInfo condition = conditions.get(entry.getKey());
            if (condition == null) {
                continue;
            }
            if (entry.getValue()) {
                result.add(condition);
            } else {
                result.add(new LootConditionInfo(INVERTED,
                        Component.translatable(
                                "screen.unsuspiciousblock.archaeology_journal.condition.inverted",
                                condition.description()), null, List.of(condition)));
            }
        }
        return List.copyOf(result);
    }

    private static String canonical(Map<String, Boolean> requirements) {
        if (requirements.isEmpty()) {
            return "default";
        }
        return requirements.entrySet().stream()
                .sorted(Comparator.comparing(Map.Entry::getKey))
                .map(entry -> entry.getKey() + '=' + (entry.getValue() ? '1' : '0'))
                .reduce((left, right) -> left + ';' + right)
                .orElse("default");
    }
}
