package com.meteorite.unsuspiciousblock.client.tooltip;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.function.Consumer;

/**
 * 统一物品 tooltip 构建器——本 mod 全部 tooltip 文本样式的唯一出口。
 *
 * <p>行序约定（五段式）：WIP 横幅（如有，永远置顶）→ 简介行 → 动态状态行 →
 * 操作提示行 → Shift 展开详情。物品侧约定先调用 {@code super.appendHoverText} 再追加自定义内容。
 *
 * <p>仅依赖共享类（Component / ChatFormatting），common 代码可安全引用；
 * 仅 {@link #expandable} 使用客户端 Shift 按键检测，同样只在客户端 tooltip 渲染路径中被调用。
 */
public final class TooltipBuilder {

    // ===== 语义色表：语义 → 颜色，禁止在别处直接挑选颜色 =====

    /** 分区标题。 */
    public static final ChatFormatting TITLE = ChatFormatting.GOLD;
    /** 简介正文行。 */
    public static final ChatFormatting BODY = ChatFormatting.WHITE;
    /** 状态行 / 次要信息。 */
    public static final ChatFormatting LABEL = ChatFormatting.GRAY;
    /** 操作提示 / 未激活内容。 */
    public static final ChatFormatting HINT = ChatFormatting.DARK_GRAY;
    /** 正面 / 已解锁 / 增益。 */
    public static final ChatFormatting POSITIVE = ChatFormatting.GREEN;
    /** 负面 / 警告 / 损失。 */
    public static final ChatFormatting NEGATIVE = ChatFormatting.RED;
    /** 严重负面（诅咒等）。 */
    public static final ChatFormatting SEVERE = ChatFormatting.DARK_RED;
    /** 特殊强调（按键名、命数等）。 */
    public static final ChatFormatting ACCENT = ChatFormatting.AQUA;
    /** 归属者 / 物品名。 */
    public static final ChatFormatting NAME = ChatFormatting.YELLOW;

    /** 通用 WIP 横幅键。 */
    public static final String WIP_KEY = "tooltip.unsuspiciousblock.wip";
    /** 通用 Shift 展开提示键。 */
    public static final String EXPAND_HINT_KEY = "tooltip.unsuspiciousblock.expand_hint";

    private final List<Component> lines;

    // 包装目标行列表，向其中按五段式约定追加内容
    public TooltipBuilder(List<Component> lines) {
        this.lines = lines;
    }

    // WIP 横幅：RED + ITALIC，必须为 tooltip 首行
    public void wip() {
        lines.add(Component.translatable(WIP_KEY).withStyle(NEGATIVE, ChatFormatting.ITALIC));
    }

    // 简介行：说明这是什么 / 怎么用
    public void intro(String key, Object... args) {
        lines.add(Component.translatable(key, args).withStyle(ChatFormatting.GRAY));
    }

    // 状态行：运行时变化的数据（整行标签色）
    public void status(String key, Object... args) {
        lines.add(Component.translatable(key, args).withStyle(LABEL));
    }

    // 操作提示行：按键、右键等交互提示
    public void hint(String key, Object... args) {
        lines.add(Component.translatable(key, args).withStyle(HINT));
    }

    // 分区标题：Shift 展开详情的区块头
    public void section(String key) {
        lines.add(Component.translatable(key).withStyle(TITLE));
    }

    // 追加一条已构建好的行（用于混排多种语义色的行）
    public void add(Component line) {
        lines.add(line);
    }

    // Shift 展开区块：按下 Shift 时执行 details 填充明细，否则显示通用提示行
    public void expandable(Consumer<TooltipBuilder> details) {
        if (Screen.hasShiftDown()) {
            details.accept(this);
        } else {
            hint(EXPAND_HINT_KEY);
        }
    }
}
