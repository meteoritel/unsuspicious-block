package com.meteorite.unsuspiciousblock.core.loottable.simulation;

import com.meteorite.unsuspiciousblock.core.loottable.analysis.ConditionIdentity;
import com.meteorite.unsuspiciousblock.core.loottable.analysis.ConditionTree;
import com.meteorite.unsuspiciousblock.core.loottable.analysis.LootConditionInfo;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.LootTableCatalog.ItemDefinition;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.LootTableCatalog.LootAcquisitionPath;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.LootTableCatalog.TableDefinition;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.Recommendation;
import com.meteorite.unsuspiciousblock.core.loottable.condition.ToolEnchantmentCondition;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 按需、带预算地搜索单条路径的联合见证（条件勾选集合 + 工具 + 幸运 + 附魔等级）；
 * 未知谓词与受限幸运区间不作可达承诺。
 */
public final class RecommendationSolver {
    private static final int MAX_ATTEMPTS = 2048;
    private static final int MAX_CONDITION_ASSIGNMENTS = 8;

    private RecommendationSolver() {
    }

    /** 在预算内求解目标物品或子表入口的可达输入配置。 */
    public static Optional<Recommendation> solve(TableDefinition table, SimulationAssistTarget target,
                                                 SimulationConstraintCatalog catalog, SimulationInput currentInput,
                                                 ServerLevel level) {
        Budget budget = new Budget();
        ScenarioParams current = currentInput.params();
        Set<String> preferredConditions = Set.copyOf(currentInput.enabledConditions());
        List<ToolOption> tools = new ArrayList<>(catalog.tools());
        tools.sort(Comparator.comparing(tool -> !tool.id().equals(current.toolId())));
        List<ResourceLocation> ids = catalog.enchantmentMaxLevels().keySet().stream()
                .sorted(Comparator.comparing(ResourceLocation::toString))
                .toList();

        List<CandidatePath> candidates = new ArrayList<>();
        for (ItemDefinition item : table.items()) {
            if (target.kind() == SimulationAssistTarget.Kind.ITEM
                    && !item.signature().toStoredKey().equals(target.value())) {
                continue;
            }
            for (int pathIndex = 0; pathIndex < item.acquisitionPaths().size(); pathIndex++) {
                if (budget.exhausted()) {
                    return Optional.empty();
                }
                LootAcquisitionPath path = item.acquisitionPaths().get(pathIndex);
                if (target.kind() == SimulationAssistTarget.Kind.CHILD_TABLE
                        && (path.sourceChildTable() == null
                        || !path.sourceChildTable().toString().equals(target.value()))) {
                    continue;
                }
                Float luck = jointLuck(path, current.luck(), budget);
                if (luck == null) {
                    continue;
                }
                List<List<String>> conditionAssignments = SimulationScenarioPlanner.satisfyingAssignments(
                        path.allConditions(), preferredConditions, MAX_CONDITION_ASSIGNMENTS);
                if (!conditionAssignments.isEmpty()) {
                    candidates.add(new CandidatePath(path, pathIndex, luck, conditionAssignments));
                }
            }
        }

        for (CandidatePath candidate : candidates) {
            for (List<String> enabledConditions : candidate.conditionAssignments()) {
                Set<String> enabledSet = Set.copyOf(enabledConditions);
                for (ToolOption tool : tools) {
                    SimulationInput found = searchLevels(catalog, enabledConditions, enabledSet,
                            candidate.path(), ids, 0, new LinkedHashMap<>(),
                            candidate.luck(), tool.id(), current.sampleCount(), level, budget);
                    if (found != null) {
                        return Optional.of(new Recommendation(found, candidate.index()));
                    }
                    if (budget.exhausted()) {
                        return Optional.empty();
                    }
                }
            }
        }
        return Optional.empty();
    }

    private record CandidatePath(LootAcquisitionPath path, int index, float luck,
                                 List<List<String>> conditionAssignments) {
    }

