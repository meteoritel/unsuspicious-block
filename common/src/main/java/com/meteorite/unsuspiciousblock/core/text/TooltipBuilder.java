package com.meteorite.unsuspiciousblock.core.text;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.List;
import java.util.function.Consumer;

/**
 * 统一物品 tooltip 构建器——本 mod 全部 tooltip 文本样式的唯一出口。
 *
 * <p>行序约定（五段式）：WIP 横幅（如有，永远置顶）→ 简介行 → 动态状态行 →
 * 操作提示 / 风险警告行 → Shift 展开详情。物品侧约定先调用 {@code super.appendHoverText} 再追加自定义内容。
 *
 * <p>仅依赖共享类（Component / ChatFormatting），common 代码可安全引用；
 * {@link #expandable} 与 {@link #wrapped} 经 {@link ClientTooltipBridge} 桥接客户端能力，
 * 专用服务端未安装实现时取安全缺省值，不加载任何客户端类。
 */
public final class TooltipBuilder {

    // ===== 语义色表：语义 → 颜色，禁止在别处直接挑选颜色 =====

    /** 分区标题、关键数值。 */
    public static final ChatFormatting TITLE = ChatFormatting.GOLD;
    /** 突出正文 / 关键状态值。 */
    public static final ChatFormatting BODY = ChatFormatting.WHITE;
    /** 简介、状态标签、次要描述。 */
    public static final ChatFormatting LABEL = ChatFormatting.GRAY;
    /**
     * 操作提示 / 未激活内容。
     * <p>
     * 用 {@code GRAY} 而不是 {@code DARK_GRAY}：tooltip 背景是接近黑的深色，{@code DARK_GRAY} 在其上
     * 只有约 1.9:1 的对比度，几乎读不出来——而这里承载的恰恰是"为什么没有数字"或操作引导这类必须读到的信息。
     * 与 {@link #LABEL} 同色是刻意的取舍：可读性优先于层级装饰，交互重点通过 {@link #ACCENT} 高亮按键/动作词区分。
     */
    public static final ChatFormatting HINT = ChatFormatting.GRAY;
    /** 正面 / 已解锁 / 增益。 */
    public static final ChatFormatting POSITIVE = ChatFormatting.GREEN;
    /** 负面 / 警告 / 损失。 */
    public static final ChatFormatting NEGATIVE = ChatFormatting.RED;
    /** 严重负面（诅咒等）。 */
    public static final ChatFormatting SEVERE = ChatFormatting.DARK_RED;
    /** 特殊强调（按键名、残存命数、特殊适用条件）。 */
    public static final ChatFormatting ACCENT = ChatFormatting.AQUA;
    /** 归属者 / 物品名。 */
    public static final ChatFormatting NAME = ChatFormatting.YELLOW;
    /** 满级 / 稀有巅峰状态（如猫国挚友）。 */
    public static final ChatFormatting SPECIAL = ChatFormatting.LIGHT_PURPLE;

    // ===== 条件树语义别名：只复用上面既有色值，不新增颜色 =====

    /** 条件树：可静态判定的条件。 */
    public static final ChatFormatting CONDITION_STATIC = POSITIVE;
    /** 条件树：概率型条件。 */
    public static final ChatFormatting CONDITION_PROBABILISTIC = TITLE;
    /** 条件树：运行时才能判定的条件。 */
    public static final ChatFormatting CONDITION_RUNTIME = NAME;
    /** 条件树：未被分析层识别的条件。 */
    public static final ChatFormatting CONDITION_UNREADABLE = LABEL;

    /** 默认最大折行像素宽度，兼顾 854×480 窗口与阅读节奏。 */
    public static final int DEFAULT_MAX_LINE_WIDTH = 200;

    /** 通用 WIP 横幅键。 */
    public static final String WIP_KEY = "tooltip.unsuspiciousblock.wip";
    /** 通用 Shift 展开提示键（占位符接收高亮后的 Shift 键名）。 */
    public static final String EXPAND_HINT_KEY = "tooltip.unsuspiciousblock.expand_hint";
    /** 通用 Shift 键名文本。 */
    public static final String SHIFT_KEY_LABEL = "Shift";

