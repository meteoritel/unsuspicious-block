package com.meteorite.unsuspiciousblock.core.loottable.analysis;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;

/** 叶子条件的说明行，只参与展示，不参与条件求值、身份或场景规划。 */
public record ConditionDisplayRow(ResourceLocation conditionType, Component description,
                                  @Nullable Float probability, List<ConditionDisplayRow> displayChildren,
                                  Map<String, String> metadata) implements ConditionPresentation {
    /** 固定展示快照，避免调用方修改已发布目录。 */
    public ConditionDisplayRow {
        displayChildren = List.copyOf(displayChildren);
        metadata = Map.copyOf(metadata);
    }
}
