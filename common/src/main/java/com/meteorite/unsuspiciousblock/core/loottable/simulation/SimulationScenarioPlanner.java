package com.meteorite.unsuspiciousblock.core.loottable.simulation;

import com.meteorite.unsuspiciousblock.core.loottable.analysis.ConditionIdentity;
import com.meteorite.unsuspiciousblock.core.loottable.analysis.ConditionTree;
import com.meteorite.unsuspiciousblock.core.loottable.analysis.ConditionTypeDescriptor;
import com.meteorite.unsuspiciousblock.core.loottable.analysis.LootConditionHandlers;
import com.meteorite.unsuspiciousblock.core.loottable.analysis.LootConditionInfo;
import com.meteorite.unsuspiciousblock.core.loottable.analysis.LootFunctionInfo;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.LootTableCatalog.ChildTableProbability;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.LootTableCatalog.ItemDefinition;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.LootTableCatalog.LootAcquisitionPath;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.LootTableCatalog.TableDefinition;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 从战利品表静态路径中提取可勾选的原子环境条件（{@link ConditionOption}），
 * 并按已校验勾选集合即时构建模拟执行场景；推荐搜索由 {@link RecommendationSolver} 负责。
 */
public final class SimulationScenarioPlanner {
    /**
     * 单表签发的可勾选环境条件上限（同时作为网络编解码边界）。
     */
    public static final int MAX_CONDITIONS = 256;
    /**
     * 基准场景的稳定键（未勾选任何条件时的默认输入）。
     */
    public static final String BASELINE_SCENARIO_KEY = "baseline";
    /**
     * 单张表的条件与函数节点访问预算，防止病态嵌套 JSON 拖垮构建。
     */
    private static final int EXPANSION_NODE_BUDGET = 4096;
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Set<ResourceLocation> WARNED_UNSTABLE_TYPES = new LinkedHashSet<>();
    private static final Set<ResourceLocation> WARNED_BUDGET_TABLES = new LinkedHashSet<>();

    private SimulationScenarioPlanner() {
    }

    /**
     * 判断条件类型是否属于需要由场景勾选控制的类型；其余类型在模拟中按真实逻辑求值。
     */
    public static boolean isScenarioControlled(ResourceLocation conditionType) {
        return LootConditionHandlers.supports(conditionType, ConditionTypeDescriptor.Capability.SCENARIO);
    }

    /**
     * 条件提取结果。
     *
     * @param conditions      该表子树中去重保序的原子可勾选环境条件
     * @param budgetExhausted 语义分析不完整或超出节点/数量预算；服务端应停用该表数值模拟
     */
    public record ConditionPlan(List<ConditionOption> conditions, boolean budgetExhausted) {
        public ConditionPlan {
            conditions = List.copyOf(conditions);
        }
    }

    /**
     * 线性扫描整张表的获取路径、函数条件与子表入口条件，提取所有可勾选原子环境条件。
     */
    public static ConditionPlan planConditions(ResourceLocation tableId, TableDefinition table) {
        Map<String, ConditionOption> collected = new LinkedHashMap<>();
        NodeBudget budget = new NodeBudget();

        for (ItemDefinition item : table.items()) {
            for (LootAcquisitionPath path : item.acquisitionPaths()) {
                collectConditions(path.allConditions(), collected, budget);
                collectFunctions(path.functions(), collected, budget);
                if (budget.exhausted) {
                    break;
                }
            }
            if (budget.exhausted) {
                break;
            }
        }
        if (!budget.exhausted) {
            for (ChildTableProbability child : table.childTableProbabilities()) {
                collectConditions(child.conditions(), collected, budget);
                if (budget.exhausted) {
                    break;
                }
            }
        }
        if (collected.size() > MAX_CONDITIONS) {
            budget.exhausted = true;
        }
        if (budget.exhausted && WARNED_BUDGET_TABLES.add(tableId)) {
            LOGGER.warn("战利品表 {} 的条件分析超出预算（节点上限 {}，条件上限 {}）或函数语义不完整；该表将停止数值模拟",
                    tableId, EXPANSION_NODE_BUDGET, MAX_CONDITIONS);
        }
        List<ConditionOption> result = collected.size() > MAX_CONDITIONS
                ? List.copyOf(new ArrayList<>(collected.values()).subList(0, MAX_CONDITIONS))
                : List.copyOf(collected.values());
        return new ConditionPlan(result, budget.exhausted);
    }

