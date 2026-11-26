package com.meteorite.unsuspiciousblock.client.ui.tooltip;

import com.meteorite.unsuspiciousblock.core.text.TooltipBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 考古笔记 Tooltip 数据模型。
 *
 * <p>统一承载标准 Minecraft {@link Component} 文本行与可选原生/自定义 {@link TooltipComponent} 预览图
 * （如标本箱 5 格缩略图），严格消费 {@link TooltipBuilder} 暗底语义色并交由 {@link JournalRichTooltip} 渲染。</p>
 */
public record JournalTooltipSpec(List<Component> lines, @Nullable TooltipComponent image) {

    /** 空提示常量。 */
    public static final JournalTooltipSpec EMPTY = new JournalTooltipSpec(List.of(), null);

    public JournalTooltipSpec {
        lines = lines.stream().map(line -> (Component) Objects.requireNonNull(line).copy()).toList();
    }

    /** 构造仅包含文本行的提示。 */
    public static JournalTooltipSpec of(List<Component> lines) {
        return lines.isEmpty() ? EMPTY : new JournalTooltipSpec(lines, null);
    }

    /** 构造包含文本行与可选图像组件的提示。 */
    public static JournalTooltipSpec of(List<Component> lines, @Nullable TooltipComponent image) {
        return lines.isEmpty() && image == null ? EMPTY : new JournalTooltipSpec(lines, image);
    }

    /**
     * @return 是否不包含任何可渲染内容
     */
    public boolean isEmpty() {
        return lines.isEmpty() && image == null;
    }

    /**
     * @return 当前客户端是否开启原版高级物品提示（{@code F3+H}）
     */
    public static boolean showAdvancedIds() {
        return Minecraft.getInstance().options.advancedItemTooltips;
    }

    /**
     * 创建新的构建器。
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Minecraft 风格 Tooltip 流式构建器，自动折叠首尾与连续空行。
     */
    public static final class Builder {
        private final List<Component> lines = new ArrayList<>();
        @Nullable
        private TooltipComponent image;
        private boolean pendingBlank;

        private Builder() {
        }

        /** 追加一行富文本。 */
        public Builder line(Component text) {
            if (text.getString().isEmpty()) {
                return blank();
            }
            flushBlank();
            lines.add(text.copy());
            return this;
        }

        /** 追加指定语义色的本地化或普通文本行。 */
        public Builder line(Component text, ChatFormatting color) {
            return line(text.copy().withStyle(color));
        }

        /** 追加分区标题行（使用 {@link TooltipBuilder#TITLE}）。 */
        public Builder section(Component title) {
            return line(title, TooltipBuilder.TITLE);
        }

        /** 追加「标签：值」语义行，外层使用 {@link TooltipBuilder#LABEL}。 */
        public Builder labelValue(String key, Component value) {
            return line(Component.translatable(key, value).withStyle(TooltipBuilder.LABEL));
        }

        /** 标记一个段间空行；首行之前与末尾不会产生多余空白，连续调用会合并。 */
        public Builder blank() {
            if (!lines.isEmpty()) {
                pendingBlank = true;
            }
            return this;
        }

        /** 附带原生或自定义 {@link TooltipComponent}（如标本箱预览图）。 */
        public Builder image(@Nullable TooltipComponent component) {
            if (component != null) {
                this.image = component;
            }
            return this;
        }

        /** 完成构建，返回不可变 {@link JournalTooltipSpec}。 */
        public JournalTooltipSpec build() {
            return of(lines, image);
        }

        private void flushBlank() {
            if (pendingBlank) {
                lines.add(CommonComponents.EMPTY);
                pendingBlank = false;
            }
        }
    }
}
