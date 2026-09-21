package com.meteorite.unsuspiciousblock.client.ui.kit;

import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** 唯一命中结果；矩形位于所属文档的内容坐标系，实例在排版时缓存。 */
public record UiTarget(Kind kind, int index, @Nullable Object payload, UiRect rect,
                       List<Component> tooltip, @Nullable UiAction action) {
    public UiTarget {
        tooltip = List.copyOf(tooltip);
    }

    /** 区分行与行内图标；图标优先于所属行命中。 */
    public enum Kind { ROW, ICON }
}
