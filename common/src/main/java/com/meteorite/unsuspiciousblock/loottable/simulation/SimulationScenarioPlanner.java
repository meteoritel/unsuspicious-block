package com.meteorite.unsuspiciousblock.loottable.simulation;

import com.meteorite.unsuspiciousblock.loottable.analysis.LootConditionInfo;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.ItemDefinition;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.LootAcquisitionPath;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.TableDefinition;
import com.meteorite.unsuspiciousblock.loottable.condition.ModLootConditions;
import com.meteorite.unsuspiciousblock.loottable.graph.RuntimeLootLinks;
import com.meteorite.unsuspiciousblock.enchantment.ModEnchantments;
import com.mojang.logging.LogUtils;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 从静态获取路径中提取会改变可用性的条件，并生成数量受控的代表模拟场景。
 */
public final class SimulationScenarioPlanner {
    private static final int MAX_SCENARIOS = 8;
    private static final int FISHING_MUD_DREDGING_SCENARIOS = 2;
    private static final ResourceLocation FISHING = RuntimeLootLinks.FISHING_TABLE;
    private static final ResourceLocation LOCATION_CHECK = ResourceLocation.withDefaultNamespace("location_check");
    private static final ResourceLocation MUD_DREDGING_TABLE = RuntimeLootLinks.MUD_DREDGING_TABLE;
    private static final ResourceLocation INVERTED = ResourceLocation.withDefaultNamespace("inverted");
    private static final ResourceLocation ANY_OF = ResourceLocation.withDefaultNamespace("any_of");
    private static final ResourceLocation ALL_OF = ResourceLocation.withDefaultNamespace("all_of");
    // 工具附魔条件不参与场景覆盖：概率的等级曲线无法在布尔场景假设里表达，表中的它按真实 test() 求值，
    // 等级维度另由泥地打捞路径统一压成满级单点（见 applyMudDredgingTool）
    private static final Set<ResourceLocation> SCENARIO_CONDITIONS = Set.of(
            ResourceLocation.withDefaultNamespace("match_tool"),
            ResourceLocation.withDefaultNamespace("block_state_property"),
            ResourceLocation.withDefaultNamespace("damage_source_properties"),
            ResourceLocation.withDefaultNamespace("entity_properties"),
            ResourceLocation.withDefaultNamespace("location_check"),
            ResourceLocation.withDefaultNamespace("weather_check"),
            ResourceLocation.withDefaultNamespace("time_check"),
            ResourceLocation.withDefaultNamespace("entity_scores"));

    private static final Logger LOGGER = LogUtils.getLogger();
    /** 已告警过的指纹不稳定类型，避免按条目刷屏；仅在主线程的场景规划中访问。 */
    private static final Set<ResourceLocation> WARNED_UNSTABLE_TYPES = new LinkedHashSet<>();

    private SimulationScenarioPlanner() {
    }

    // 判断条件类型是否属于需要由代表场景控制的类型；其余类型在模拟中按真实逻辑求值
    public static boolean isScenarioControlled(ResourceLocation conditionType) {
        return SCENARIO_CONDITIONS.contains(conditionType);
    }

