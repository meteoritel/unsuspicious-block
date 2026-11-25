package com.meteorite.unsuspiciousblock.client.ui.tooltip;

import com.meteorite.meteosuikit.api.render.UiIcon;
import com.meteorite.unsuspiciousblock.core.text.TooltipBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 考古笔记富文本视觉 Tooltip 的不可变结构化模型。
 *
 * <p>将标题、状态胶囊、紧凑指标条、像素树行、双列按键表、分割线与页脚操作提示
 * 统一建模，由 {@link JournalRichTooltip} 在原版深色外框内进行无阴影图文混排，
 * 同时可导出为纯文本回退行列表供 {@code UiControl} / {@code UiNode} 与布局快照使用。</p>
 */
public final class JournalTooltipSpec {

    /**
     * 语义胶囊标签，用于展示状态或按键徽章。
     *
     * @param label 胶囊文字
     * @param color 语义色（取自 {@link TooltipBuilder} 亮色常量）
     */
    public record Pill(Component label, ChatFormatting color) {
        public Pill {
            label = Objects.requireNonNull(label).copy();
            Objects.requireNonNull(color);
        }
    }

    /**
     * 紧凑指标条中的单个图标数值芯片。
     *
     * @param icon     可选 {@code 10×10} 图标
     * @param text     指标文本
     * @param color    语义色
     * @param bordered 是否绘制芯片边框底衬
     */
    public record MetricChip(@Nullable UiIcon icon, Component text, ChatFormatting color, boolean bordered) {
        public MetricChip {
            text = Objects.requireNonNull(text).copy();
            Objects.requireNonNull(color);
        }
    }

    /**
     * 富 Tooltip 内容块密封接口。
     */
    public sealed interface Block permits Header, MetricRow, TextLine, TreeRow, KeyValueRow,
            Divider, NativeComponent, FooterHint {
    }

    /**
     * 首行标题块：可选图标 + 标题 + 可选右侧胶囊。
     */
    public record Header(@Nullable UiIcon icon, Component title, ChatFormatting titleColor,
                         @Nullable Pill badge) implements Block {
        public Header {
            title = Objects.requireNonNull(title).copy();
            Objects.requireNonNull(titleColor);
        }
    }

    /**
     * 横向指标芯片条，超宽时自动换行。
     */
    public record MetricRow(List<MetricChip> chips) implements Block {
        public MetricRow {
            chips = List.copyOf(chips);
        }
    }

    /**
     * 普通或带前导图标的文本行，支持自动折行。
     */
    public record TextLine(Component text, @Nullable UiIcon leadingIcon, int indentPx) implements Block {
        public TextLine {
            text = Objects.requireNonNull(text).copy();
        }
    }

    /**
     * 使用像素连线绘制的树形条目行。
     */
    public record TreeRow(int depth, boolean isLast, List<Boolean> ancestorContinues,
                          @Nullable UiIcon icon, Component text) implements Block {
        public TreeRow {
            ancestorContinues = List.copyOf(ancestorContinues);
            text = Objects.requireNonNull(text).copy();
        }
    }

    /**
     * 双列对齐行：左侧按键/前缀胶囊 + 右侧说明文本。
     */
    public record KeyValueRow(Component keyBadge, ChatFormatting keyColor, Component description) implements Block {
        public KeyValueRow {
            keyBadge = Objects.requireNonNull(keyBadge).copy();
            Objects.requireNonNull(keyColor);
            description = Objects.requireNonNull(description).copy();
        }
    }

    /**
     * 水平分割线。
     */
    public record Divider() implements Block {
        public static final Divider INSTANCE = new Divider();
    }

    /**
     * 内嵌原版或模组自定义 {@link TooltipComponent}（如标本箱预览图）。
     */
    public record NativeComponent(TooltipComponent component) implements Block {
        public NativeComponent {
            Objects.requireNonNull(component);
        }
    }

    /**
     * 底部操作提示行：自动带顶部分割线与动作胶囊。
     */
    public record FooterHint(@Nullable UiIcon icon, Component actionBadge, ChatFormatting badgeColor,
                             Component description) implements Block {
        public FooterHint {
            actionBadge = Objects.requireNonNull(actionBadge).copy();
            Objects.requireNonNull(badgeColor);
            description = Objects.requireNonNull(description).copy();
        }
    }

    private final List<Block> blocks;
    private final List<Component> plainLines;

    private JournalTooltipSpec(List<Block> blocks, List<Component> plainLines) {
        this.blocks = List.copyOf(blocks);
        this.plainLines = List.copyOf(plainLines);
    }

    /**
     * @return 所有内容块（不可变）
     */
    public List<Block> blocks() {
        return blocks;
    }

