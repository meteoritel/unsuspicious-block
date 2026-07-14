package com.meteorite.unsuspiciousblock.loottable.analysis;

import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

/**
 * 战利品条件静态分析结果——携带人类可读的条件描述与可选概率值。
 * <p>
 * conditionType 用于调用方通过 {@link LootConditionHandlers#get} 反查 handler，
 * 以判断该条件是否引入不确定性（{@link LootConditionHandler#addsUncertainty()}）。
 */
public record LootConditionInfo(
        String conditionType,
        Component description,
        @Nullable Float probability
) {
    public LootConditionInfo {
        if (conditionType == null || conditionType.isBlank()) {
            throw new IllegalArgumentException("conditionType must not be blank");
        }
        if (description == null) {
            throw new IllegalArgumentException("description must not be null");
        }
    }
}