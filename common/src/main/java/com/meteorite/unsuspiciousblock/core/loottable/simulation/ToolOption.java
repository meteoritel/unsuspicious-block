package com.meteorite.unsuspiciousblock.core.loottable.simulation;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.Set;

/**
 * 由服务端目录签发的工具基座——玩家只能在这些基座之间选，不能自由指定物品。
 *
 * @param id                     基座物品 id，同时是它在 {@link ScenarioParams#toolId()} 里的身份
 * @param displayName            基座物品的展示名（取物品自身的 hoverName，随语言变化）
 * @param predicateText          引用它的谓词原文；默认工具没有引用者，因此为 {@code null}
 * @param satisfiedConditionIds  该工具基座在真实 {@code MatchTool} 谓词下可满足的条件 ID 集合
 */
public record ToolOption(ResourceLocation id, Component displayName, @Nullable Component predicateText,
                         Set<String> satisfiedConditionIds) {
    public ToolOption {
        if (id == null || displayName == null) {
            throw new IllegalArgumentException("工具基座的 id 与展示名不可为空");
        }
        satisfiedConditionIds = satisfiedConditionIds == null ? Set.of() : Set.copyOf(satisfiedConditionIds);
    }

    /** 兼容构造：未预求值工具匹配条件的基座选项。 */
    public ToolOption(ResourceLocation id, Component displayName, @Nullable Component predicateText) {
        this(id, displayName, predicateText, Set.of());
    }
}
