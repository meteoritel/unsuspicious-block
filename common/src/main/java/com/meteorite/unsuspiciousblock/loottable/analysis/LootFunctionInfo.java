package com.meteorite.unsuspiciousblock.loottable.analysis;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 单个战利品函数的**结构化静态描述**——静态规则侧的唯一权威描述来源。
 * <p>
 * 它刻意与"预览求值"（{@link LootFunctionHandler#apply}）解耦：描述始终生成，
 * 预览成功与否都不影响描述的存在（规划 F02 / D15）。旧 {@code tooltipHint} 不再由
 * 各 handler 各拼一套，而是由本记录的 {@link #description()} 派生，避免第二套参数拼接逻辑。
 * <p>
 * 结构约束（规划 §4.2 / §4.5.3）：
 * <ul>
 *   <li><b>不包含执行次数</b>——本记录是"声明了什么"，运行时观测另存；</li>
 *   <li><b>不修改结果签名</b>——签名仍由既有派生路径决定；</li>
 *   <li>{@code conditions} 是**函数自身**的条件，原位保存在这里，不再并入物品的
 *       {@code entryConditions}（规划 F04：函数条件失败通常不代表原物品不掉落）；</li>
 *   <li>{@code children} 表达包装层次（sequence / filtered / reference 的内层），
 *       顺序与重复次数有意义；</li>
 *   <li>{@code metadata} 是**有界稳定键值**，供去重与网络传输使用，不存放无限制任意 JSON；
 *       值是语言无关的机器可读文本，不参与本地化。</li>
 * </ul>
 */
public record LootFunctionInfo(
        ResourceLocation functionType,
        Component description,
        FunctionFidelity fidelity,
        FunctionEffectKind effect,
        List<LootConditionInfo> conditions,
        List<LootFunctionInfo> children,
        Map<String, String> metadata) {

    /** 网络展示中每个节点的子函数上限；服务端语义树不按此裁剪。 */
    public static final int MAX_CHILDREN_PER_NODE = 64;
    /** 展示裁剪标记；原因包括 children / siblings / depth。 */
    public static final String METADATA_TRUNCATED = "truncated";
    /** 静态函数语义未完整分析时置位；场景规划不得把缺失条件当成无条件。 */
    public static final String METADATA_ANALYSIS_INCOMPLETE = "analysis_incomplete";
    /** 已结合输入物品证明无效果的节点，普通 tooltip 可省略。 */
    public static final String METADATA_DISPLAY_NO_OP = "display_no_op";

    public LootFunctionInfo {
        if (functionType == null) {
            throw new IllegalArgumentException("functionType must not be null");
        }
        if (description == null) {
            throw new IllegalArgumentException("description must not be null");
        }
        if (fidelity == null) {
            fidelity = FunctionFidelity.UNRESOLVED;
        }
        if (effect == null) {
            effect = FunctionEffectKind.UNKNOWN;
        }
        conditions = conditions == null ? List.of() : List.copyOf(conditions);
        children = children == null ? List.of() : List.copyOf(children);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    // 常用短构造：无函数条件、无子函数、无元数据
    public LootFunctionInfo(ResourceLocation functionType, Component description,
                            FunctionFidelity fidelity, FunctionEffectKind effect) {
        this(functionType, description, fidelity, effect, List.of(), List.of(), Map.of());
    }

    // 无子函数与元数据的有条件形式
    public LootFunctionInfo(ResourceLocation functionType, Component description,
                            FunctionFidelity fidelity, FunctionEffectKind effect,
                            List<LootConditionInfo> conditions) {
        this(functionType, description, fidelity, effect, conditions, List.of(), Map.of());
    }

    // 追加一条机器可读元数据；展示文本保持不变
    public LootFunctionInfo withMetadata(String key, String value) {
        LinkedHashMap<String, String> merged = new LinkedHashMap<>(this.metadata);
        merged.put(key, value);
        return new LootFunctionInfo(this.functionType, this.description, this.fidelity,
                this.effect, this.conditions, this.children, merged);
    }

    // 覆盖描述文本（本地化拼装推迟到展示边界时使用）
    public LootFunctionInfo withDescription(Component value) {
        return new LootFunctionInfo(this.functionType, value, this.fidelity,
                this.effect, this.conditions, this.children, this.metadata);
    }

    // 原位补挂函数自身的条件——函数条件与物品条目条件必须分开保存（规划 F04）
    public LootFunctionInfo withConditions(List<LootConditionInfo> value) {
        return new LootFunctionInfo(this.functionType, this.description, this.fidelity,
                this.effect, value, this.children, this.metadata);
    }

    /** 是否解析成功（供 UI 决定是否显示"未解析"降级文案）。 */
    public boolean isResolved() {
        return this.fidelity != FunctionFidelity.UNRESOLVED;
    }

    /** 该函数是否带自己的条件——函数条件与产物出现条件必须分开解释。 */
    public boolean hasConditions() {
        return !this.conditions.isEmpty();
    }

}
