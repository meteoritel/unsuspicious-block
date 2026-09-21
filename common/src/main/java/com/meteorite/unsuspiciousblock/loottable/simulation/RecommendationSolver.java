package com.meteorite.unsuspiciousblock.loottable.simulation;

import com.meteorite.unsuspiciousblock.loottable.analysis.LootConditionInfo;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.*;
import com.meteorite.unsuspiciousblock.loottable.catalog.Recommendation;
import com.meteorite.unsuspiciousblock.loottable.condition.ToolEnchantmentCondition;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import java.util.*;

/** 按需、带预算地搜索单条路径的联合见证；未知谓词与受限幸运区间不作可达承诺。 */
public final class RecommendationSolver {
    private static final int MAX_ATTEMPTS = 2048;
    private RecommendationSolver() {}

    public static Optional<Recommendation> solve(TableDefinition table, String target,
            SimulationConstraintCatalog catalog, ScenarioParams current, ServerLevel level) {
        Budget budget = new Budget();
        for (ItemDefinition item : table.items()) {
            if (!item.signature().toStoredKey().equals(target)) continue;
            for (int pathIndex = 0; pathIndex < item.acquisitionPaths().size(); pathIndex++) {
                LootAcquisitionPath path = item.acquisitionPaths().get(pathIndex);
                Float luck = jointLuck(path, current.luck(), budget);
                if (luck == null) continue;
                for (SimulationScenario scene : catalog.scenarios()) {
                    for (ToolOption tool : catalog.tools()) {
                        List<ResourceLocation> ids = catalog.enchantmentMaxLevels().keySet().stream()
                                .sorted(Comparator.comparing(ResourceLocation::toString)).toList();
                        SimulationInput found = searchLevels(catalog, scene, path, ids, 0,
                                new LinkedHashMap<>(), luck, tool.id(), current.sampleCount(), level, budget);
                        if (found != null) return Optional.of(new Recommendation(found, pathIndex));
                        if (budget.exhausted()) return Optional.empty();
                    }
                }
            }
        }
        return Optional.empty();
    }

    // 用路径上每层的真实权重/抽取公式回验同一个幸运值；未知 provider 不作证明。
    private static Float jointLuck(LootAcquisitionPath path, float current, Budget budget) {
        if (path.luckRequirements().isEmpty()) return null;
        if (numericPath(path, current)) return current;
        for (int step = 0; step <= 1500; step++) {
            if (budget.exhausted()) return null;
            float luck = step <= 1000 ? step / 100F : (1000 - step) / 100F;
            if (numericPath(path, luck)) return luck;
        }
        return null;
    }

    private static boolean numericPath(LootAcquisitionPath path, float luck) {
        for (var spec : path.luckRequirements()) {
            if (spec.rolls() == null || spec.bonusRolls() == null) return false;
            if (Math.max((int) Math.floor(spec.weight() + spec.quality() * luck), 0) <= 0) return false;
            if (Math.floor(spec.rolls()) + Math.floor(spec.bonusRolls().floatValue() * luck) <= 0) return false;
        }
        return true;
    }

    private static SimulationInput searchLevels(SimulationConstraintCatalog catalog, SimulationScenario scene,
            LootAcquisitionPath path, List<ResourceLocation> ids, int index, Map<ResourceLocation, Integer> levels,
            float luck, ResourceLocation tool, int samples, ServerLevel world, Budget budget) {
        if (budget.exhausted()) return null;
        if (index < ids.size()) {
            ResourceLocation id = ids.get(index);
            for (int value = 0; value <= catalog.enchantmentMaxLevels().get(id); value++) {
                levels.put(id, value);
                SimulationInput result = searchLevels(catalog, scene, path, ids, index + 1,
                        levels, luck, tool, samples, world, budget);
                if (result != null) return result;
                if (budget.exhausted()) break;
            }
            levels.remove(id);
            return null;
        }
        budget.attempts++;
        ScenarioParams params = new ScenarioParams(luck, tool, levels, samples);
        var resolved = catalog.resolve(scene.key(), params);
        if (resolved.isEmpty()) return null;
        var stack = params.createToolStack(world.registryAccess());
        LootContext context = new LootContext.Builder(new LootParams.Builder(world)
                .withParameter(LootContextParams.ORIGIN, scene.profile().origin())
                .withParameter(LootContextParams.TOOL, stack).withLuck(luck)
                .create(LootContextParamSets.ALL_PARAMS)).create(Optional.empty());
        for (LootConditionInfo condition : path.allConditions())
            if (!Boolean.TRUE.equals(possible(condition, scene, context, false))) return null;
        return resolved.get();
    }

    // 三值逻辑：null 表示不能证明，反转未知仍是未知；随机条件用非零支持集，不掷骰猜结论。
    private static Boolean possible(LootConditionInfo info, SimulationScenario scene,
                                     LootContext context, boolean inverted) {
        String type = info.conditionType().getPath();
        if (type.equals("inverted") && info.children().size() == 1)
            return possible(info.children().getFirst(), scene, context, !inverted);
        if (type.equals("all_of") || type.equals("any_of")) {
            boolean and = type.equals("all_of") != inverted;
            boolean unknown = false;
            for (var child : info.children()) {
                Boolean value = possible(child, scene, context, inverted);
                if (value == null) unknown = true;
                else if (value != and) return !and;
            }
            return unknown ? null : and;
        }
        if (SimulationScenarioPlanner.isScenarioControlled(info.conditionType())) {
            Boolean value = scene.profile().conditionOutcomes().get(LootConditionFingerprint.of(info));
            return value == null ? null : value != inverted;
        }
        if (info.source() instanceof ToolEnchantmentCondition(var enchantment, var minLevel, var curve)) {
            var tool = context.getParam(LootContextParams.TOOL);
            int level = tool.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY).getLevel(enchantment);
            float chance = level < minLevel ? 0 : curve.map(v -> v.calculate(level)).orElse(1F);
            return inverted ? chance < 1 : chance > 0;
        }
        if (type.equals("match_tool") && info.source() != null) {
            try { return info.source().test(context) != inverted; }
            catch (RuntimeException ignored) { return null; }
        }
        if (type.equals("random_chance") && info.probability() != null)
            return inverted ? info.probability() < 1 : info.probability() > 0;
        return null;
    }

    /** 搜索节点数与墙钟时间双重上界，避免整合包谓词拖住服务端 tick。 */
    private static final class Budget {
        private final long deadline = System.nanoTime() + 15_000_000L;
        private int attempts;
        boolean exhausted() { return attempts >= MAX_ATTEMPTS || System.nanoTime() >= deadline; }
    }
}

