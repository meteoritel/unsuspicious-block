package com.meteorite.unsuspiciousblock.core.loottable.analysis;

import com.meteorite.unsuspiciousblock.core.loottable.simulation.ScenarioParams;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 冻结后仍可解释的叶子事实；随机叶子描述支持集，不在阅读或推荐时抽签。
 */
public sealed interface ConditionSemantics {
    /**
     * 未覆盖的规则保留原因，不能伪装成不匹配。
     */
    record Unknown(String reason) implements ConditionSemantics {
    }

    /**
     * 环境真值由签发条件身份和输入中的勾选集合决定。
     */
    record Controlled() implements ConditionSemantics {
    }

    /**
     * 固定概率支持；零与一分别排除正向或反向路径。
     */
    record Chance(float probability) implements ConditionSemantics {
    }

    /**
     * 按附魔等级取档，超出最后一档时沿用最后一档。
     */
    record EnchantedChance(ResourceLocation enchantment, List<Float> probabilities) implements ConditionSemantics {
        public EnchantedChance {
            probabilities = List.copyOf(probabilities);
        }
    }

    /**
     * 一个附魔子谓词要求集合中至少一个实际存在的附魔满足等级范围。
     */
    record EnchantmentRequirement(Set<ResourceLocation> ids, boolean anyEnchantment, int min, int max) {
        public EnchantmentRequirement {
            ids = Set.copyOf(ids);
        }

        public boolean matches(Map<ResourceLocation, Integer> levels) {
            return levels.entrySet().stream().anyMatch(entry -> entry.getValue() > 0
                    && (anyEnchantment || ids.contains(entry.getKey()))
                    && entry.getValue() >= min && entry.getValue() <= max);
        }
    }

    /**
     * 工具不变量与可变附魔分开保存；额外组件约束保留未知。
     */
    record ToolPredicate(Set<ResourceLocation> items, boolean anyItem, int minCount, int maxCount,
                         List<EnchantmentRequirement> enchantments, boolean requiresEnchantmentsComponent,
                         boolean partial) implements ConditionSemantics {
        public ToolPredicate {
            items = Set.copyOf(items);
            enchantments = List.copyOf(enchantments);
        }
    }

    /**
     * 当前输入下、指定极性是否存在触发机会。
     */
    static ConditionTree.Truth evaluate(ConditionSemantics semantics, ScenarioParams params, boolean negated) {
        return switch (semantics) {
            case Chance chance -> chance(chance.probability(), negated);
            case EnchantedChance chance -> chance.probabilities().isEmpty() ? ConditionTree.Truth.UNKNOWN
                    : chance(chance.probabilities().get(Math.min(params.effectiveToolEnchantments()
                    .getOrDefault(chance.enchantment(), 0), chance.probabilities().size() - 1)), negated);
            case ToolPredicate predicate -> {
                int count = params.toolId().equals(ResourceLocation.withDefaultNamespace("air")) ? 0 : 1;
                boolean known = (predicate.anyItem() || predicate.items().contains(params.toolId()))
                        && count >= predicate.minCount() && count <= predicate.maxCount()
                        && (!predicate.requiresEnchantmentsComponent() || params.hasToolEnchantmentsComponent())
                        && predicate.enchantments().stream().allMatch(requirement -> requirement.matches(params.effectiveToolEnchantments()));
                yield !known ? ConditionTree.Truth.of(negated)
                        : predicate.partial() ? ConditionTree.Truth.UNKNOWN : ConditionTree.Truth.of(!negated);
            }
            default -> ConditionTree.Truth.UNKNOWN;
        };
    }

    private static ConditionTree.Truth chance(float probability, boolean negated) {
        if (!Float.isFinite(probability)) return ConditionTree.Truth.UNKNOWN;
        return ConditionTree.Truth.of(negated ? probability < 1 : probability > 0);
    }
}
