package com.meteorite.unsuspiciousblock.core.loottable.simulation;

import com.meteorite.unsuspiciousblock.core.loottable.analysis.ConditionIdentity;
import com.meteorite.unsuspiciousblock.core.loottable.analysis.ConditionSemantics;
import com.meteorite.unsuspiciousblock.core.loottable.analysis.ConditionTree;
import com.meteorite.unsuspiciousblock.core.loottable.analysis.LootConditionInfo;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.LootTableCatalog.LootAcquisitionPath;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.Recommendation;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.ScenarioBranchCatalog.Candidate;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Objects;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * 沿条件树惰性构造需求，只搜索请求范围内的完整路径及其引用参数。
 */
public final class RecommendationSolver {
    private static final int MAX_ATTEMPTS = 2048;
    private static final long TIME_BUDGET_NANOS = 15_000_000L;
    private static final int LUCK_GRID = 100;
    private static final int MIN_LUCK_STEP = -500;
    private static final int MAX_LUCK_STEP = 1000;

    private RecommendationSolver() {
    }

    /**
     * 结束原因区分已知冲突、尚未解释的规则与搜索截断。
     */
    public enum Outcome {FOUND, UNSUPPORTED, BUDGET_EXHAUSTED, NO_CANDIDATE}

    /**
     * 成功附带路径归属；未知诊断保留条件身份或幸运原因。
     */
    public record Result(Outcome outcome, @Nullable Recommendation recommendation, List<String> reasons) {
        public Result {
            reasons = List.copyOf(reasons);
        }
    }

    /**
     * 使用生产预算；候选范围由同代分支索引解析，求解器不扩大范围。
     */
    public static Result solve(List<Candidate> candidates, SimulationConstraintCatalog catalog, SimulationInput current) {
        return solve(candidates, catalog, current, MAX_ATTEMPTS, TIME_BUDGET_NANOS, System::nanoTime);
    }

    /**
     * 可注入预算与时钟以确定性验证搜索终态，时间单位为纳秒。
     */
    public static Result solve(List<Candidate> candidates, SimulationConstraintCatalog catalog, SimulationInput current,
                               int maxAttempts, long duration, LongSupplier clock) {
        Budget budget = new Budget(maxAttempts, duration, clock);
        Set<String> unknown = new LinkedHashSet<>();
        for (Candidate candidate : candidates) {
            if (!budget.take()) break;
            LootAcquisitionPath path = candidate.path();
            boolean knownLuck = path.luckRequirements().isEmpty()
                    ? !path.luckAffected() && (path.luckGate() == null || path.luckGate().isTrivial())
                    : path.luckRequirements().stream().allMatch(spec -> spec.rolls() != null && spec.bonusRolls() != null)
                    && numericPath(path, current.params().luck());
            if (!path.luckImpossible() && knownLuck && catalog.resolve(current.enabledConditions(), current.params()).isPresent()
                    && ScenarioConditionEvaluator.evaluate(path.allConditions(), current) == ConditionTree.Truth.TRUE)
                return found(candidate, current, unknown);
        }
        for (Candidate candidate : candidates) {
            if (!budget.take()) break;
            LootAcquisitionPath path = candidate.path();
            Float luck = jointLuck(path, current.params().luck(), budget, unknown);
            if (luck == null) continue;
            SimulationInput preferred = new SimulationInput(current.enabledConditions(), new ScenarioParams(luck,
                    current.params().toolId(), current.params().toolEnchantments(), current.params().sampleCount()));
            if (catalog.resolve(preferred.enabledConditions(), preferred.params()).isPresent()
                    && ScenarioConditionEvaluator.evaluate(path.allConditions(), preferred) == ConditionTree.Truth.TRUE)
                return found(candidate, preferred, unknown);
            Supplier<Stream<Requirement>> alternatives = requirements(path.allConditions(), budget);
            Iterator<Requirement> iterator = alternatives.get().iterator();
            while (!budget.exhausted() && iterator.hasNext()) {
                Requirement requirement = iterator.next();
                List<String> enabled = requirement.apply(current.enabledConditions());
                List<ToolOption> tools = new ArrayList<>(catalog.tools());
                tools.sort(Comparator.comparing(tool -> !tool.id().equals(current.params().toolId())));
                if (!requirement.equipment()) tools.removeIf(tool -> !tool.id().equals(current.params().toolId()));
                Map<ResourceLocation, List<Integer>> domains = levelDomains(requirement, catalog, current.params());
                for (ToolOption tool : tools) {
                    Search search = new Search(path, catalog, enabled, luck, tool.id(), current.params().sampleCount(),
                            List.copyOf(domains.entrySet()), budget, unknown);
                    SimulationInput found = search.levels(0, new LinkedHashMap<>(current.params().toolEnchantments()));
                    if (found != null) return found(candidate, found, unknown);
                    if (budget.exhausted()) break;
                }
            }
            if (budget.exhausted()) break;
        }
        return new Result(budget.exhausted() ? Outcome.BUDGET_EXHAUSTED
                : unknown.isEmpty() ? Outcome.NO_CANDIDATE : Outcome.UNSUPPORTED, null, List.copyOf(unknown));
    }

