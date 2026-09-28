package com.meteorite.unsuspiciousblock.client.ui.kit;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * 固定矩形的轻量控件，测量及命中结果只在配置改变时创建，不负责输入分发。
 *
 * <p>控件自带语义状态（普通/悬停/按下/选中/禁用/焦点）：颜色从 {@link UiControlStyle} 取，
 * 禁用与不可见的控件不参与命中、动作与焦点序列。状态只影响绘制与命中，不触发重新测量，
 * 因此状态变化不需要重建内容树。</p>
 *
 * <p>绘制只在文字或图标确实越出控件矩形时才设置裁剪，未越界时不提交绘制批次。</p>
 */
public final class UiControl implements UiFocusTarget {
    private UiRect bounds = new UiRect(0, 0, 0, 0);
    private Component label = Component.empty();
    private FormattedCharSequence text = FormattedCharSequence.EMPTY;
    private int textWidth;
    private int color;
    @Nullable private UiIcon icon;
    private List<Component> tooltip = List.of();
    @Nullable private UiAction action;
    @Nullable private Component accessibleName;
    private UiTarget target = new UiTarget(UiTarget.Kind.ROW, 0, null, bounds, List.of(), null);
    /** 标签超宽时的滚动计时：只在悬停期间推进，离开即归零。 */
    private int scrollTicks;
    private UiControlStyle style = UiControlStyle.PARCHMENT;
    private boolean enabled = true;
    private boolean visible = true;
    private boolean selected;
    private boolean focused;
    private boolean pressed;

    public void configure(Font font, Component label, int color, @Nullable UiIcon icon,
                          List<Component> tooltip, @Nullable UiAction action) {
        // 重建同一标签时不打断滚动：浮层按秒重配控件，逐次归零会看到每秒跳回起点。
        if (!this.label.getString().equals(label.getString())) this.scrollTicks = 0;
        this.label = label.copy();
        this.text = this.label.getVisualOrderText();
        this.textWidth = font.width(label);
        this.color = color;
        this.icon = icon;
        this.tooltip = List.copyOf(tooltip);
        this.action = action;
        // 动作被拿掉（标签化）时立刻取消焦点与按下态：两者都以「可激活」为前提。
        if (action == null) {
            this.focused = false;
            this.pressed = false;
        }
        rebuildTarget();
    }

    // 负宽高按 0 钳制（与 UiDocument.setViewport / UiScrollView 同口径）；零宽高控件不可命中。
    public void setBounds(int x, int y, int width, int height) {
        int w = Math.max(0, width);
        int h = Math.max(0, height);
        if (bounds.x() == x && bounds.y() == y && bounds.width() == w && bounds.height() == h) return;
        bounds = new UiRect(x, y, w, h);
        rebuildTarget();
    }

    public UiRect bounds() { return bounds; }

    public void setStyle(UiControlStyle style) { this.style = Objects.requireNonNull(style); }
    public UiControlStyle style() { return style; }