    private final List<Component> lines;

    /** 包装目标行列表，向其中按五段式约定追加内容。 */
    public TooltipBuilder(List<Component> lines) {
        this.lines = lines;
    }

    /** WIP 横幅：RED + ITALIC，必须为 tooltip 首行。 */
    public void wip() {
        lines.add(Component.translatable(WIP_KEY).withStyle(NEGATIVE, ChatFormatting.ITALIC));
    }

    /** 简介行：说明这是什么 / 怎么用。 */
    public void intro(String key, Object... args) {
        wrapped(Component.translatable(key, args).withStyle(LABEL));
    }

    /** 状态行：运行时变化的数据（整行标签色）。 */
    public void status(String key, Object... args) {
        lines.add(Component.translatable(key, args).withStyle(LABEL));
    }

    /** 标签与值分离的状态行：外层保持 {@link #LABEL}，占位符内传入各自上色后的值。 */
    public void labelValue(String key, Component value) {
        lines.add(Component.translatable(key, value).withStyle(LABEL));
    }

    /** 操作提示行：按键、右键等交互提示（高亮按键或动作由调用方嵌入 {@link #ACCENT}）。 */
    public void hint(String key, Object... args) {
        wrapped(Component.translatable(key, args).withStyle(HINT));
    }

    /** 风险警告行：以 {@code ⚠ } 前缀与 {@link #NEGATIVE} 醒目提示破坏性后果或代价。 */
    public void warning(String key, Object... args) {
        wrapped("⚠ ", "  ", Component.translatable(key, args).withStyle(NEGATIVE));
    }

    /** 列表条目行：以统一项目符号开头并支持安全折行。 */
    public void bullet(String key, ChatFormatting color, Object... args) {
        wrapped("• ", "  ", Component.translatable(key, args).withStyle(color));
    }

    /** 分区标题：Shift 展开详情的区块头。 */
    public void section(String key) {
        lines.add(Component.translatable(key).withStyle(TITLE));
    }

    /** 追加空行用于分区分隔。 */
    public void blank() {
        lines.add(CommonComponents.EMPTY);
    }

    /** 追加一条已构建好的行（用于混排多种语义色的行）。 */
    public void add(Component line) {
        lines.add(line);
    }

    /** 按默认宽度自动折行追加单条富文本。 */
    public void wrapped(Component text) {
        wrapped("", "", text);
    }

    /** 按默认宽度自动折行追加带首行前缀与续行缩进的富文本。 */
    public void wrapped(String firstPrefix, String continuationPrefix, Component text) {
        List<Component> split = ClientTooltipBridge.splitLines(text, DEFAULT_MAX_LINE_WIDTH);
        for (int i = 0; i < split.size(); i++) {
            String prefix = i == 0 ? firstPrefix : continuationPrefix;
            if (prefix.isEmpty()) {
                lines.add(split.get(i));
            } else {
                ChatFormatting prefixColor = text.getStyle().getColor() != null
                        ? null : LABEL;
                MutableComponent line = Component.literal(prefix);
                if (prefixColor != null) {
                    line.withStyle(prefixColor);
                } else {
                    line.withStyle(text.getStyle());
                }
                line.append(split.get(i));
                lines.add(line);
            }
        }
    }

    /** Shift 展开区块：按下 Shift 时执行 details 填充明细，否则显示高亮后的通用展开提示。 */
    public void expandable(Consumer<TooltipBuilder> details) {
        if (ClientTooltipBridge.isExpandKeyDown()) {
            details.accept(this);
        } else {
            Component shiftKey = Component.literal(SHIFT_KEY_LABEL).withStyle(ACCENT);
            lines.add(Component.translatable(EXPAND_HINT_KEY, shiftKey).withStyle(HINT));
        }
    }
}