    private static Float jointLuck(LootAcquisitionPath path, float current, Budget budget) {
        if (path.luckRequirements().isEmpty()) {
            return null;
        }
        if (numericPath(path, current)) {
            return current;
        }
        for (int step = 0; step <= 1500; step++) {
            if (budget.exhausted()) {
                return null;
            }
            float luck = step <= 1000 ? step / 100F : (1000 - step) / 100F;
            if (numericPath(path, luck)) {
                return luck;
            }
        }
        return null;
    }

    private static boolean numericPath(LootAcquisitionPath path, float luck) {
        for (var spec : path.luckRequirements()) {
            if (spec.rolls() == null || spec.bonusRolls() == null) {
                return false;
            }
            if (Math.max((int) Math.floor(spec.weight() + spec.quality() * luck), 0) == 0) {
                return false;
            }
            if (Math.floor(spec.rolls()) + Math.floor(spec.bonusRolls().floatValue() * luck) <= 0) {
                return false;
            }
        }
        return true;
    }

    private static SimulationInput searchLevels(SimulationConstraintCatalog catalog,
                                              List<String> enabledConditions,
                                              Set<String> enabledSet,
                                              LootAcquisitionPath path,
                                              List<ResourceLocation> ids,
                                              int index,
                                              Map<ResourceLocation, Integer> levels,
                                              float luck,
                                              ResourceLocation tool,
                                              int samples,
                                              ServerLevel world,
                                              Budget budget) {
        if (budget.exhausted()) {
            return null;
        }
        if (index < ids.size()) {
            ResourceLocation id = ids.get(index);
            for (int value = 0; value <= catalog.enchantmentMaxLevels().get(id); value++) {
                levels.put(id, value);
                SimulationInput result = searchLevels(catalog, enabledConditions, enabledSet, path, ids,
                        index + 1, levels, luck, tool, samples, world, budget);
                if (result != null) {
                    return result;
                }
                if (budget.exhausted()) {
                    break;
                }
            }
            levels.remove(id);
            return null;
        }
        budget.attempts++;
        ScenarioParams params = new ScenarioParams(luck, tool, levels, samples);
        var resolved = catalog.resolve(enabledConditions, params);
        if (resolved.isEmpty()) {
            return null;
        }
        var stack = params.createToolStack(world.registryAccess());
        LootContext context = LootContextParamFiller.createConditionTestContext(
                world, catalog.baseProfile().origin(), stack, null, luck);
        for (LootConditionInfo condition : path.allConditions()) {
            if (!Boolean.TRUE.equals(possible(condition, enabledSet, context))) {
                return null;
            }
        }
        return resolved.get();
    }

    private static Boolean possible(LootConditionInfo info, Set<String> enabledSet, LootContext context) {
        return ConditionTree.fold(info, false, (leaf, negated) ->
                ConditionTree.Truth.of(possibleLeaf(leaf, enabledSet, context, negated))).value();
    }

    private static Boolean possibleLeaf(LootConditionInfo info, Set<String> enabledSet,
                                        LootContext context, boolean inverted) {
        String type = info.conditionType().toString();
        if (SimulationScenarioPlanner.isScenarioControlled(info.conditionType())) {
            if (!ConditionIdentity.isStable(info)) {
                return null;
            }
            boolean enabled = enabledSet.contains(ConditionIdentity.of(info).key());
            return enabled != inverted;
        }
        if (info.source() instanceof ToolEnchantmentCondition(var enchantment, var minLevel, var curve)) {
            var tool = context.getParam(LootContextParams.TOOL);
            int level = tool.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY).getLevel(enchantment);
            float chance = level < minLevel ? 0 : curve.map(v -> v.calculate(level)).orElse(1F);
            return inverted ? chance < 1 : chance > 0;
        }
        if (type.equals("minecraft:match_tool") && info.source() != null) {
            try {
                return info.source().test(context) != inverted;
            } catch (RuntimeException ignored) {
                return null;
            }
        }
        if (type.equals("minecraft:random_chance") && info.probability() != null) {
            return inverted ? info.probability() < 1 : info.probability() > 0;
        }
        return null;
    }

    private static final class Budget {
        private final long deadline = System.nanoTime() + 15_000_000L;
        private int attempts;

        boolean exhausted() {
            return attempts >= MAX_ATTEMPTS || System.nanoTime() >= deadline;
        }
    }
}
