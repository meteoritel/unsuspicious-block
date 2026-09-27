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

    public void setBounds(int x, int y, int width, int height) {
        if (bounds.x() == x && bounds.y() == y && bounds.width() == width && bounds.height() == height) return;
        bounds = new UiRect(x, y, width, height);
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

    public void render(GuiGraphics graphics, Font font, int mouseX, int mouseY) {
        if (!visible) return;
        boolean hovered = enabled && bounds.contains(mouseX, mouseY);
        graphics.fill(bounds.x(), bounds.y(), bounds.right(), bounds.bottom(), style.background(stateFor(hovered)));
        UiTransform.enableScissor(graphics, bounds);
        try {
            int width = textWidth + (icon == null ? 0 : icon.width() + (textWidth == 0 ? 0 : 3));
            int x = bounds.x() + Math.max(2, (bounds.width() - width) / 2);
            if (icon != null) {
                icon.render(graphics, x, bounds.y() + (bounds.height() - icon.height()) / 2);
                x += icon.width() + 3;
            }
            // 放不下时不再静默裁掉：留在矩形内、悬停滚动。
            scrollTicks = hovered ? scrollTicks + 1 : 0;
            TextScroll.draw(graphics, font, text, textWidth, x,
                    bounds.y() + (bounds.height() - font.lineHeight) / 2,
                    Math.max(0, bounds.right() - 2 - x), textColor(), hovered, scrollTicks);
        } finally {
            UiTransform.disableScissor(graphics);
        }
        if (focused) renderFocusOutline(graphics);
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
