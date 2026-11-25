package com.meteorite.unsuspiciousblock.core.loottable.simulation;

import com.meteorite.unsuspiciousblock.core.loottable.analysis.ConditionIdentity;
import com.meteorite.unsuspiciousblock.core.loottable.analysis.ConditionSemantics;
import com.meteorite.unsuspiciousblock.core.loottable.analysis.ConditionTree;
import com.meteorite.unsuspiciousblock.core.loottable.analysis.ConditionTypeDescriptor;
import com.meteorite.unsuspiciousblock.core.loottable.analysis.LootConditionHandlers;
import com.meteorite.unsuspiciousblock.core.loottable.analysis.LootConditionInfo;
import com.meteorite.unsuspiciousblock.core.loottable.analysis.LuckGate;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.ScenarioBranch;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.ArrayList;
import java.util.Set;

/**
 * 场景与分支条件的共用三值求值器——前后端共用同一套逻辑树判定口径，避免服务端与 UI 漂移。
 */
public final class ScenarioConditionEvaluator {
    /**
     * 条件元数据键：要求具备的工具附魔注册名。
     */
    public static final String METADATA_ENCHANTMENT = "required_enchantment";
    /**
     * 条件元数据键：要求具备的最低附魔等级。
     */
    public static final String METADATA_MIN_LEVEL = "required_min_level";


    private ScenarioConditionEvaluator() {
    }

    /**
     * 分支资格与规则保留项；机会不等同于每次抽取必然成功。
     */
    public record BranchStatus(ConditionTree.Truth truth, boolean hasRandomCondition,
                               boolean hasUnresolvedCondition) {
        public boolean allDeterministicSatisfied() {
            return truth != ConditionTree.Truth.FALSE;
        }
    }

    /**
     * 使用完整输入解释同一棵逻辑树。
     */
    public static ConditionTree.Truth evaluate(LootConditionInfo condition, SimulationInput input) {
        return ConditionTree.fold(condition, false, (leaf, negated) -> {
            if (leaf.semantics() instanceof ConditionSemantics.Controlled) {
                return ConditionIdentity.isStable(leaf)
                        ? ConditionTree.Truth.of(input.enabledConditions().contains(leaf.identity().key()) != negated)
                        : ConditionTree.Truth.UNKNOWN;
            }
            return ConditionSemantics.evaluate(leaf.semantics(), input.params(), negated);
        });
    }

    public static ConditionTree.Truth evaluate(List<LootConditionInfo> conditions, SimulationInput input) {
        ConditionTree.Truth result = ConditionTree.Truth.TRUE;
        for (LootConditionInfo condition : conditions) {
            ConditionTree.Truth next = evaluate(condition, input);
            if (next == ConditionTree.Truth.FALSE) return next;
            if (next == ConditionTree.Truth.UNKNOWN) result = next;
        }
        return result;
    }

    /**
     * 判断一组条件在给定已勾选场景条件集合下是否未被阻塞（允许三值中的 TRUE 与 UNKNOWN）。
     */
    public static boolean areScenarioConditionsSatisfied(List<LootConditionInfo> conditions,
                                                         Set<String> enabledConditionIds) {
        for (LootConditionInfo condition : conditions) {
            ConditionTree.Truth result = evaluateScenarioTree(condition, enabledConditionIds);
            if (result == ConditionTree.Truth.FALSE) {
                return false;
            }
        }
        return true;
    }

    /**
     * 以三值代数对单棵条件树求值场景控制叶子（未参与投影的叶子保持 {@code UNKNOWN}）。
     */
    public static ConditionTree.Truth evaluateScenarioTree(LootConditionInfo condition,
                                                           Set<String> enabledConditionIds) {
        return ConditionTree.fold(condition, false, (leaf, negated) -> {
            if (!SimulationScenarioPlanner.isScenarioControlled(leaf.conditionType())) {
                return ConditionTree.Truth.UNKNOWN;
            }
            if (!ConditionIdentity.isStable(leaf)) {
                return ConditionTree.Truth.UNKNOWN;
            }
            boolean enabled = enabledConditionIds.contains(leaf.identity().key());
            return ConditionTree.Truth.of(enabled != negated);
        });
    }

    /**
     * 综合评估分支在当前已勾选条件、工具、附魔与幸运参数下的实时状态。
     */
    public static BranchStatus evaluateBranch(ScenarioBranch branch,
                                              Set<String> enabledConditionIds,
                                              ScenarioParams params) {
        SimulationInput input = new SimulationInput(List.copyOf(enabledConditionIds), params);
        List<LootConditionInfo> conditions = new ArrayList<>(branch.conditions());
        conditions.addAll(branch.requirements());
        ConditionTree.Truth truth = isLuckSatisfied(branch.luck(), params.luck())
                ? evaluate(conditions, input) : ConditionTree.Truth.FALSE;
        boolean random = hasRandomCondition(conditions);
        return new BranchStatus(truth, random, truth == ConditionTree.Truth.UNKNOWN);
    }

    /**
     * 判定当前幸运值是否满足路径的 {@link LuckGate}。
     */
    public static boolean isLuckSatisfied(@Nullable LuckGate gate, float luck) {
        if (gate == null || gate.isTrivial()) {
            return true;
        }
        if (gate.impossible()) {
            return false;
        }
        if (gate.minLuck().isPresent() && luck < gate.minLuck().getAsDouble()) {
            return false;
        }
        return !(gate.bonusRollsGate().isPresent() && luck < gate.bonusRollsGate().getAsDouble());
    }

    /**
     * 判断单个条件是否需要随机触发。附魔概率档只有落在 0 和 1 之间才存在随机结果。
     */
    public static boolean isRandomCondition(LootConditionInfo condition) {
        if (!LootConditionHandlers.supports(condition.conditionType(), ConditionTypeDescriptor.Capability.RANDOM)) {
            return false;
        }
        if (condition.semantics() instanceof ConditionSemantics.EnchantedChance chance) {
            return chance.probabilities().stream().anyMatch(probability -> probability > 0F && probability < 1F);
        }
        return true;
    }

    private static boolean hasRandomCondition(List<LootConditionInfo> conditions) {
        for (LootConditionInfo condition : conditions) {
            boolean[] found = {false};
            ConditionTree.walkLogic(condition, node -> {
                if (isRandomCondition(node)) {
                    found[0] = true;
                }
            });
            if (found[0]) {
                return true;
            }
        }
        return false;
    }

}