    // 禁用控件仍照常绘制（走禁用底色与淡化文字），但不命中、不激活、不进焦点序列。
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (!enabled) {
            pressed = false;
            focused = false;
        }
    }
    public boolean isEnabled() { return enabled; }

    // 不可见控件完全不绘制、不命中、不参与焦点；浮层分页窗口用它隐藏窗口外的行。
    public void setVisible(boolean visible) {
        this.visible = visible;
        if (!visible) {
            pressed = false;
            focused = false;
        }
    }
    public boolean isVisible() { return visible; }

    public void setSelected(boolean selected) { this.selected = selected; }
    public boolean isSelected() { return selected; }

    public void setFocused(boolean focused) { this.focused = focused && isFocusable(); }
    @Override public boolean isFocused() { return focused; }

    // 按下态是「这个目标可被激活」的反馈：纯标签（无 action）按住时不应显示按钮底色。
    public void setPressed(boolean pressed) { this.pressed = pressed && isFocusable(); }
    public boolean isPressed() { return pressed; }

    /** 可命中与可参与悬停的条件：禁用或不可见的目标不参与命中。 */
    public boolean isHittable() { return enabled && visible; }

    @Override public boolean canFocus() { return isFocusable(); }

    /** 供焦点调试与旁白使用的可读名称；未显式设置时按标签、再按首行提示回退。 */
    public void setAccessibleName(@Nullable Component name) {
        this.accessibleName = name == null ? null : name.copy();
    }

    @Override public @Nullable Component accessibleName() {
        if (accessibleName != null) return accessibleName;
        // 图标/符号按钮的 label 常为空或不具名，真正的名称只在提示里：回退到首行提示。
        if (!label.getString().isEmpty()) return label;
        return tooltip.isEmpty() ? label : tooltip.getFirst();
    }

    /**
     * 可进入键盘焦点序列的条件：可命中**且确实有动作**。
     * 纯标签（{@code action == null}）仍可命中、仍显示 tooltip，但不作为 Tab 停靠点——
     * 否则焦点序列会被只读文本占满，而它们激活后没有任何效果。
     */
    public boolean isFocusable() { return isHittable() && action != null; }

    private void rebuildTarget() {
        target = new UiTarget(icon == null ? UiTarget.Kind.ROW : UiTarget.Kind.ICON,
                0, label, bounds, tooltip, action);
    }

    @Nullable public UiTarget hit(double x, double y) {
        if (!isHittable()) return null;
        return bounds.contains(x, y) ? target : null;
    }

    /** 执行动作并把「是否消费」交还调用方；禁用、不可见或无动作时一律不消费。 */
    public boolean activate() {
        if (!isFocusable() || action == null) return false;
        action.run();
        return true;
    }

    // 只在内容确实越出控件矩形时才设裁剪：无裁剪快路径必须保证文字与图标都在边界内（见 needsClip）。
    public void render(GuiGraphics graphics, Font font, int mouseX, int mouseY) {
        if (!visible) return;
        boolean hovered = enabled && bounds.contains(mouseX, mouseY);
        graphics.fill(bounds.x(), bounds.y(), bounds.right(), bounds.bottom(), style.background(stateFor(hovered)));
        int iconWidth = icon == null ? 0 : icon.width();
        int iconHeight = icon == null ? 0 : icon.height();
        int contentWidth = textWidth + (icon == null ? 0 : iconWidth + (textWidth == 0 ? 0 : 3));
        int x = bounds.x() + Math.max(2, (bounds.width() - contentWidth) / 2);
        int iconY = bounds.y() + (bounds.height() - iconHeight) / 2;
        int textX = icon == null ? x : x + iconWidth + 3;
        int textY = bounds.y() + (bounds.height() - font.lineHeight) / 2;
        int textMax = Math.max(0, bounds.right() - 2 - textX);
        boolean clip = needsClip(x, iconY, iconWidth, iconHeight, textX, textY, font.lineHeight);
        if (clip) UiTransform.enableScissor(graphics, bounds);
        try {
            if (icon != null) icon.render(graphics, x, iconY);
            // 放不下时不再静默裁掉：留在矩形内、悬停滚动。
            scrollTicks = hovered ? scrollTicks + 1 : 0;
            TextScroll.draw(graphics, font, text, textWidth, textX, textY, textMax, textColor(), hovered, scrollTicks);
        } finally {
            if (clip) UiTransform.disableScissor(graphics);
        }
        if (focused) renderFocusOutline(graphics);
    }

    // 需要外层裁剪的条件：图标或文字实际占用的矩形越出控件边界。文本超宽时 TextScroll 自带带内裁剪
    // （带右界为 bounds.right() - 2），这里只补它不覆盖的部分：图标越界与垂直越界。
    private boolean needsClip(int iconX, int iconY, int iconWidth, int iconHeight,
                              int textX, int textY, int lineHeight) {
        if (icon != null && (iconX < bounds.x() || iconX + iconWidth > bounds.right()
                || iconY < bounds.y() || iconY + iconHeight > bounds.bottom())) {
            return true;
        }
        // +1 是 TextScroll 文本带下沿多出的 1 像素；空标签不绘制，不因此触发裁剪。
        return textWidth > 0 && (textX < bounds.x() || textY < bounds.y() || textY + lineHeight + 1 > bounds.bottom());
    }

    private UiControlStyle.State stateFor(boolean hovered) {
        if (!enabled) return UiControlStyle.State.DISABLED;
        if (pressed) return UiControlStyle.State.PRESSED;
        if (hovered) return UiControlStyle.State.HOVER;
        if (selected) return UiControlStyle.State.SELECTED;
        return UiControlStyle.State.NORMAL;
    }

    // 禁用态只降不透明度，保留调用方给定的语义色相，避免又多出一套硬编码文本色。
    private int textColor() {
        return enabled ? color : (color & 0x00FFFFFF) | 0x88000000;
    }

    // 焦点轮廓画在矩形内侧：不侵入相邻控件，也不会被控件自身的裁剪吃掉。
    private void renderFocusOutline(GuiGraphics graphics) {
        int outline = style.focusOutline();
        graphics.fill(bounds.x(), bounds.y(), bounds.right(), bounds.y() + 1, outline);
        graphics.fill(bounds.x(), bounds.bottom() - 1, bounds.right(), bounds.bottom(), outline);
        graphics.fill(bounds.x(), bounds.y() + 1, bounds.x() + 1, bounds.bottom() - 1, outline);
        graphics.fill(bounds.right() - 1, bounds.y() + 1, bounds.right(), bounds.bottom() - 1, outline);
    }
}
