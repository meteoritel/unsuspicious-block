package com.meteorite.unsuspiciousblock.loottable.simulation;

import net.minecraft.resources.ResourceLocation;

import java.util.Map;

/**
 * {@link SimulationInput} ↔ 稳定字符串（决策 37）。
 * <p>
 * 键里**不出现玩家身份**：任何输入只要规范编码相同就复用同一份结果，无论由谁触发、由谁算出。
 * 模拟输入相同则统计口径相同（不承诺逐位相同——模拟用服务端共享的 `RandomSource`，
 * 缓存的语义本就是"一份抽样估计"）。
 * <p>
 * 编码形态是 `字段=值` 用 `|` 连接、字段顺序固定：
 * <pre>
 * scenario=scene-3|luck=3.00|tool=minecraft:shears|ench=minecraft:fortune:3,minecraft:silk_touch:1|n=10000
 * </pre>
 * 四处细节是刻意的：
 * <ul>
 *   <li>场景段是**稳定身份**（{@code baseline} 或 {@code scene-N}），直接取自
 *       {@code SimulationScenario.key()}。它**不是**条件指纹的编码：指纹跨 JVM 运行可能不重复，
 *       用它做键会让同一个场景每次启动换一个新键，缓存永远不命中（见
 *       {@link SimulationInput#scenarioKey()}）；</li>
 *   <li>幸运按 {@code %.2f} 渲染：参数在构造时已量化到 0.01，因此"玩家填的门槛"与"算出的门槛"
 *       得到同一个键，不会因为浮点尾巴分成两条缓存；</li>
 *   <li>附魔按 id 排序、等级逐个写出：{@code Map} 的迭代顺序不可依赖；</li>
 *   <li>条件赋值**不进键**——它由场景身份唯一决定（服务端按已签发场景还原），写进来只是重复一段
 *       不稳定信息。</li>
 * </ul>
 * 本类不提供反解析：网络请求传的是**结构化字段**，服务端据此重新构造
 * {@link SimulationInput} 并按目录约束逐项校验（决策 15），比解析一个自造字符串更不容易出错。
 */
public final class SimulationInputKey {
    private static final char FIELD_SEPARATOR = '|';

    private SimulationInputKey() {
    }

    /** 生成规范键。 */
    public static String of(SimulationInput input) {
        ScenarioParams params = input.params();
        StringBuilder builder = new StringBuilder(96);
        builder.append("scenario=").append(input.scenarioKey());
        builder.append(FIELD_SEPARATOR).append("luck=")
                .append(String.format(java.util.Locale.ROOT, "%.2f", params.luck()));
        builder.append(FIELD_SEPARATOR).append("tool=").append(params.toolId());
        builder.append(FIELD_SEPARATOR).append("ench=");
        appendEnchantments(builder, params.toolEnchantments());
        builder.append(FIELD_SEPARATOR).append("n=").append(params.sampleCount());
        return builder.toString();
    }

    /**
     * 输入的**参数组合**键——把抽样次数那一段去掉后的键（决策 46）。
     * <p>
     * 缓存淘汰按这个粒度计数：换幸运/工具/附魔是换**问题**，换抽样次数只是换**答案的精度**，
     * 高精度答案不该把问题本身挤出缓存。
     * <p>
     * 实现依赖 {@link #of} 的一个不变量：抽样次数段恒为最后一个字段。持久化的键全部由
     * {@code of} 产出，因此该不变量在读取路径上同样成立；键不是这个格式时（人为构造、
     * 或将来改了字段顺序）保守退回整个键——那样只会让淘汰更细，不会把不同问题合并成一个。
     */
    public static String parameterGroup(String inputKey) {
        int lastSeparator = inputKey.lastIndexOf(FIELD_SEPARATOR);
        if (lastSeparator <= 0) {
            return inputKey;
        }
        String lastField = inputKey.substring(lastSeparator + 1);
        return lastField.startsWith("n=") ? inputKey.substring(0, lastSeparator) : inputKey;
    }

    private static void appendEnchantments(StringBuilder builder, Map<ResourceLocation, Integer> levels) {
        boolean first = true;
        for (Map.Entry<ResourceLocation, Integer> entry : levels.entrySet()) {
            if (!first) {
                builder.append(',');
            }
            first = false;
            builder.append(entry.getKey()).append(':').append(entry.getValue());
        }
    }
}
