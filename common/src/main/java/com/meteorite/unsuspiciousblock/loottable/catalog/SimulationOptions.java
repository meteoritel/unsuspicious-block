package com.meteorite.unsuspiciousblock.loottable.catalog;

import com.meteorite.unsuspiciousblock.loottable.simulation.*;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;

/** 每表一次下发的参数约束；不包含服务端条件赋值或预生成输入组合。 */
public record SimulationOptions(long generation, List<CatalogTableDto.ScenarioAssumptions> scenes,
        List<ToolOption> tools, Map<ResourceLocation, Integer> enchantments,
        List<Integer> samples, int truncated, boolean budgetExhausted) {
    public SimulationOptions {
        scenes = List.copyOf(scenes);
        tools = List.copyOf(tools);
        enchantments = Map.copyOf(enchantments);
        samples = List.copyOf(samples);
    }

    public static SimulationOptions from(long generation, SimulationConstraintCatalog catalog) {
        return new SimulationOptions(generation, catalog.scenarios().stream()
                .map(s -> new CatalogTableDto.ScenarioAssumptions(s.key(), s.assumptions())).toList(),
                catalog.tools(), catalog.enchantmentMaxLevels(), catalog.sampleCounts(),
                catalog.truncatedScenarioCount(), catalog.scenarioBudgetExhausted());
    }

    public boolean rejects(String scene, ScenarioParams params) {
        return !(scenes.stream().anyMatch(s -> s.scenarioKey().equals(scene))
                && tools.stream().anyMatch(t -> t.id().equals(params.toolId()))
                && samples.contains(params.sampleCount())
                && params.toolEnchantments().entrySet().stream()
                    .allMatch(e -> e.getValue() <= enchantments.getOrDefault(e.getKey(), -1)));
    }
}