    /**
     * 根据已校验的条件勾选集合，即时构建供模拟器使用的 {@link SimulationScenario}。
     */
    public static SimulationScenario buildScenario(String scenarioKey,
                                                   List<String> enabledConditions,
                                                   List<ConditionOption> issuedConditions,
                                                   TableDefinition table,
                                                   SimulationProfile baseProfile) {
        Set<String> enabledSet = Set.copyOf(enabledConditions);
        Map<ConditionIdentity, Boolean> outcomes = new LinkedHashMap<>(issuedConditions.size());
        List<LootConditionInfo> assumptions = new ArrayList<>();
        for (ConditionOption option : issuedConditions) {
            boolean enabled = enabledSet.contains(option.id());
            outcomes.put(option.condition().identity(), enabled);
            if (enabled) {
                assumptions.add(option.condition());
            }
        }

        Set<String> applicableSignatures = new LinkedHashSet<>();
        Set<ResourceLocation> applicableChildren = new LinkedHashSet<>();
        Set<ResourceLocation> childrenWithStaticPath = new LinkedHashSet<>();

        for (ItemDefinition item : table.items()) {
            String storedKey = item.signature().toStoredKey();
            if (item.acquisitionPaths().isEmpty()) {
                applicableSignatures.add(storedKey);
                continue;
            }
            for (LootAcquisitionPath path : item.acquisitionPaths()) {
                if (path.sourceChildTable() != null) {
                    childrenWithStaticPath.add(path.sourceChildTable());
                }
                if (ScenarioConditionEvaluator.areScenarioConditionsSatisfied(path.allConditions(), enabledSet)) {
                    applicableSignatures.add(storedKey);
                    if (path.sourceChildTable() != null) {
                        applicableChildren.add(path.sourceChildTable());
                    }
                }
            }
        }
        for (ResourceLocation childTable : table.childTables()) {
            if (!childrenWithStaticPath.contains(childTable)) {
                applicableChildren.add(childTable);
            }
        }
        return new SimulationScenario(
                scenarioKey,
                baseProfile.withConditionOutcomes(outcomes, Map.of()),
                assumptions,
                applicableSignatures,
                applicableChildren,
                enabledSet.isEmpty());
    }

    private static void collectConditions(List<LootConditionInfo> conditions,
                                          Map<String, ConditionOption> collected,
                                          NodeBudget budget) {
        for (LootConditionInfo condition : conditions) {
            if (budget.exhausted) {
                return;
            }
            ConditionTree.walkLogic(condition, node -> {
                if (budget.exhausted || budget.exceedsNodeBudget()) {
                    return;
                }
                if (ConditionTree.kind(node) != ConditionTree.Kind.LEAF) {
                    return;
                }
                if (!isScenarioControlled(node.conditionType())) {
                    return;
                }
                if (!ConditionIdentity.isStable(node)) {
                    warnUnstableFingerprint(node.conditionType());
                    return;
                }
                String key = ConditionIdentity.of(node).key();
                collected.putIfAbsent(key, new ConditionOption(key, node));
            });
        }
    }

    private static void collectFunctions(List<LootFunctionInfo> functions,
                                         Map<String, ConditionOption> collected,
                                         NodeBudget budget) {
        for (LootFunctionInfo function : functions) {
            if (budget.exhausted || budget.exceedsNodeBudget()) {
                return;
            }
            if (function.metadata().containsKey(LootFunctionInfo.METADATA_ANALYSIS_INCOMPLETE)) {
                budget.exhausted = true;
                return;
            }
            collectConditions(function.conditions(), collected, budget);
            collectFunctions(function.children(), collected, budget);
        }
    }

    private static void warnUnstableFingerprint(ResourceLocation conditionType) {
        if (WARNED_UNSTABLE_TYPES.add(conditionType)) {
            LOGGER.warn("战利品条件 {} 的指纹不稳定（未实现确定性编码），无法参与条件勾选控制，"
                    + "该条件在模拟中执行真实逻辑", conditionType);
        }
    }

    private static final class NodeBudget {
        private int nodes;
        private boolean exhausted;

        boolean exceedsNodeBudget() {
            if (++this.nodes > EXPANSION_NODE_BUDGET) {
                this.exhausted = true;
                return true;
            }
            return false;
        }
    }
}
