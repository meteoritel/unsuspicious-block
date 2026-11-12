package com.meteorite.unsuspiciousblock.core.loottable.simulation;

import com.meteorite.unsuspiciousblock.core.loottable.analysis.ConditionIdentity;
import com.meteorite.unsuspiciousblock.core.loottable.analysis.ConditionTree;
import com.meteorite.unsuspiciousblock.core.loottable.analysis.ConditionTypeDescriptor;
import com.meteorite.unsuspiciousblock.core.loottable.analysis.LootConditionHandlers;
import com.meteorite.unsuspiciousblock.core.loottable.analysis.LootConditionInfo;
import com.meteorite.unsuspiciousblock.core.loottable.analysis.LuckGate;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.ScenarioBranch;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 场景与分支条件的共用三值求值器——前后端共用同一套逻辑树判定口径，避免服务端与 UI 漂移。
 */
public final class ScenarioConditionEvaluator {
    /** 条件元数据键：要求具备的工具附魔注册名。 */
    public static final String METADATA_ENCHANTMENT = "required_enchantment";
    /** 条件元数据键：要求具备的最低附魔等级。 */
    public static final String METADATA_MIN_LEVEL = "required_min_level";

    private static final ResourceLocation MATCH_TOOL = ResourceLocation.withDefaultNamespace("match_tool");

    private ScenarioConditionEvaluator() {
    }

    /**
     * 单条获取分支在当前勾选条件与参数下的实时状态。
     *
     * @param scenarioSatisfied      环境条件是否不阻塞该分支
     * @param toolSatisfied          当前工具基座是否满足 {@code match_tool}
     * @param enchantmentSatisfied   当前工具附魔等级是否满足附魔门槛
     * @param luckSatisfied          当前幸运值是否满足 {@link LuckGate}
     * @param hasRandomCondition     是否包含概率掷骰条件
     * @param hasUnresolvedCondition 是否含未完全解析或运行时条件
     */
    public record BranchStatus(
            boolean scenarioSatisfied,
            boolean toolSatisfied,
            boolean enchantmentSatisfied,
            boolean luckSatisfied,
            boolean hasRandomCondition,
            boolean hasUnresolvedCondition
    ) {
        /** 全部可静态判定的门槛（环境、工具、附魔、幸运）是否均已满足。 */
        public boolean allDeterministicSatisfied() {
            return scenarioSatisfied && toolSatisfied && enchantmentSatisfied && luckSatisfied;
        }
    }

    /** 判断一组条件在给定已勾选场景条件集合下是否未被阻塞（允许三值中的 TRUE 与 UNKNOWN）。 */
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

    /** 以三值代数对单棵条件树求值场景控制叶子（非场景叶子视作不阻塞的 {@code TRUE}）。 */
    public static ConditionTree.Truth evaluateScenarioTree(LootConditionInfo condition,
                                                           Set<String> enabledConditionIds) {
        return ConditionTree.fold(condition, false, (leaf, negated) -> {
            if (!SimulationScenarioPlanner.isScenarioControlled(leaf.conditionType())) {
                return ConditionTree.Truth.TRUE;
            }
            if (!ConditionIdentity.isStable(leaf)) {
                return ConditionTree.Truth.UNKNOWN;
            }
            boolean enabled = enabledConditionIds.contains(leaf.identity().key());
            return ConditionTree.Truth.of(enabled != negated);
        });
    }

    /** 评估单个环境叶子条件对分支的当前状态：是否处于正向/反向要求及是否已满足。 */
    public static boolean isLeafSatisfied(LootConditionInfo leaf, boolean negated, Set<String> enabledConditionIds) {
        if (!SimulationScenarioPlanner.isScenarioControlled(leaf.conditionType()) || !ConditionIdentity.isStable(leaf)) {
            return true;
        }
        return enabledConditionIds.contains(leaf.identity().key()) != negated;
    }

