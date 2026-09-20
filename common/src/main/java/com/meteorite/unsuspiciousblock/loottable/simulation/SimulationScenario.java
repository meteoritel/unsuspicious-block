package com.meteorite.unsuspiciousblock.loottable.simulation;

import com.meteorite.unsuspiciousblock.loottable.analysis.LootConditionInfo;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Set;

/**
 * 一组自洽条件下的战利品模拟输入，以及该场景适用的解析期物品签名。
 *
 * @param key                     场景的**稳定身份**（{@code baseline} 或 {@code scene-N}，见
 *                                {@link SimulationScenarioPlanner#BASELINE_SCENARIO_KEY}），
 *                                同时用作缓存键的一段与网络标识。
 *                                它**不编码条件指纹**——指纹跨 JVM 运行可能不重复，写进键会让同一个场景
 *                                每次启动换一个键（见 {@link SimulationInput#scenarioKey()}）
 * @param profile                 该场景的基座 profile（条件赋值已就位）；输入的参数旋钮在模拟前套用它
 * @param assumptions             该场景假定的条件，供场景列表与 tooltip 展示
 * @param applicableSignatures    该场景下静态判定可达的物品签名存储键
 * @param applicableChildTables   该场景下静态判定可达的直接子表
 * @param baseline                是否为**基准场景**——条件全部不成立的场景（决策 2）。
 *                                网格只展示基准输入的数字，其余场景只在场景 Tab 与 tooltip 里出现。
 *                                某个表若因专门分支额外派生了场景（原版 fishing 的注入场景），
 *                                基准标记随派生一起保留，因此"基准"始终能唯一指认。
 * @param keepBaseTool            是否保留本场景自带的工具（注入场景为 {@code true}）。
 *                                注入场景把"满级钓竿"写进了场景定义本身——它的全部意义就是带着能通过
 *                                {@code tool_enchantment} 门槛的工具去抽注入池；若被输入的默认工具覆盖，
 *                                注入池永远抽空，注入条目就再也发现不了（那不是"参数生效"而是信息丢失）。
 *                                其余场景为 {@code false}，工具与附魔等级完全由参数决定。
 */
public record SimulationScenario(String key, SimulationProfile profile,
                                 List<LootConditionInfo> assumptions,
                                 Set<String> applicableSignatures,
                                 Set<ResourceLocation> applicableChildTables,
                                 boolean baseline,
                                 boolean keepBaseTool) {
    public SimulationScenario {
        assumptions = List.copyOf(assumptions);
        applicableSignatures = Set.copyOf(applicableSignatures);
        applicableChildTables = Set.copyOf(applicableChildTables);
    }

    /** 非基准、且工具由参数决定（绝大多数场景）的便利构造。 */
    public SimulationScenario(String key, SimulationProfile profile,
                              List<LootConditionInfo> assumptions,
                              Set<String> applicableSignatures,
                              Set<ResourceLocation> applicableChildTables) {
        this(key, profile, assumptions, applicableSignatures, applicableChildTables, false, false);
    }

    /** 基准标记显式给出、工具由参数决定的便利构造。 */
    public SimulationScenario(String key, SimulationProfile profile,
                              List<LootConditionInfo> assumptions,
                              Set<String> applicableSignatures,
                              Set<ResourceLocation> applicableChildTables,
                              boolean baseline) {
        this(key, profile, assumptions, applicableSignatures, applicableChildTables, baseline, false);
    }
}