    /**
     * @return 对应的纯文本回退行列表
     */
    public List<Component> plainLines() {
        return plainLines;
    }

    /**
     * @return 是否不包含任何可渲染块
     */
    public boolean isEmpty() {
        return blocks.isEmpty();
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
     * 富 Tooltip 流式构建器。
     */
    public static final class Builder {
        private final List<Block> blocks = new ArrayList<>();
        private final List<Component> plainLines = new ArrayList<>();

        private Builder() {
        }

        /** 追加纯文字标题。 */
        public Builder header(Component title, ChatFormatting color) {
            return header(null, title, color, null);
        }

        /** 追加带状态胶囊的标题。 */
        public Builder header(Component title, ChatFormatting color, @Nullable Pill badge) {
            return header(null, title, color, badge);
        }

        /** 追加带图集图标与可选胶囊的标题。 */
        public Builder header(@Nullable UiIcon icon, Component title, ChatFormatting color, @Nullable Pill badge) {
            blocks.add(new Header(icon, title, color, badge));
            var line = title.copy().withStyle(color);
            if (badge != null) {
                line.append(Component.literal(" · ").withStyle(TooltipBuilder.LABEL))
                        .append(badge.label().copy().withStyle(badge.color()));
            }
            plainLines.add(line);
            return this;
        }

        /** 追加带物品缩略图标的标题。 */
        public Builder itemHeader(ItemStack stack, Component title, ChatFormatting color, @Nullable Pill badge) {
            UiIcon icon = stack.isEmpty() ? null : new UiIcon.Item(stack);
            return header(icon, title, color, badge);
        }

        /** 追加一行指标芯片。 */
        public Builder metrics(List<MetricChip> chips) {
            if (chips.isEmpty()) {
                return this;
            }
            blocks.add(new MetricRow(chips));
            var summary = Component.empty();
            for (int i = 0; i < chips.size(); i++) {
                if (i > 0) {
                    summary.append(Component.literal(" · ").withStyle(TooltipBuilder.LABEL));
                }
                MetricChip chip = chips.get(i);
                summary.append(chip.text().copy().withStyle(chip.color()));
            }
            plainLines.add(summary);
            return this;
        }

        /** 追加普通文本行。 */
        public Builder line(Component text) {
            return line(text, null, 0);
        }

        /** 追加带前导图标与缩进的文本行。 */
        public Builder line(Component text, @Nullable UiIcon leadingIcon, int indentPx) {
            blocks.add(new TextLine(text, leadingIcon, Math.max(0, indentPx)));
            plainLines.add(text.copy());
            return this;
        }

        /** 追加像素树节点行。 */
        public Builder treeRow(int depth, boolean isLast, List<Boolean> ancestorContinues,
                               @Nullable UiIcon icon, Component text) {
            blocks.add(new TreeRow(depth, isLast, ancestorContinues, icon, text));
            StringBuilder prefix = new StringBuilder();
            for (boolean cont : ancestorContinues) {
                prefix.append(cont ? "│  " : "   ");
            }
            if (depth > 0) {
                prefix.append(isLast ? "└─ " : "├─ ");
            }
            plainLines.add(Component.literal(prefix.toString()).withStyle(TooltipBuilder.HINT).append(text.copy()));
            return this;
        }

        /** 追加双列对齐键值行。 */
        public Builder keyValue(Component keyBadge, ChatFormatting keyColor, Component description) {
            blocks.add(new KeyValueRow(keyBadge, keyColor, description));
            plainLines.add(keyBadge.copy().withStyle(keyColor)
                    .append(Component.literal("  ").withStyle(TooltipBuilder.LABEL))
                    .append(description.copy()));
            return this;
        }

        /** 追加水平分割线。 */
        public Builder divider() {
            if (!blocks.isEmpty() && !(blocks.getLast() instanceof Divider)) {
                blocks.add(Divider.INSTANCE);
            }
            return this;
        }

        /** 追加自定义原版/模组 {@link TooltipComponent}。 */
        public Builder nativeComponent(@Nullable TooltipComponent component) {
            if (component != null) {
                blocks.add(new NativeComponent(component));
            }
            return this;
        }

        /** 追加底部操作提示。 */
        public Builder footer(@Nullable UiIcon icon, Component actionBadge, ChatFormatting badgeColor,
                              Component description) {
            blocks.add(new FooterHint(icon, actionBadge, badgeColor, description));
            plainLines.add(actionBadge.copy().withStyle(badgeColor)
                    .append(Component.literal(" ").withStyle(TooltipBuilder.LABEL))
                    .append(description.copy().withStyle(TooltipBuilder.HINT)));
            return this;
        }

        /** 完成构建。 */
        public JournalTooltipSpec build() {
            return new JournalTooltipSpec(blocks, plainLines);
        }
    }
}
