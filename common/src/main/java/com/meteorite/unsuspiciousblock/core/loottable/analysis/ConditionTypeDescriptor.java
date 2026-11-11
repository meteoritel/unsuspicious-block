package com.meteorite.unsuspiciousblock.core.loottable.analysis;

import net.minecraft.world.level.storage.loot.parameters.LootContextParam;

import java.util.Set;

/** 条件类型的共同登记；节点保真度仍由每次分析结果独立携带。 */
public record ConditionTypeDescriptor(LootConditionHandler handler, Set<Capability> capabilities,
                                      Set<LootContextParam<?>> requiredParameters) {
    /** 固定类型事实，避免消费者维护平行类型集合。 */
    public ConditionTypeDescriptor {
        capabilities = Set.copyOf(capabilities);
        requiredParameters = Set.copyOf(requiredParameters);
    }

    /** 能力可以组合，例如工具附魔既有门槛也有概率效果。 */
    public enum Capability { SCENARIO, TOOL, ENCHANTMENT_LEVEL, RANDOM, SECONDARY }

    /** 查询本类型登记的能力，未知类型由消费者保守处理。 */
    public boolean supports(Capability capability) {
        return capabilities.contains(capability);
    }

    /** handler 是类型默认不确定性的事实源，节点的 partial/unreadable 单独保留。 */
    public LootConditionHandler.UncertaintyLevel uncertaintyLevel() {
        return handler.uncertaintyLevel();
    }
}
