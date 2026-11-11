package com.meteorite.unsuspiciousblock.core.loottable.analysis;

import net.minecraft.world.item.ItemStack;
import com.meteorite.unsuspiciousblock.core.loottable.analysis.LootConditionHandler.UncertaintyLevel;

/** 预览求值结果，区分可计算效果、上下文缺失和未支持能力。 */
public sealed interface FunctionPreviewResult {
    /** 已应用到隔离预览栈。 */
    record Applied(ItemStack stack) implements FunctionPreviewResult {}
    /** 需要实际上下文或随机值，不修改当前预览。 */
    record NeedsContext(UncertaintyLevel uncertainty) implements FunctionPreviewResult {}
    /** 当前静态预览不支持此规则。 */
    record Unsupported() implements FunctionPreviewResult {
        public static final Unsupported INSTANCE = new Unsupported();
    }
}