    private static Result found(Candidate candidate, SimulationInput input, Set<String> unknown) {
        return new Result(Outcome.FOUND, new Recommendation(input,
                new Recommendation.Witness(candidate.branchReference(), candidate.kind(), candidate.target(),
                        candidate.itemSignature(), candidate.pathId())), List.copyOf(unknown));
    }

    private record Leaf(LootConditionInfo condition, boolean negated) {
    }

    private record Requirement(Map<String, Boolean> environment, List<Leaf> leaves) {
        static Requirement empty() {
            return new Requirement(Map.of(), List.of());
        }

        @Nullable Requirement merge(Requirement other) {
            Map<String, Boolean> values = new LinkedHashMap<>(environment);
            for (var entry : other.environment.entrySet()) {
                Boolean previous = values.putIfAbsent(entry.getKey(), entry.getValue());
                if (previous != null && !previous.equals(entry.getValue())) return null;
            }
            List<Leaf> combined = new ArrayList<>(leaves);
            combined.addAll(other.leaves);
            return new Requirement(values, combined);
        }

        List<String> apply(List<String> preferred) {
            Set<String> enabled = new LinkedHashSet<>(preferred);
            environment.forEach((id, value) -> {
                if (value) enabled.add(id);
                else enabled.remove(id);
            });
            return SimulationInput.canonicalize(List.copyOf(enabled));
        }

        boolean equipment() {
            return leaves.stream().anyMatch(leaf -> leaf.condition().semantics() instanceof ConditionSemantics.ToolPredicate
                    || leaf.condition().semantics() instanceof ConditionSemantics.EnchantedChance);
        }
    }

    private static Supplier<Stream<Requirement>> requirements(List<LootConditionInfo> conditions, Budget budget) {
        ConditionTree.Algebra<Supplier<Stream<Requirement>>> algebra = new ConditionTree.Algebra<>() {
            public Supplier<Stream<Requirement>> leaf(LootConditionInfo node, boolean negated) {
                Requirement requirement = node.semantics() instanceof ConditionSemantics.Controlled && ConditionIdentity.isStable(node)
                        ? new Requirement(Map.of(node.identity().key(), !negated), List.of())
                        : new Requirement(Map.of(), List.of(new Leaf(node, negated)));
                return () -> Stream.of(requirement);
            }

            public Supplier<Stream<Requirement>> identity(boolean conjunction) {
                return conjunction ? () -> Stream.of(Requirement.empty()) : Stream::empty;
            }

            public Supplier<Stream<Requirement>> combine(boolean conjunction, Supplier<Stream<Requirement>> left,
                                                         Supplier<Stream<Requirement>> right) {
                if (!conjunction)
                    return () -> Stream.concat(left.get(), right.get()).takeWhile(ignored -> budget.take());
                return () -> left.get().flatMap(a -> right.get().takeWhile(ignored -> budget.take())
                        .map(a::merge).filter(Objects::nonNull));
            }

            public boolean isTerminal(boolean conjunction, Supplier<Stream<Requirement>> value) {
                return false;
            }

            public boolean canVisit(LootConditionInfo node) {
                return budget.take();
            }

            public Supplier<Stream<Requirement>> unknown(LootConditionInfo node) {
                return Stream::empty;
            }
        };
        Supplier<Stream<Requirement>> result = algebra.identity(true);
        for (LootConditionInfo condition : conditions)
            result = algebra.combine(true, result, ConditionTree.fold(condition, false, algebra));
        return result;
    }

    private static Map<ResourceLocation, List<Integer>> levelDomains(Requirement requirement,
                                                                     SimulationConstraintCatalog catalog, ScenarioParams current) {
        Map<ResourceLocation, Set<Integer>> boundaries = new LinkedHashMap<>();
        for (Leaf leaf : requirement.leaves()) {
            if (leaf.condition().semantics() instanceof ConditionSemantics.EnchantedChance(
                    var enchantment, var probabilities
            )) {
                for (int level = 0; level < probabilities.size(); level++)
                    boundary(boundaries, catalog, enchantment, level);
            } else if (leaf.condition().semantics() instanceof ConditionSemantics.ToolPredicate tool) {
                for (var enchantment : tool.enchantments()) {
                    Set<ResourceLocation> ids = enchantment.anyEnchantment() ? catalog.enchantmentMaxLevels().keySet() : enchantment.ids();
                    for (ResourceLocation id : ids) {
                        boundary(boundaries, catalog, id, 0);
                        boundary(boundaries, catalog, id, 1);
                        boundary(boundaries, catalog, id, enchantment.min());
                        if (enchantment.max() < Integer.MAX_VALUE)
                            boundary(boundaries, catalog, id, enchantment.max() + 1);
                    }
                }
            }
        }
        Map<ResourceLocation, List<Integer>> domains = new LinkedHashMap<>();
        boundaries.entrySet().stream().sorted(Map.Entry.comparingByKey(Comparator.comparing(ResourceLocation::toString)))
                .forEach(entry -> {
                    Set<Integer> levels = new LinkedHashSet<>();
                    levels.add(current.toolEnchantments().getOrDefault(entry.getKey(), 0));
                    entry.getValue().stream().sorted().forEach(levels::add);
                    domains.put(entry.getKey(), List.copyOf(levels));
                });
        return domains;
    }

