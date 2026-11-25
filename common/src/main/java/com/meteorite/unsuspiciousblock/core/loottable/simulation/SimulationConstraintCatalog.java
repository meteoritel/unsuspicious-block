package com.meteorite.unsuspiciousblock.core.loottable.simulation;

import com.meteorite.unsuspiciousblock.core.loottable.analysis.LootConditionHandlers;
import com.meteorite.unsuspiciousblock.core.loottable.analysis.LootConditionInfo;
import com.meteorite.unsuspiciousblock.core.loottable.analysis.LuckGate;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.LootTableCatalog.TableDefinition;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.ParameterKind;
import com.meteorite.unsuspiciousblock.core.loottable.graph.RuntimeLootLinks;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 由表内容派生的模拟约束目录：签发可勾选环境条件清单、工具基座、
 * 被引用附魔上限与抽样次数档位，并在按需请求时校验并即时构造执行场景。
 */
public record SimulationConstraintCatalog(
        List<ConditionOption> conditions,
        boolean scenarioBudgetExhausted,
        List<ToolOption> tools,
        boolean toolSelectionAllowed,
        Map<ResourceLocation, Integer> enchantmentMaxLevels,
        List<Integer> sampleCounts,
        Map<ResourceLocation, List<LootConditionInfo>> childEntryGates,
        Map<ResourceLocation, LuckGate> childEntryLuckGates,
        Set<ResourceLocation> luckAffectedChildren,
        SimulationProfile baseProfile) {

    /** 兼容旧调用方：默认不携带子表入口幸运门槛与幸运敏感集合。 */
    public SimulationConstraintCatalog(
            List<ConditionOption> conditions,
            boolean scenarioBudgetExhausted,
            List<ToolOption> tools,
            boolean toolSelectionAllowed,
            Map<ResourceLocation, Integer> enchantmentMaxLevels,
            List<Integer> sampleCounts,
            Map<ResourceLocation, List<LootConditionInfo>> childEntryGates,
            SimulationProfile baseProfile) {
        this(conditions, scenarioBudgetExhausted, tools, toolSelectionAllowed, enchantmentMaxLevels,
                sampleCounts, childEntryGates, Map.of(), Set.of(), baseProfile);
    }


    public SimulationConstraintCatalog {
        childEntryLuckGates = childEntryLuckGates == null ? Map.of() : Map.copyOf(childEntryLuckGates);
        luckAffectedChildren = luckAffectedChildren == null ? Set.of() : Set.copyOf(luckAffectedChildren);
        conditions = List.copyOf(conditions);
        tools = List.copyOf(tools);
        enchantmentMaxLevels = Map.copyOf(enchantmentMaxLevels);
        sampleCounts = List.copyOf(sampleCounts);
        Map<ResourceLocation, List<LootConditionInfo>> gates = new LinkedHashMap<>();
        childEntryGates.forEach((childTable, gateConditions) ->
                gates.put(childTable, List.copyOf(gateConditions)));
        childEntryGates = Map.copyOf(gates);
        if (tools.isEmpty()) {
            throw new IllegalArgumentException("工具基座清单至少要含默认工具");
        }
    }

    /**
     * 由条件规划结果与表内容派生约束目录，保留工具与附魔的签发范围。
     */
    /** 根据编译分析与展开结果构建完整的模拟约束目录，包含子表入口幸运门槛与幸运敏感集合。 */
    public static SimulationConstraintCatalog build(SimulationScenarioPlanner.ConditionPlan plan,
                                                    SimulationProfile baseProfile,
                                                    Map<ResourceLocation, String> subtreeTools,
                                                    boolean toolSelectionAllowed,
                                                    Map<ResourceLocation, Integer> enchantmentMaxLevels,
                                                    Map<ResourceLocation, List<LootConditionInfo>> childEntryGates,
                                                    Map<ResourceLocation, LuckGate> childEntryLuckGates,
                                                    Set<ResourceLocation> luckAffectedChildren) {
        SimulationConstraintCatalog base = build(plan, baseProfile, subtreeTools, toolSelectionAllowed,
                enchantmentMaxLevels, childEntryGates);
        return new SimulationConstraintCatalog(
                base.conditions(), base.scenarioBudgetExhausted(), base.tools(), base.toolSelectionAllowed(),
                base.enchantmentMaxLevels(), base.sampleCounts(), base.childEntryGates(),
                childEntryLuckGates, luckAffectedChildren, base.baseProfile());
    }

    public static SimulationConstraintCatalog build(SimulationScenarioPlanner.ConditionPlan plan,
                                                    SimulationProfile baseProfile,
                                                    Map<ResourceLocation, String> subtreeTools,
                                                    boolean toolSelectionAllowed,
                                                    Map<ResourceLocation, Integer> enchantmentMaxLevels,
                                                    Map<ResourceLocation, List<LootConditionInfo>> childEntryGates) {
        List<ToolOption> tools = new ArrayList<>(subtreeTools.size() + 1);
        ResourceLocation defaultToolId = baseProfile.toolItemId();
        ToolOption defaultOption = defaultTool(baseProfile);
        tools.add(defaultOption);
        for (Map.Entry<ResourceLocation, String> entry : subtreeTools.entrySet()) {
            if (entry.getKey().equals(defaultToolId)) {
                ToolOption updated = new ToolOption(entry.getKey(), defaultOption.displayName(),
                        Component.literal(entry.getValue()));
                tools.set(0, updated);
                continue;
            }
            ToolOption option = new ToolOption(entry.getKey(),
                    new ItemStack(BuiltInRegistries.ITEM.get(entry.getKey())).getHoverName().copy(),
                    Component.literal(entry.getValue()));
            tools.add(option);
        }
        return new SimulationConstraintCatalog(plan.conditions(), plan.budgetExhausted(),
                tools, toolSelectionAllowed, enchantmentMaxLevels,
                ScenarioParams.SAMPLE_COUNT_TIERS, childEntryGates, baseProfile);
    }

    /**
     * 把注入门槛翻译成结构化条件树，与玩法共用同一套条件分析处理器。
     */
    public static List<LootConditionInfo> describeGate(RuntimeLootLinks.InjectionGate gate,
                                                       HolderLookup.Provider registries) {
        return List.copyOf(LootConditionHandlers.analyzeAll(
                List.of(gate.condition(registries)),
                RegistryOps.create(JsonOps.INSTANCE, registries)));
    }

    /**
     * 默认工具基座——与基准输入共用同一份 profile。
     */
    public static ToolOption defaultTool(SimulationProfile baseProfile) {
        ItemStack tool = baseProfile.tool();
        Component name = tool.isEmpty() ? Component.translatable(
                "screen.unsuspiciousblock.archaeology_journal.simulation.params.empty_hand") : tool.getHoverName().copy();
        return new ToolOption(baseProfile.toolItemId(), name, null);
    }

    /**
     * 基准参数：默认工具 + 幸运 0 + 基准档位 + 无附魔。
     */
    public ScenarioParams baselineParams() {
        return ScenarioParams.baseline(this.tools.getFirst().id());
    }

    /**
     * 启动时每表运行的基准输入：空勾选集合 + 基准参数。
     */
    public SimulationInput baselineInput() {
        return new SimulationInput(List.of(), baselineParams());
    }

    /**
     * 构建基准执行场景。
     */
    public SimulationScenario baselineScenario(TableDefinition planningTable) {
        return buildExecutionScenario(List.of(), planningTable);
    }

    /**
     * 根据校验后的勾选条件列表即时构建模拟执行场景。
     */
    public SimulationScenario buildExecutionScenario(List<String> enabledConditions, TableDefinition planningTable) {
        List<String> canonical = SimulationInput.canonicalize(enabledConditions);
        return SimulationScenarioPlanner.buildScenario(
                SimulationInputKey.scenarioSegment(canonical),
                canonical,
                this.conditions,
                planningTable,
                this.baseProfile);
    }

    /**
     * 按 id 取已签发的工具基座；未签发时返回 {@code null}。
     */
    @Nullable
    public ToolOption tool(ResourceLocation toolId) {
        for (ToolOption option : this.tools) {
            if (option.id().equals(toolId)) {
                return option;
            }
        }
        return null;
    }

    /**
     * 客户端可用的旋钮种类。
     */
    public List<ParameterKind> parameterKinds() {
        List<ParameterKind> kinds = new ArrayList<>(4);
        kinds.add(ParameterKind.LUCK);
        if (toolSelectionAllowed) {
            kinds.add(ParameterKind.TOOL);
        }
        if (!this.enchantmentMaxLevels.isEmpty()) {
            kinds.add(ParameterKind.ENCHANT_LEVEL);
        }
        return List.copyOf(kinds);
    }

    /**
     * 校验一份请求是否落在本目录签发的取值范围内。
     * 任一项越权或包含未签发条件 ID 时整体拒绝，不做静默修正。
     */
    public Optional<SimulationInput> resolve(List<String> enabledConditions, ScenarioParams params) {
        if (enabledConditions == null || params == null) {
            return Optional.empty();
        }
        if (enabledConditions.size() > this.conditions.size()) {
            return Optional.empty();
        }
        Set<String> validIds = validConditionIds();
        Set<String> seen = new LinkedHashSet<>(enabledConditions.size());
        for (String id : enabledConditions) {
            if (id == null || !validIds.contains(id) || !seen.add(id)) {
                return Optional.empty();
            }
        }
        if (!this.sampleCounts.contains(params.sampleCount())) {
            return Optional.empty();
        }
        if (tool(params.toolId()) == null) {
            return Optional.empty();
        }
        for (Map.Entry<ResourceLocation, Integer> entry : params.toolEnchantments().entrySet()) {
            Integer maxLevel = this.enchantmentMaxLevels.get(entry.getKey());
            if (maxLevel == null || entry.getValue() > maxLevel) {
                return Optional.empty();
            }
        }
        return Optional.of(new SimulationInput(List.copyOf(seen), params));
    }

    private Set<String> validConditionIds() {
        Set<String> ids = new LinkedHashSet<>(this.conditions.size());
        for (ConditionOption option : this.conditions) {
            ids.add(option.id());
        }
        return ids;
    }

    /**
     * 状态标记用的紧凑描述。
     */
    public String describe() {
        Map<String, Integer> levels = new LinkedHashMap<>();
        this.enchantmentMaxLevels.forEach((id, level) -> levels.put(id.toString(), level));
        return "conditions=" + this.conditions.size()
                + ",tools=" + this.tools.size() + ",ench=" + levels
                + ",samples=" + this.sampleCounts;
    }
}
