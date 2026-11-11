package com.meteorite.unsuspiciousblock.core.loottable.analysis;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;

/** 条件树与说明行共用的展示 Interface；逻辑消费者只接收 LootConditionInfo。 */
public sealed interface ConditionPresentation permits LootConditionInfo, ConditionDisplayRow {
    ResourceLocation conditionType();
    Component description();
    @Nullable Float probability();
    Map<String, String> metadata();
    List<? extends ConditionPresentation> displayChildren();
}