    private static void boundary(Map<ResourceLocation, Set<Integer>> domains, SimulationConstraintCatalog catalog,
                                 ResourceLocation id, int level) {
        Integer max = catalog.enchantmentMaxLevels().get(id);
        if (max != null && level >= 0 && level <= max)
            domains.computeIfAbsent(id, ignored -> new LinkedHashSet<>()).add(level);
    }

    private record Search(LootAcquisitionPath path, SimulationConstraintCatalog catalog, List<String> enabled,
                          float luck, ResourceLocation tool, int samples,
                          List<Map.Entry<ResourceLocation, List<Integer>>> domains, Budget budget,
                          Set<String> unknown) {
        @Nullable SimulationInput levels(int index, Map<ResourceLocation, Integer> values) {
            if (!budget.take()) return null;
            if (index < domains.size()) {
                var domain = domains.get(index);
                Integer previous = values.get(domain.getKey());
                for (int value : domain.getValue()) {
                    if (value == 0) values.remove(domain.getKey());
                    else values.put(domain.getKey(), value);
                    SimulationInput found = levels(index + 1, values);
                    if (found != null) return found;
                    if (budget.exhausted()) break;
                }
                if (previous == null) values.remove(domain.getKey());
                else values.put(domain.getKey(), previous);
                return null;
            }
            var input = catalog.resolve(enabled, new ScenarioParams(luck, tool, values, samples));
            if (input.isEmpty()) return null;
            ConditionTree.Truth truth = ScenarioConditionEvaluator.evaluate(path.allConditions(), input.get());
            if (truth == ConditionTree.Truth.TRUE) return input.get();
            if (truth == ConditionTree.Truth.UNKNOWN) for (LootConditionInfo condition : path.allConditions())
                ConditionTree.walkLogic(condition, node -> {
                    if (node.kind() == ConditionTree.Kind.LEAF
                            && ScenarioConditionEvaluator.evaluate(node, input.get()) == ConditionTree.Truth.UNKNOWN)
                        unknown.add(node.identity().key());
                });
            return null;
        }
    }

    private static @Nullable Float jointLuck(LootAcquisitionPath path, float current, Budget budget, Set<String> unknown) {
        if (path.luckImpossible()) return null;
        if (path.luckRequirements().isEmpty()) {
            if (!path.luckAffected() && (path.luckGate() == null || path.luckGate().isTrivial())) return current;
            unknown.add("luck_requirements");
            return null;
        }
        if (path.luckRequirements().stream().anyMatch(spec -> spec.rolls() == null || spec.bonusRolls() == null)) {
            unknown.add("luck_provider");
            return null;
        }
        if (numericPath(path, current)) return current;
        for (int step = 0; step <= MAX_LUCK_STEP - MIN_LUCK_STEP; step++) {
            if (!budget.take()) return null;
            float luck = (step <= MAX_LUCK_STEP ? step : MAX_LUCK_STEP - step) / (float) LUCK_GRID;
            if (numericPath(path, luck)) return luck;
        }
        return null;
    }

    private static boolean numericPath(LootAcquisitionPath path, float luck) {
        for (var spec : path.luckRequirements()) {
            if (Math.max((int) Math.floor(spec.weight() + spec.quality() * luck), 0) == 0) return false;
            if (Math.floor(Objects.requireNonNull(spec.rolls())) + Math.floor(Objects.requireNonNull(spec.bonusRolls()).floatValue() * luck) <= 0)
                return false;
        }
        return true;
    }

    private static final class Budget {
        private final int maxAttempts;
        private final long deadline;
        private final LongSupplier clock;
        private int attempts;
        private boolean exhausted;

        Budget(int maxAttempts, long duration, LongSupplier clock) {
            this.maxAttempts = maxAttempts;
            this.clock = clock;
            deadline = clock.getAsLong() + duration;
        }

        boolean take() {
            if (attempts >= maxAttempts || clock.getAsLong() >= deadline) {
                exhausted = true;
                return false;
            }
            attempts++;
            return true;
        }

        boolean exhausted() {
            return exhausted;
        }
    }
}
