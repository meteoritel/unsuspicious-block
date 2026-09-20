package com.meteorite.unsuspiciousblock.loottable.analysis;

import com.google.gson.JsonElement;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Set;

/**
 * 单表的**上下文无关**编译产物——只记录本表直接物品路径与引用位置，不缓存展开后的子表结果。
 * <p>
 * 关键约束（见规划 §3.2）：同一个子表在不同引用位置下产出的物品路径不同（条件与函数被引用
 * 位置改写），因此这里只能保存"本表局部语义 + 引用位置描述"，由投影层在链接期把父表的
 * 继承条件和函数链拼上去。若缓存展开结果，第二个引用位置就会拿到错误的条件与函数。
 * <p>
 * 事件按 JSON 中的出现顺序排列，物品条目与引用位置混在同一个列表里——顺序有意义：
 * 物品按签名去重时"先出现者胜"，获取路径的顺序也与首次出现顺序一致。
 * <p>
 * 每个事件携带两个**本表局部**快照：{@code inheritedConditions}（自本表根向下累积的
 * 继承条件链，最外层在前）与 {@code inheritedFunctions}（自本表根向下累积的函数链，
 * 最内层在前）。二者都不含本事件自身的条目条件/函数，投影层按
 * {@code 本事件自身 ++ 局部快照 ++ 上层传入} 的顺序拼接。
 * <p>
 * {@code referencedEnchantments} 是编译期扫出的"本表 JSON 引用到的附魔"——
 * 附魔定义不在任何战利品表 JSON 里，却决定模拟用的满级工具与等级控件范围，
 * 因此它既进哈希摘要（决策 35），也是旋钮目录里等级控件的唯一来源（决策 26），
 * 两份用途共用这一份枚举，不再各扫一遍。
 */
public record CompiledLootTable(ResourceLocation id, String declaredType, List<Event> events,
                                Set<ResourceLocation> referencedEnchantments) {
    public CompiledLootTable {
        events = List.copyOf(events);
        referencedEnchantments = Set.copyOf(referencedEnchantments);
    }

    /** 兼容旧调用方：无被引用附魔。 */
    public CompiledLootTable(ResourceLocation id, String declaredType, List<Event> events) {
        this(id, declaredType, events, Set.of());
    }

    /** 编译事件——直接物品路径或子表引用位置。 */
    public sealed interface Event permits ItemPath, ReferenceSite {
        /** 本表内已继承的条件链（不含本事件自身的条件），最外层在前。 */
        List<LootConditionInfo> inheritedConditions();

        /** 本表内已继承的函数链（不含本事件自身的函数），最内层在前。 */
        List<JsonElement> inheritedFunctions();

        // 当前路径所在池的权重或奖励抽取次数受幸运影响。
        boolean luckAffected();
    }

    /**
     * 本表直接产出的物品路径。
     * 物品 tag 已在编译期展开为具体物品，因此 {@code sourceItemTag} 记录它来自哪个 tag。
     */
    public record ItemPath(ResourceLocation itemId,
                           @Nullable ResourceLocation sourceItemTag,
                           List<LootConditionInfo> entryConditions,
                           List<JsonElement> entryFunctions,
                           List<LootConditionInfo> inheritedConditions,
                           List<JsonElement> inheritedFunctions,
                           boolean luckAffected) implements Event {
        public ItemPath {
            entryConditions = List.copyOf(entryConditions);
            entryFunctions = List.copyOf(entryFunctions);
            inheritedConditions = List.copyOf(inheritedConditions);
            inheritedFunctions = List.copyOf(inheritedFunctions);
        }
    }

    /**
     * 子表引用位置——保留每一次出现，不去重也不合并。
     * <p>
     * 同一子表在两个位置被引用且 conditions / functions 不同时，两条获取路径必须各自保留；
     * 图上的拓扑边可以去重，但编译产物不行。
     */
    public record ReferenceSite(ResourceLocation target,
                                List<LootConditionInfo> siteConditions,
                                List<JsonElement> siteFunctions,
                                List<LootConditionInfo> inheritedConditions,
                                List<JsonElement> inheritedFunctions,
                                boolean luckAffected) implements Event {
        public ReferenceSite {
            siteConditions = List.copyOf(siteConditions);
            siteFunctions = List.copyOf(siteFunctions);
            inheritedConditions = List.copyOf(inheritedConditions);
            inheritedFunctions = List.copyOf(inheritedFunctions);
        }
    }
}