    /** 综合评估分支在当前已勾选条件、工具、附魔与幸运参数下的实时状态。 */
    public static BranchStatus evaluateBranch(ScenarioBranch branch,
                                              Set<String> enabledConditionIds,
                                              ScenarioParams params,
                                              @Nullable ToolOption selectedTool) {
        boolean scenarioOk = areScenarioConditionsSatisfied(branch.conditions(), enabledConditionIds)
                && areScenarioConditionsSatisfied(branch.requirements(), enabledConditionIds);
        boolean toolOk = areToolConditionsSatisfied(branch.conditions(), selectedTool)
                && areToolConditionsSatisfied(branch.requirements(), selectedTool);
        boolean enchantmentOk = areEnchantmentConditionsSatisfied(branch.conditions(), params.toolEnchantments())
                && areEnchantmentConditionsSatisfied(branch.requirements(), params.toolEnchantments());
        boolean luckOk = isLuckSatisfied(branch.luck(), params.luck());
        boolean hasRandom = containsCapability(branch.conditions(), ConditionTypeDescriptor.Capability.RANDOM)
                || containsCapability(branch.requirements(), ConditionTypeDescriptor.Capability.RANDOM);
        boolean hasUnresolved = branch.uncertain()
                || containsUnreadable(branch.conditions())
                || containsUnreadable(branch.requirements());
        return new BranchStatus(scenarioOk, toolOk, enchantmentOk, luckOk, hasRandom, hasUnresolved);
    }

    /** 判定当前选中的工具基座是否满足 {@code match_tool} 条件树。 */
    public static boolean areToolConditionsSatisfied(List<LootConditionInfo> conditions, @Nullable ToolOption selectedTool) {
        for (LootConditionInfo condition : conditions) {
            ConditionTree.Truth result = ConditionTree.fold(condition, false, (leaf, negated) -> {
                if (!MATCH_TOOL.equals(leaf.conditionType())) {
                    return ConditionTree.Truth.TRUE;
                }
                if (selectedTool == null) {
                    return ConditionTree.Truth.UNKNOWN;
                }
                boolean matched = selectedTool.satisfiedConditionIds().contains(leaf.identity().key());
                return ConditionTree.Truth.of(matched != negated);
            });
            if (result == ConditionTree.Truth.FALSE) {
                return false;
            }
        }
        return true;
    }

    /** 判定当前附魔等级配置是否满足静态可读的附魔门槛（如 {@code tool_enchantment} / {@code table_bonus}）。 */
    public static boolean areEnchantmentConditionsSatisfied(List<LootConditionInfo> conditions,
                                                            Map<ResourceLocation, Integer> toolEnchantments) {
        for (LootConditionInfo condition : conditions) {
            ConditionTree.Truth result = ConditionTree.fold(condition, false, (leaf, negated) -> {
                String enchIdText = leaf.metadata().get(METADATA_ENCHANTMENT);
                if (enchIdText == null) {
                    return ConditionTree.Truth.TRUE;
                }
                ResourceLocation enchId = ResourceLocation.tryParse(enchIdText);
                if (enchId == null) {
                    return ConditionTree.Truth.UNKNOWN;
                }
                int minLevel = 1;
                String minText = leaf.metadata().get(METADATA_MIN_LEVEL);
                if (minText != null) {
                    try {
                        minLevel = Math.max(1, Integer.parseInt(minText));
                    } catch (NumberFormatException ignored) {
                        minLevel = 1;
                    }
                }
                int actual = toolEnchantments.getOrDefault(enchId, 0);
                return ConditionTree.Truth.of((actual >= minLevel) != negated);
            });
            if (result == ConditionTree.Truth.FALSE) {
                return false;
            }
        }
        return true;
    }

    /** 判定当前幸运值是否满足路径的 {@link LuckGate}。 */
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
        if (gate.bonusRollsGate().isPresent() && luck < gate.bonusRollsGate().getAsDouble()) {
            return false;
        }
        return true;
    }

    private static boolean containsCapability(List<LootConditionInfo> conditions,
                                              ConditionTypeDescriptor.Capability capability) {
        for (LootConditionInfo condition : conditions) {
            boolean[] found = {false};
            ConditionTree.walkLogic(condition, node -> {
                if (LootConditionHandlers.supports(node.conditionType(), capability)) {
                    found[0] = true;
                }
            });
            if (found[0]) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsUnreadable(List<LootConditionInfo> conditions) {
        for (LootConditionInfo condition : conditions) {
            boolean[] found = {false};
            ConditionTree.walkLogic(condition, node -> {
                if (LootConditionHandlers.FIDELITY_UNREADABLE.equals(
                        node.metadata().get(LootConditionHandlers.FIDELITY_METADATA_KEY))) {
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
