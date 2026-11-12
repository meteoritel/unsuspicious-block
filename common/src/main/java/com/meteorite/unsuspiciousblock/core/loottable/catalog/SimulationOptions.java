package com.meteorite.unsuspiciousblock.core.loottable.catalog;

import com.meteorite.unsuspiciousblock.core.loottable.simulation.ConditionOption;
import com.meteorite.unsuspiciousblock.core.loottable.simulation.ScenarioParams;
import com.meteorite.unsuspiciousblock.core.loottable.simulation.SimulationConstraintCatalog;
import com.meteorite.unsuspiciousblock.core.loottable.simulation.ToolOption;
import net.minecraft.resources.ResourceLocation;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 每表一次下发的模拟约束：可勾选原子条件清单、工具基座、附魔上限与抽样档位。
 */
public record SimulationOptions(long generation,
                                List<ConditionOption> conditions,
                                List<ToolOption> tools,
                                Map<ResourceLocation, Integer> enchantments,
                                List<Integer> samples,
                                boolean budgetExhausted,
                                boolean toolSelectionAllowed) {
    public SimulationOptions {
        conditions = List.copyOf(conditions);
        tools = List.copyOf(tools);
        enchantments = Map.copyOf(enchantments);
        samples = List.copyOf(samples);
    }

    /** 由服务端约束目录构造网络同步用的只读选项。 */
    public static SimulationOptions from(long generation, SimulationConstraintCatalog catalog) {
        return new SimulationOptions(
                generation,
                catalog.conditions(),
                catalog.tools(),
                catalog.enchantmentMaxLevels(),
                catalog.sampleCounts(),
                catalog.scenarioBudgetExhausted(),
                catalog.toolSelectionAllowed());
    }

    /** 校验客户端本地选择（勾选条件集 + 参数）是否全部落在当前表签发范围内。 */
    public boolean rejects(List<String> enabledConditions, ScenarioParams params) {
        if (enabledConditions == null || params == null || enabledConditions.size() > conditions.size()) {
            return true;
        }
        Set<String> validIds = new LinkedHashSet<>(conditions.size());
        for (ConditionOption option : conditions) {
            validIds.add(option.id());
        }
        Set<String> seen = new LinkedHashSet<>(enabledConditions.size());
        for (String id : enabledConditions) {
            if (id == null || !validIds.contains(id) || !seen.add(id)) {
                return true;
            }
        }
        return !(tools.stream().anyMatch(tool -> tool.id().equals(params.toolId()))
                && samples.contains(params.sampleCount())
                && params.toolEnchantments().entrySet().stream()
                .allMatch(entry -> entry.getValue() <= enchantments.getOrDefault(entry.getKey(), -1)));
    }
}