    // 每条获取路径形成一个最小场景，避免对所有条件做无界笛卡尔积
    public static List<SimulationScenario> plan(ResourceLocation tableId, TableDefinition table,
                                                ServerLevel level) {
        SimulationProfile baseProfile = SimulationProfile.eligibleConditions(level, table.type());
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
        int baseScenarioLimit = FISHING.equals(tableId)
                ? MAX_SCENARIOS - FISHING_MUD_DREDGING_SCENARIOS
                : MAX_SCENARIOS;
        for (Map<String, Boolean> candidate : candidates.values()) {
            if (result.size() >= baseScenarioLimit) {
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
            Set<ResourceLocation> applicableChildren = applicableChildTables(
                    table, normalized, conditionByFingerprint);
            List<LootConditionInfo> assumptions = describe(normalized, conditionByFingerprint);
            String key = normalized.isEmpty() ? "default" : canonical(normalized);
            if (!emittedKeys.add(key)) {
                continue;
            }
            result.add(new SimulationScenario(key,
                    baseProfile.withConditionOutcomes(normalized, Map.of()), assumptions,
                    applicable, applicableChildren));
        }
        if (MUD_DREDGING_TABLE.equals(tableId)) {
            result = applyMudDredgingTool(result, level);
        } else if (FISHING.equals(tableId)) {
            appendMudDredgingScenarios(result, table, baseProfile, level);
        }
        return List.copyOf(result);
    }

    // 泥地打捞统一使用满级工具：附魔等级这一维压成单点，避免与环境条件形成笛卡尔积。
    // 满级取自附魔数据本身（max_level），数据包调整最大等级时模拟自动跟随。
    private static List<SimulationScenario> applyMudDredgingTool(
            List<SimulationScenario> baseScenarios, ServerLevel level) {
        List<SimulationScenario> result = new ArrayList<>();
        Holder<Enchantment> enchantment = mudDredgingEnchantment(level);
        int toolLevel = enchantment.value().definition().maxLevel();
        ItemStack tool = toolWithEnchantment(enchantment, toolLevel);
        for (SimulationScenario base : baseScenarios) {
            List<LootConditionInfo> assumptions = new ArrayList<>(base.assumptions());
            assumptions.addFirst(mudDredgingLevelAssumption(toolLevel));
            result.add(new SimulationScenario(base.key() + ";level=" + toolLevel,
                    base.profile().withTool(tool), assumptions, base.applicableSignatures(),
                    base.applicableChildTables()));
        }
        return result;
    }

    // 原始 fishing JSON 看不到平台注入池，使用满级附魔补充普通/加成群系两个代表场景。
    private static void appendMudDredgingScenarios(List<SimulationScenario> result, TableDefinition table,
                                                   SimulationProfile baseProfile,
                                                   ServerLevel level) {
        Holder<Enchantment> enchantment = mudDredgingEnchantment(level);
        int toolLevel = enchantment.value().definition().maxLevel();
        ItemStack tool = toolWithEnchantment(enchantment, toolLevel);
        Set<String> applicable = table.items().stream()
                .map(item -> item.signature().toStoredKey())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        for (boolean swamp : List.of(false, true)) {
            Map<ResourceLocation, Boolean> defaults = Map.of(LOCATION_CHECK, swamp);
            SimulationProfile profile = baseProfile.withTool(tool).withConditionOutcomes(
                    baseProfile.conditionOutcomes(), defaults);
            List<LootConditionInfo> assumptions = List.of(
                    mudDredgingLevelAssumption(toolLevel),
                    new LootConditionInfo(LOCATION_CHECK, Component.translatable(
                            "screen.unsuspiciousblock.archaeology_journal.condition."
                                    + (swamp ? "mud_dredging_bonus_biome" : "mud_dredging_normal_biome")),
                            null));
            result.add(new SimulationScenario(
                    "mud_dredging:level=" + toolLevel + ";swamp=" + (swamp ? 1 : 0),
                    profile, assumptions, applicable, Set.copyOf(table.childTables())));
        }
    }

    private static LootConditionInfo mudDredgingLevelAssumption(int toolLevel) {
        return new LootConditionInfo(ModLootConditions.TOOL_ENCHANTMENT, Component.translatable(
                "screen.unsuspiciousblock.archaeology_journal.condition.mud_dredging_level",
                toolLevel), null);
    }

    private static Holder<Enchantment> mudDredgingEnchantment(ServerLevel level) {
        return level.holderLookup(Registries.ENCHANTMENT).getOrThrow(ModEnchantments.MUD_DREDGING);
    }

    private static ItemStack toolWithEnchantment(Holder<Enchantment> enchantment, int enchantmentLevel) {
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

    // 仅在解析路径能够证明子表在当前场景不可达时排除；没有静态产物信息时保守保留。
    private static Set<ResourceLocation> applicableChildTables(
            TableDefinition table, Map<String, Boolean> scenario,
            Map<String, LootConditionInfo> conditionByFingerprint) {
        Set<ResourceLocation> result = new LinkedHashSet<>();
        for (ResourceLocation childTable : table.childTables()) {
            boolean hasKnownPath = false;
            boolean applicable = false;
            for (ItemDefinition item : table.items()) {
                for (LootAcquisitionPath path : item.acquisitionPaths()) {
                    if (!childTable.equals(path.sourceChildTable())) {
                        continue;
                    }
                    hasKnownPath = true;
                    for (Map<String, Boolean> requirement : requirementsFor(
                            path.allConditions(), conditionByFingerprint)) {
                        if (scenario.entrySet().containsAll(requirement.entrySet())) {
                            applicable = true;
                            break;
                        }
                    }
                    if (applicable) break;
                }
                if (applicable) break;
            }
            if (!hasKnownPath || applicable) {
                result.add(childTable);
            }
        }
        return result;
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
        // 指纹无法跨解析期/运行时复现时，建立场景只会产出运行时永远匹配不上的死键，
        // 反而把条目在其"自家场景"里也判成不适达。此处按无约束处理，让条目保留在
        // 所有代表场景中（数值偏保守，但不会伪装成确定值）。
        if (!LootConditionFingerprint.isStable(condition)) {
            warnUnstableFingerprint(condition.conditionType());
            return List.of(Map.of());
        }
        String fingerprint = LootConditionFingerprint.of(condition);
        conditionByFingerprint.putIfAbsent(fingerprint, condition);
        return List.of(Map.of(fingerprint, !negated));
    }

    // 每个类型只告警一次：该类型无法参与场景覆盖，需要改为 record 或覆写 toString
    private static void warnUnstableFingerprint(ResourceLocation conditionType) {
        if (WARNED_UNSTABLE_TYPES.add(conditionType)) {
            LOGGER.warn("战利品条件 {} 的指纹不稳定（toString 为默认实现），无法参与模拟场景规划，"
                    + "该条件已按无约束处理；如需纳管请将其实现为 record 或覆写 toString",
                    conditionType);
        }
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
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + '=' + (entry.getValue() ? '1' : '0'))
                .reduce((left, right) -> left + ';' + right)
                .orElse("default");
    }
}
