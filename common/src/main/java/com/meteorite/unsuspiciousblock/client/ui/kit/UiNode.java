package com.meteorite.unsuspiciousblock.client.ui.kit;

import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/** 声明式块序列；仅 Frame 可拥有一层子内容，不实现通用嵌套容器。 */
public sealed interface UiNode permits UiNode.Row, UiNode.Gap, UiNode.Divider, UiNode.Frame {
    /** 树的根行没有父行。 */
    int NO_PARENT = -1;

    /**
     * 自然宽度的缩进行，文字后可跟多个各自带命中信息的图标。
     * {@code parentRow} 是同一内容列表中父行的下标（{@link #NO_PARENT} 表示根行）；
     * 连线几何由排版阶段按父子矩形推导，构建方只需给出父子关系。
     */
    record Row(int indent, int parentRow, Component text, int color, List<InlineIcon> icons,
               List<Component> tooltip, @Nullable Object payload, @Nullable UiAction action) implements UiNode {
        public Row {
            if (indent < 0) throw new IllegalArgumentException("Negative row indent");
            if (parentRow < NO_PARENT) throw new IllegalArgumentException("Invalid row parent");
            text = Objects.requireNonNull(text).copy();
            icons = List.copyOf(icons);
            tooltip = copyTooltip(tooltip);
        }

        public Row(Component text, int color) {
            this(0, NO_PARENT, text, color, List.of(), List.of(), null, null);
        }
    }

    /** 行内图标的独立交互信息；空提示仍会阻止同一位置回退到行提示。 */
    record InlineIcon(UiIcon icon, List<Component> tooltip, @Nullable Object payload, @Nullable UiAction action) {
        public InlineIcon {
            Objects.requireNonNull(icon);
            tooltip = copyTooltip(tooltip);
        }
    }

    /** 固定高度的空白。 */
    record Gap(int height) implements UiNode {
        public Gap {
            if (height < 0) throw new IllegalArgumentException("Negative gap");
        }
    }

    /** 宽度跟随文档视口的单像素分隔线。 */
    record Divider(int color) implements UiNode {}

    /** 固定大小的裁剪视口，变换对象由业务方持有以便保存场景状态；连线颜色归框所有（见 {@link Row#parentRow()}）。 */
    record FrameSpec(int width, int height, int branchColor, UiTransform transform) {
        public FrameSpec {
            if (width <= 0 || height <= 0) throw new IllegalArgumentException("Empty frame");
            Objects.requireNonNull(transform);
        }
    }

    /** 专用于条件树的内容视口；边框、框角按钮及输入优先级由面板管理。 */
    record Frame(FrameSpec spec, List<UiNode> content) implements UiNode {
        public Frame {
            Objects.requireNonNull(spec);
            content = List.copyOf(content);
            for (UiNode node : content) {
                if (node instanceof Frame) throw new IllegalArgumentException("Nested frames are not supported");
            }
        }
    }

    private static List<Component> copyTooltip(List<Component> tooltip) {
        return tooltip.stream().map(component -> (Component) component.copy()).toList();
    }
}
