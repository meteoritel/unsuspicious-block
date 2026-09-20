package com.meteorite.unsuspiciousblock.loottable.simulation;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/**
 * 由服务端目录**签发的**工具基座——玩家只能在这些基座之间选，不能自由指定物品。
 * <p>
 * 为什么不穷举 {@code match_tool} 谓词（决策 45）：{@code ItemPredicate} 可含任意物品、标签、
 * 组件、数量与子谓词，穷举既不可能也无意义。因此清单 = 默认工具 + 谓词里可枚举出的基座物品，
 * 其中**标签谓词只取首个成员**——这是数据包成员顺序决定的实现偶然，会造出"下拉里有金斧但没有铁斧"
 * 这类难以解释的缺失。代价被明确接受，补偿是同一条决策的第二个要求：
 * {@link #predicateText()} 必须带上**谓词原文**，提示里写清下拉不等于谓词允许的全集。
 * <p>
 * 由此产生一个刻意的行为：按决策 34，找不到联合见证的条目只渲染静态提示、不渲染按钮。
 * 在"取首个成员"下，未列入下拉的成员必然找不到见证，于是行为是**诚实的降级**，
 * 而不是挂着一个点不动的死按钮。
 *
 * @param id            基座物品 id，同时是它在 {@link ScenarioParams#toolId()} 里的身份
 * @param displayName   基座物品的展示名（取物品自身的 hoverName，随语言变化）
 * @param predicateText 引用它的谓词原文；默认工具没有引用者，因此为 {@code null}
 */
public record ToolOption(ResourceLocation id, Component displayName, @Nullable Component predicateText) {
    public ToolOption {
        if (id == null || displayName == null) {
            throw new IllegalArgumentException("工具基座的 id 与展示名不可为空");
        }
    }
}
