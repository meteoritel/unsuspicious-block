package com.meteorite.unsuspiciousblock.loottable.simulation;

import com.meteorite.unsuspiciousblock.loottable.catalog.SimulatedValue;
import com.meteorite.unsuspiciousblock.loottable.signature.LootResultSignature;
import net.minecraft.resources.ResourceLocation;

import java.util.Map;
import java.util.Set;

/**
 * 一次模拟的**原始测量值**——它只回答"某个输入下算出了多少"，不含任何由静态结构派生的结论。
 * <p>
 * 与旧实现的区别是本阶段（P1）最重要的结构变化：此前每次模拟产出的是一个已经填好展示概率的
 * {@code TableDefinition}，于是"算过什么"与"该显示什么"被绑在同一个对象上——想换一个输入看数字，
 * 就必须重算整张表。现在两者分开：
 * <ul>
 *   <li>本记录按 {@code (表哈希, 输入键)} 持久化，是**内容寻址的缓存条目**；</li>
 *   <li>展示用的 {@code TableDefinition} 由持有静态结构与测量值的服务端**当场派生**
 *       （见 {@code ArchaeologyJournalServerCatalog} 的派生路径），因此"从缓存恢复"与
 *       "刚刚算完"走的是同一条代码，不存在两套口径。</li>
 * </ul>
 * 每个签名一个 {@link SimulatedValue}：在**单次抽取中至少出现一次**的比例。抽样零命中是
 * {@code Measured(0.0)} 而不是 {@code Unknown}——"这条路径适用但这次没抽到"是测量事实，
 * 与"没算过"必须分得开（决策 40）。
 *
 * @param itemProbabilities       签名存储键 → 测量值；覆盖该输入下全部适用条目
 * @param childProbabilities      直接子表 → "该子表至少产出一个物品"的测量值
 * @param discoveredSignatures    模拟期动态发现的签名（GLM / LootTableEvents.MODIFY 注入的条目）
 * @param discoveredDirectly      其中直接在根表产出（而非来自子表）的签名存储键
 * @param discoveredChildSources  动态签名 → 它实际来自的直接子表
 */
public record SimulationMeasurement(Map<String, SimulatedValue> itemProbabilities,
                                    Map<ResourceLocation, SimulatedValue> childProbabilities,
                                    Map<String, LootResultSignature> discoveredSignatures,
                                    Set<String> discoveredDirectly,
                                    Map<String, ResourceLocation> discoveredChildSources) {
    public SimulationMeasurement {
        itemProbabilities = Map.copyOf(itemProbabilities);
        childProbabilities = Map.copyOf(childProbabilities);
        discoveredSignatures = Map.copyOf(discoveredSignatures);
        discoveredDirectly = Set.copyOf(discoveredDirectly);
        discoveredChildSources = Map.copyOf(discoveredChildSources);
    }

    /** 没有任何测量值（空表）的实例——恢复路径用它表示"这张表算过但什么都没测到"。 */
    public static SimulationMeasurement empty() {
        return new SimulationMeasurement(Map.of(), Map.of(), Map.of(), Set.of(), Map.of());
    }
}
