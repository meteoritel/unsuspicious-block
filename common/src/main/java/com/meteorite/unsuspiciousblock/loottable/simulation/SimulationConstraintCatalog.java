package com.meteorite.unsuspiciousblock.loottable.simulation;

import com.meteorite.unsuspiciousblock.loottable.analysis.LootConditionHandlers;
import com.meteorite.unsuspiciousblock.loottable.analysis.LootConditionInfo;
import com.meteorite.unsuspiciousblock.loottable.catalog.ParameterKind;
import com.meteorite.unsuspiciousblock.loottable.graph.RuntimeLootLinks;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 由表内容派生出的**约束描述**——它只回答"这张表能调什么、引用了什么"，
 * **不生成任何输入组合**（决策 32）。
 * <p>
 * 这是本阶段最重要的一处结构性改动。首版计划让目录枚举"场景 × 基座工具 × 附魔等级赋值"的完整候选集合，
 * 但那是一个**乘积**：32 的上限只约束其中一维，6 个附魔各 4 档就是 4⁶ × 32 = 131,072 个组合。
 * 关键认识是缓存 LRU 限制的是**结果数量**，限制不了这部分**计算**——所以必须从源头取消枚举：
 * 目录发布约束，单个输入在请求时构造并由 {@link #resolve} 校验。
 * <p>
 * 于是"目录"在这里退化成四份清单：场景候选（≤32，附截断数量）、工具基座、被引用附魔的等级上限、
 * 抽样次数档位。所有越权取值（自造场景、超界幸运、未签发的档位或附魔）都在
 * {@link #resolve} 处被拒绝，而不是靠客户端自觉。
 *
 * @param scenarios               代表场景（含基准），≤ {@link SimulationScenarioPlanner#MAX_SCENARIOS}
 * @param truncatedScenarioCount  因超出上界而未列出的场景数；{@code 0} 表示全部列出
 * @param scenarioBudgetExhausted 是否有路径因条件树展开超预算而按"无约束"降级
 * @param tools                   工具基座清单，第一项恒为默认工具
 * @param enchantmentMaxLevels    该表子树实际引用到的附魔 → 其 {@code max_level}；等级旋钮的范围就是 0..它
 * @param sampleCounts            签发的抽样次数档位
 * @param childEntryGates         该表各子表入口的**注入门槛**描述（子表 id → 条件树）。
 *                                注入边不在任何 JSON 里，静态投影看不到它，因此这条说明只能来自
 *                                约束描述：父表页据此说明"进这张子表需要什么"，并让"零命中"能报成
 *                                「需要条件」而不是「未命中」
 */
public record SimulationConstraintCatalog(
        List<SimulationScenario> scenarios,
        int truncatedScenarioCount,
        boolean scenarioBudgetExhausted,
        List<ToolOption> tools,
        Map<ResourceLocation, Integer> enchantmentMaxLevels,
        List<Integer> sampleCounts,
        Map<ResourceLocation, List<LootConditionInfo>> childEntryGates) {

    public SimulationConstraintCatalog {
        scenarios = List.copyOf(scenarios);
        tools = List.copyOf(tools);
        enchantmentMaxLevels = Map.copyOf(enchantmentMaxLevels);
        sampleCounts = List.copyOf(sampleCounts);
        Map<ResourceLocation, List<LootConditionInfo>> gates = new LinkedHashMap<>();
        childEntryGates.forEach((childTable, conditions) ->
                gates.put(childTable, List.copyOf(conditions)));
        childEntryGates = Map.copyOf(gates);
        if (tools.isEmpty()) {
            throw new IllegalArgumentException("工具基座清单至少要含默认工具");
        }
    }

    /**
     * 由场景规划结果与表内容派生约束描述。
     *
     * @param baseProfile        该表的基座 profile（默认工具由它给出，与基准输入同源）
     * @param subtreeTools       子树内 {@code match_tool} 谓词可枚举出的基座物品 → 谓词原文
     * @param enchantmentMaxLevels 子树内被引用附魔 → {@code max_level}
     * @param childEntryGates    各子表入口的注入门槛描述（见 record 说明）
     */
    public static SimulationConstraintCatalog build(SimulationScenarioPlanner.ScenarioPlan plan,
                                                    SimulationProfile baseProfile,
                                                    Map<ResourceLocation, String> subtreeTools,
                                                    Map<ResourceLocation, Integer> enchantmentMaxLevels,
                                                    Map<ResourceLocation, List<LootConditionInfo>> childEntryGates) {
        List<ToolOption> tools = new ArrayList<>(subtreeTools.size() + 1);
        tools.add(defaultTool(baseProfile));
        for (Map.Entry<ResourceLocation, String> entry : subtreeTools.entrySet()) {
            if (entry.getKey().equals(baseProfile.toolItemId())) {
                // 默认工具已在首位，谓词原文仍要保留——它才是"这个工具为什么出现在这里"的说明
                tools.set(0, new ToolOption(entry.getKey(), tools.getFirst().displayName(),
                        Component.literal(entry.getValue())));
                continue;
            }
            tools.add(new ToolOption(entry.getKey(),
                    new ItemStack(BuiltInRegistries.ITEM.get(entry.getKey())).getHoverName().copy(),
                    Component.literal(entry.getValue())));
        }
        return new SimulationConstraintCatalog(plan.scenarios(), plan.truncatedCount(),
                plan.budgetExhausted(), tools, enchantmentMaxLevels,
                ScenarioParams.SAMPLE_COUNT_TIERS, childEntryGates);
    }

    /**
     * 把一个注入门槛翻译成条件树描述——与玩法同源：门槛声明 → 同一个条件对象 → 同一份分析处理器。
     * 因此 tooltip 上写的等级/概率不可能与实际判定走两套数字。
     */
    public static List<LootConditionInfo> describeGate(RuntimeLootLinks.InjectionGate gate,
                                                       HolderLookup.Provider registries) {
        return List.copyOf(LootConditionHandlers.analyzeAll(List.of(gate.condition(registries))));
    }

    /** 默认工具基座——与基准输入用的是同一份 profile，两者不会漂移。 */
    public static ToolOption defaultTool(SimulationProfile baseProfile) {
        ItemStack tool = baseProfile.tool();
        return new ToolOption(baseProfile.toolItemId(), tool.getHoverName().copy(), null);
    }

    /** 基准参数：默认工具 + 幸运 0 + 基准档位 + 无附魔（决策 17）。 */
    public ScenarioParams baselineParams() {
        return ScenarioParams.baseline(this.tools.getFirst().id());
    }

    /** 基准场景——条件全部不成立的那个场景（决策 2）；规划为空时返回 {@code null}。 */
    @Nullable
    public SimulationScenario baselineScenario() {
        for (SimulationScenario scenario : this.scenarios) {
            if (scenario.baseline()) {
                return scenario;
            }
        }
        return this.scenarios.isEmpty() ? null : this.scenarios.getFirst();
    }

    /**
     * 启动时每表只跑的那一个输入（决策 17/32）：基准场景的条件全假赋值 + 基准参数。
     * <p>
     * 条件赋值取自基准场景的 profile（已补齐该表全部指纹），因此它就是"条件全部不成立"那个赋值；
     * 由服务端自己还原赋值而不是让客户端传指纹，是为了让"这个输入是否被目录签发"变成一个查表动作。
     * <p>
     * 场景键用基准场景自己的稳定键；无场景表（理论上不该发生）兜底为
     * {@link SimulationScenarioPlanner#BASELINE_SCENARIO_KEY} + 空赋值——没有指纹时基准赋值本就是空的。
     */
    public SimulationInput baselineInput() {
        SimulationScenario scenario = baselineScenario();
        if (scenario == null) {
            return new SimulationInput(SimulationScenarioPlanner.BASELINE_SCENARIO_KEY,
                    Map.of(), baselineParams());
        }
        return new SimulationInput(scenario.key(), scenario.profile().conditionOutcomes(),
                baselineParams());
    }

    /** 按 key 取已签发的场景；未签发时返回 {@code null}。 */
    @Nullable
    public SimulationScenario scenario(String scenarioKey) {
        for (SimulationScenario scenario : this.scenarios) {
            if (scenario.key().equals(scenarioKey)) {
                return scenario;
            }
        }
        return null;
    }

    /** 按 id 取已签发的工具基座；未签发时返回 {@code null}。 */
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
     * <p>
     * 前三个恒可见；{@code ENCHANT_LEVEL} **只在该表子树确实引用了附魔时**才出现——
     * 不会出现"没被引用却给控件"的情形（决策 23/26）。
     */
    public List<ParameterKind> parameterKinds() {
        List<ParameterKind> kinds = new ArrayList<>(4);
        kinds.add(ParameterKind.LUCK);
        kinds.add(ParameterKind.TOOL);
        if (!this.enchantmentMaxLevels.isEmpty()) {
            kinds.add(ParameterKind.ENCHANT_LEVEL);
        }
        kinds.add(ParameterKind.SAMPLE_COUNT);
        return List.copyOf(kinds);
    }

    /**
     * 校验一份请求是否落在本目录签发的取值范围内（决策 15：服务端只接受目录签发的输入）。
     * <p>
     * 逐项判定，任何一项越权即整体拒绝——**不做部分修正**：把一个越权值悄悄改成最近合法值，
     * 会让玩家看到的数字与他填的参数不一致，那比直接拒绝更坏。
     *
     * @return 通过校验的输入；任一项不合法时返回空
     */
    public Optional<SimulationInput> resolve(String scenarioKey, ScenarioParams params) {
        SimulationScenario scenario = scenario(scenarioKey);
        if (scenario == null) {
            return Optional.empty();
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
        // 条件赋值由服务端按已签发场景还原，客户端不传指纹——它只挑场景，不构造赋值。
        // 场景键取自场景自身的稳定身份，绝不从条件指纹反算：指纹跨运行不稳定，用它做键会让
        // 同一个场景每次启动换一个键，缓存永不命中。
        return Optional.of(new SimulationInput(scenario.key(),
                scenario.profile().conditionOutcomes(), params));
    }

    /** 状态标记用的紧凑描述。 */
    public String describe() {
        Map<String, Integer> levels = new LinkedHashMap<>();
        this.enchantmentMaxLevels.forEach((id, level) -> levels.put(id.toString(), level));
        return "scenarios=" + this.scenarios.size()
                + (this.truncatedScenarioCount > 0 ? "(+" + this.truncatedScenarioCount + " truncated)" : "")
                + ",tools=" + this.tools.size() + ",ench=" + levels
                + ",samples=" + this.sampleCounts;
    }
}
