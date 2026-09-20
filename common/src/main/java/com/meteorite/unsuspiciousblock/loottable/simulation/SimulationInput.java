package com.meteorite.unsuspiciousblock.loottable.simulation;

import java.util.Map;
import java.util.TreeMap;

/**
 * 模拟的**输入身份**——场景身份 + 条件布尔赋值 + 参数旋钮，三者共同决定"这个问题是什么"。
 * <p>
 * 为什么合成一个记录类（决策 5）：缓存键必须是整体。分场景与调参数是同一个问题的两个侧面，
 * 把它们拆成两个键会让"沼泽条件下的幸运 3"这类组合无处安放，也会让"切场景保留已填参数"
 * 在实现上退化成两份状态互相同步。
 * <p>
 * 不变量：{@code scenarioKey} 非空；{@code conditionOutcomes} 按指纹排序（{@link TreeMap}）且拒绝
 * {@code null}——排序让"同一个赋值"只有一种写法；参数侧的不变量见 {@link ScenarioParams}。
 * <p>
 * 本记录**不是**缓存键本身：键是 {@link SimulationInputKey} 给出的规范字符串。分开的理由是键要能
 * 跨进程比较（网络、存档），而记录类只需要在本进程内可比较。
 *
 * @param scenarioKey       场景的**稳定身份**：基准场景恒为 {@code baseline}，其余为 {@code scene-N}
 *                          （序号＝规划器的发射顺序，见
 *                          {@link SimulationScenarioPlanner#BASELINE_SCENARIO_KEY}）。
 *                          <p>
 *                          这里曾经存的是"条件赋值的规范编码"，即条件指纹串。指纹由条件对象的
 *                          {@code toString()} 派生，而 {@code entity_properties} / {@code location_check}
 *                          这类未按字段值生成文本的类型会退化成 {@code 类名@identityHash}，**跨 JVM 运行
 *                          不重复**。于是同一个场景每次启动都换一个新键：缓存永不命中（每次全量重算），
 *                          旧键又因为 LRU 按参数组合计数而永久留在存档里（既不被淘汰也不被覆盖）。
 *                          条件指纹**仍然是场景的运行时语义**（{@code conditionOutcomes}），只是不再进键。
 * @param conditionOutcomes 条件指纹 → 成立与否；空表示"条件全部不成立"的基准赋值。
 *                          它由场景身份唯一决定（服务端按已签发场景还原），因此不参与缓存键。
 * @param params            参数旋钮
 */
public record SimulationInput(String scenarioKey, Map<String, Boolean> conditionOutcomes,
                              ScenarioParams params) {
    public SimulationInput {
        if (scenarioKey == null || scenarioKey.isBlank()) {
            throw new IllegalArgumentException("模拟输入的场景身份不可为空");
        }
        if (conditionOutcomes == null || params == null) {
            throw new IllegalArgumentException("模拟输入的条件赋值与参数都不可为空");
        }
        Map<String, Boolean> sorted = new TreeMap<>();
        for (Map.Entry<String, Boolean> entry : conditionOutcomes.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                throw new IllegalArgumentException("条件指纹与成立与否都不可为空");
            }
            sorted.put(entry.getKey(), entry.getValue());
        }
        conditionOutcomes = Map.copyOf(sorted);
    }

    /** 本输入在缓存与网络里使用的规范键。 */
    public String key() {
        return SimulationInputKey.of(this);
    }
}
