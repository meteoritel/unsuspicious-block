package com.meteorite.unsuspiciousblock.loottable.simulation;

import com.meteorite.unsuspiciousblock.loottable.analysis.LootConditionInfo;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.ItemDefinition;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.LootAcquisitionPath;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.TableDefinition;
import com.mojang.logging.LogUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 从静态获取路径中提取会改变可用性的条件，并生成数量受控的代表模拟场景。
 * <p>
 * 三条关键约束：
 * <ul>
 *   <li><b>硬上界 32</b>（决策 14）：超出按"覆盖的获取路径数"降序截断，并把截断数量如实上报——
 *       旧实现的静默截断让玩家分不清"被丢弃"与"规则没解析"；</li>
 *   <li><b>展开本身也有预算</b>（决策 33）：{@code all_of} 走叉乘、{@code any_of} 累加，因此
 *       "all_of 里嵌多个 any_of"的条件树会在 32 的截断**之前**就指数膨胀。场景上限约束的是展开的
 *       <i>结果数量</i>，约束不了展开的<i>过程成本</i>，所以两者各有一个独立预算；超预算的路径按
 *       "无约束"降级（与指纹不稳定时的既有降级同型：数值偏保守，但不会把条目伪装成确定不可达）；</li>
 *   <li><b>基准场景必须在截断之前产出</b>：它是"条件全部不成立"的那个赋值（决策 2），
 *       网格上的数字只来自它。按覆盖度排序会让它排到末尾，因此先产出它再排其余。</li>
 * </ul>
 * {@code match_tool} **不在**场景控制类型内（决策 8）：工具由 profile 填充的真实
 * {@code TOOL} 求值，布尔维度从 8 类降到 7 类。此前它被伪造成布尔，于是"工具匹配"与
 * "时运等级"互不相干，基础场景的时运曲线恒为 0 级。
 * <p>
 * <b>场景键是稳定身份，不编码条件指纹</b>：基准恒为 {@link #BASELINE_SCENARIO_KEY}，其余按本方法确定的
 * 发射顺序编号为 {@code scene-N}。条件指纹跨 JVM 运行可能不重复（见
 * {@link SimulationInput#scenarioKey()}），把它写进键会让同一个场景每次启动换一个键：缓存永不命中，
 * 旧键又因为 LRU 按参数组合计数而永久留在存档里。因此"发射顺序必须可复现"是本类的硬要求——
 * 它同时依赖"候选集合按路径枚举顺序插入"与"排序稳定且不按不稳定文本破并列"两点。
 */
public final class SimulationScenarioPlanner {
    /** 场景硬上界（决策 14）。 */
    public static final int MAX_SCENARIOS = 32;
    /**
     * 基准场景的稳定键（决策 2）。
     * <p>
     * 固定字面量而非"条件赋值的编码"：它是网格数字的唯一来源，也是缓存键里最该可读的一段。
     * 无场景表的兜底输入（{@link SimulationConstraintCatalog#baselineInput()}）复用它，因此
     * "基准身份"全局只有一个写法。
     */
    public static final String BASELINE_SCENARIO_KEY = "baseline";
    /** 其余场景的稳定键前缀，序号即发射顺序（1 起，基准不占序号）。 */
    private static final String SCENARIO_KEY_PREFIX = "scene-";
    private static final ResourceLocation INVERTED = ResourceLocation.withDefaultNamespace("inverted");
    private static final ResourceLocation ANY_OF = ResourceLocation.withDefaultNamespace("any_of");
    private static final ResourceLocation ALL_OF = ResourceLocation.withDefaultNamespace("all_of");
    /** 单张表的条件树展开预算：访问的条件节点总数。 */
    private static final int EXPANSION_NODE_BUDGET = 4096;
    /** 单张表的条件树展开预算：中间组合集合的最大规模。 */
    private static final int EXPANSION_COMBINATION_BUDGET = 512;
    // 工具附魔条件不参与场景覆盖：概率的等级曲线无法在布尔场景假设里表达，表中的它按真实 test() 求值。
    // 等级是运行时输入而非条件谓词（决策 26），基准场景用无附魔工具，因此受它约束的条目在基准下
    // 自然落到"需要条件"，而不是被伪造成某个具体等级下的数字。
    private static final Set<ResourceLocation> SCENARIO_CONDITIONS = Set.of(
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
    /** 已告警过展开超预算的表，避免按场景刷屏。 */
    private static final Set<ResourceLocation> WARNED_BUDGET_TABLES = new LinkedHashSet<>();

    private SimulationScenarioPlanner() {
    }

    // 判断条件类型是否属于需要由代表场景控制的类型；其余类型在模拟中按真实逻辑求值
    public static boolean isScenarioControlled(ResourceLocation conditionType) {
        return SCENARIO_CONDITIONS.contains(conditionType);
    }

    /**
     * 一轮场景规划的产物。
     *
     * @param scenarios      代表场景（含基准），数量不超过 {@link #MAX_SCENARIOS}
     * @param truncatedCount 因超出上界而未列出的场景数；{@code 0} 表示全部列出
     * @param budgetExhausted 是否有路径因展开预算耗尽而按"无约束"降级
     */
    public record ScenarioPlan(List<SimulationScenario> scenarios, int truncatedCount,
                               boolean budgetExhausted) {
        public ScenarioPlan {
            scenarios = List.copyOf(scenarios);
        }

        /** 不做截断、不超预算的规划结果。 */
        public static ScenarioPlan of(List<SimulationScenario> scenarios) {
            return new ScenarioPlan(scenarios, 0, false);
        }
    }

    // 每条获取路径形成一个最小场景，避免对所有条件做无界笛卡尔积
    public static ScenarioPlan plan(ResourceLocation tableId, TableDefinition table, ServerLevel level) {
        SimulationProfile baseProfile = SimulationProfile.eligibleConditions(level, table.type());
        Map<String, LootConditionInfo> conditionByFingerprint = new LinkedHashMap<>();
        ExpansionBudget budget = new ExpansionBudget();

        // 每条路径的需求集合只算一次，供"候选收集""覆盖度排序""适用性判定"三处共用——
        // 旧实现在每个场景上为每个条目重算一遍，是同一份工作的 O(场景数 × 路径数) 次重复。
        List<PathRequirements> pathRequirements = collectPathRequirements(table, conditionByFingerprint, budget);

        Map<String, Map<String, Boolean>> candidates = new LinkedHashMap<>();
        candidates.put("default", Map.of());
        Set<String> allFingerprints = new LinkedHashSet<>();
        for (PathRequirements path : pathRequirements) {
            for (Map<String, Boolean> requirement : path.requirements()) {
                if (!requirement.isEmpty()) {
                    allFingerprints.addAll(requirement.keySet());
                    candidates.putIfAbsent(canonical(requirement), requirement);
                }
            }
        }

        Map<String, Map<String, Boolean>> normalizedByKey = new LinkedHashMap<>();
        for (Map<String, Boolean> candidate : candidates.values()) {
            Map<String, Boolean> normalized = new LinkedHashMap<>();
            for (String fingerprint : allFingerprints) {
                normalized.put(fingerprint, candidate.getOrDefault(fingerprint, false));
            }
            normalizedByKey.putIfAbsent(canonical(normalized), normalized);
        }


        List<SimulationScenario> result = new ArrayList<>();
        // 基准先占名额：它由全假赋值产生，覆盖度天然最低，排序后会第一个被丢掉
        Map<String, Boolean> baselineOutcomes = normalizedByKey.remove(canonical(allFalse(allFingerprints)));
        if (baselineOutcomes == null) {
            baselineOutcomes = allFalse(allFingerprints);
        }
        result.add(buildScenario(BASELINE_SCENARIO_KEY, baselineOutcomes, table, pathRequirements,
                conditionByFingerprint, baseProfile, true));

        // 其余场景按覆盖路径数降序，**刻意不做二次排序**：并列时的先后由 normalizedByKey 的插入顺序
        // 决定，而插入顺序来自"路径枚举顺序"，与表内容一一对应且可复现（Java 的排序是稳定的，相等元素
        // 保持原有次序）。曾经这里按指纹字符串破并列，而指纹跨运行不稳定，于是并列候选每次启动互换
        // 序号——序号即缓存键，带并列场景的表因此每次启动都全量重算。
        List<Map<String, Boolean>> others = new ArrayList<>(normalizedByKey.values());
        others.sort(Comparator
                .comparingInt((Map<String, Boolean> normalized) ->
                        coverage(normalized, pathRequirements))
                .reversed());

        int ordinal = 1;
        int truncated = 0;
        for (Map<String, Boolean> normalized : others) {
            if (result.size() >= MAX_SCENARIOS) {
                truncated++;
                continue;
            }
            result.add(buildScenario(SCENARIO_KEY_PREFIX + ordinal++, normalized, table,
                    pathRequirements, conditionByFingerprint, baseProfile, false));
        }

        if (budget.exhausted && WARNED_BUDGET_TABLES.add(tableId)) {
            LOGGER.warn("战利品表 {} 的条件树展开超出预算（节点上限 {}，组合上限 {}），"
                            + "超出的路径已按无约束处理；数值偏保守，但不会把条目伪装成确定不可达",
                    tableId, EXPANSION_NODE_BUDGET, EXPANSION_COMBINATION_BUDGET);
        }
        return new ScenarioPlan(result, truncated, budget.exhausted);
    }

    /** 一条获取路径预计算出的需求集合，以及它归属的物品签名与直接子表。 */
    private record PathRequirements(String storedKey, @Nullable ResourceLocation sourceChildTable,
                                    List<Map<String, Boolean>> requirements) {
        // 该路径的需求是否被当前布尔赋值满足；需求集合为空即"条件自相矛盾"，任何场景都不满足
        boolean satisfiedBy(Map<String, Boolean> scenario) {
            for (Map<String, Boolean> requirement : this.requirements) {
                if (scenario.entrySet().containsAll(requirement.entrySet())) {
                    return true;
                }
            }
            return false;
        }
    }

    private static List<PathRequirements> collectPathRequirements(
            TableDefinition table, Map<String, LootConditionInfo> conditionByFingerprint,
            ExpansionBudget budget) {
        List<PathRequirements> result = new ArrayList<>();
        for (ItemDefinition item : table.items()) {
            for (LootAcquisitionPath path : item.acquisitionPaths()) {
                result.add(new PathRequirements(item.signature().toStoredKey(), path.sourceChildTable(),
                        requirementsFor(path.allConditions(), conditionByFingerprint, budget)));
            }
        }
        return List.copyOf(result);
    }

    private static SimulationScenario buildScenario(String key, Map<String, Boolean> normalized,
                                                    TableDefinition table,
                                                    List<PathRequirements> pathRequirements,
                                                    Map<String, LootConditionInfo> conditionByFingerprint,
                                                    SimulationProfile baseProfile, boolean baseline) {
        Set<String> applicable = new LinkedHashSet<>();
        Set<ResourceLocation> applicableChildren = new LinkedHashSet<>();
        for (ItemDefinition item : table.items()) {
            // 无静态获取路径的条目（动态条目、或路径为空）保守视为适用
            if (item.acquisitionPaths().isEmpty()) {
                applicable.add(item.signature().toStoredKey());
            }
        }
        for (PathRequirements path : pathRequirements) {
            if (!path.satisfiedBy(normalized)) {
                continue;
            }
            applicable.add(path.storedKey());
            if (path.sourceChildTable() != null) {
                applicableChildren.add(path.sourceChildTable());
            }
        }
        // 没有静态路径的子表入口（由运行时注入等产生）保守保留，避免"没观测到"被当成"不可达"
        for (ResourceLocation childTable : table.childTables()) {
            boolean hasKnownPath = false;
            for (PathRequirements path : pathRequirements) {
                if (childTable.equals(path.sourceChildTable())) {
                    hasKnownPath = true;
                    break;
                }
            }
            if (!hasKnownPath) {
                applicableChildren.add(childTable);
            }
        }
        List<LootConditionInfo> assumptions = describe(normalized, conditionByFingerprint);
        return new SimulationScenario(key, baseProfile.withConditionOutcomes(normalized, Map.of()),
                assumptions, applicable, applicableChildren, baseline);
    }

    // 该布尔赋值覆盖的获取路径数——截断按它降序，让"能解释更多条目"的场景优先留下
    private static int coverage(Map<String, Boolean> scenario, List<PathRequirements> pathRequirements) {
        int count = 0;
        for (PathRequirements path : pathRequirements) {
            if (path.satisfiedBy(scenario)) {
                count++;
            }
        }
        return count;
    }

    private static Map<String, Boolean> allFalse(Set<String> fingerprints) {
        Map<String, Boolean> result = new LinkedHashMap<>();
        for (String fingerprint : fingerprints) {
            result.put(fingerprint, false);
        }
        return result;
    }

    private static List<Map<String, Boolean>> requirementsFor(
            List<LootConditionInfo> conditions, Map<String, LootConditionInfo> conditionByFingerprint,
            ExpansionBudget budget) {
        List<Map<String, Boolean>> result = List.of(Map.of());
        for (LootConditionInfo condition : conditions) {
            result = combineAnd(result, requirementsFor(condition, false, conditionByFingerprint, budget));
            if (result.isEmpty() || budget.exhausted) {
                break;
            }
        }
        if (budget.exhausted) {
            return List.of(Map.of());
        }
        return result;
    }

    private static List<Map<String, Boolean>> requirementsFor(
            LootConditionInfo condition, boolean negated,
            Map<String, LootConditionInfo> conditionByFingerprint, ExpansionBudget budget) {
        if (budget.exhausted || !budget.visitNode()) {
            return List.of(Map.of());
        }
        ResourceLocation type = condition.conditionType();
        if (INVERTED.equals(type) && !condition.children().isEmpty()) {
            return requirementsFor(condition.children().getFirst(), !negated, conditionByFingerprint, budget);
        }
        if (ALL_OF.equals(type) || ANY_OF.equals(type)) {
            boolean useAnd = ALL_OF.equals(type) != negated;
            List<Map<String, Boolean>> result = useAnd ? List.of(Map.of()) : new ArrayList<>();
            for (LootConditionInfo child : condition.children()) {
                List<Map<String, Boolean>> childRequirements =
                        requirementsFor(child, negated, conditionByFingerprint, budget);
                if (budget.exhausted) {
                    return List.of(Map.of());
                }
                if (useAnd) {
                    result = combineAnd(result, childRequirements);
                } else {
                    result.addAll(childRequirements);
                }
                // 叉乘在这里指数膨胀，超限即整体降级为"无约束"（决策 33）
                if (result.size() > EXPANSION_COMBINATION_BUDGET) {
                    return budget.markExhausted();
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

    /**
     * 条件树展开的预算计数器。
     * <p>
     * {@code exhausted} 一旦置位就**不可恢复**：后续所有展开一律返回"无约束"。这是刻意的——
     * 只丢弃超限的那一支会让"哪些路径被降级"取决于遍历顺序，而按无约束处理是保守方向，
     * 至少不会把可达条目伪装成不可达。
     */
    private static final class ExpansionBudget {
        private int nodes;
        private boolean exhausted;

        // 访问一个条件节点；返回 false 表示已超节点预算
        boolean visitNode() {
            if (++this.nodes > EXPANSION_NODE_BUDGET) {
                this.exhausted = true;
                return false;
            }
            return true;
        }

        List<Map<String, Boolean>> markExhausted() {
            this.exhausted = true;
            return List.of(Map.of());
        }
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
                if (result.size() > EXPANSION_COMBINATION_BUDGET) {
                    return result;
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

    /**
     * 条件布尔赋值的规范编码——**只用于本类内部去重候选**。
     * <p>
     * 它编码的是条件指纹，而指纹跨 JVM 运行可能不重复（见 {@link SimulationInput#scenarioKey()}），
     * 因此**不得**用它生成任何持久化键或网络标识：那会让同一个场景每次启动换一个键。
     * 空赋值编码为 {@code "default"}（"条件全部不成立"的那个赋值），仅用于让基准候选与
     * {@code candidates} 里的初始空候选去重到同一项。
     */
    private static String canonical(Map<String, Boolean> requirements) {
        if (requirements.isEmpty()) {
            return "default";
        }
        Map<String, Boolean> sorted = new TreeMap<>(requirements);
        StringBuilder builder = new StringBuilder(sorted.size() * 24);
        boolean first = true;
        for (Map.Entry<String, Boolean> entry : sorted.entrySet()) {
            if (!first) {
                builder.append(';');
            }
            first = false;
            builder.append(entry.getKey()).append('=').append(entry.getValue() ? '1' : '0');
        }
        return builder.toString();
    }
}
