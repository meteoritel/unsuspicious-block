package com.meteorite.unsuspiciousblock.loottable.analysis;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 战利品条件静态分析结果——携带人类可读的条件描述与可选概率值。
 * <p>
 * conditionType 用于调用方通过 {@link LootConditionHandlers#get} 反查 handler，
 * 以判断该条件是否引入不确定性（{@link LootConditionHandler#addsUncertainty()}）。
 * <p>
 * children 支持树形结构，用于组合条件（all_of/any_of/inverted）的嵌套展示。
 */
public record LootConditionInfo(
        ResourceLocation conditionType,
        Component description,
        @Nullable Float probability,
        List<LootConditionInfo> children
) {
    public LootConditionInfo {
        if (conditionType == null) {
            throw new IllegalArgumentException("conditionType must not be null");
        }
        if (description == null) {
            throw new IllegalArgumentException("description must not be null");
        }
        if (children == null) {
            children = List.of();
        }
    }

    // 兼容旧调用方：无 children
    public LootConditionInfo(ResourceLocation conditionType, Component description, @Nullable Float probability) {
        this(conditionType, description, probability, List.of());
    }
}