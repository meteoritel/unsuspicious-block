package com.meteorite.unsuspiciousblock.client.ui.kit;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** 固定矩形的轻量控件，测量及命中结果只在配置改变时创建，不负责输入分发。 */
public final class UiControl {
    private UiRect bounds = new UiRect(0, 0, 0, 0);
    private Component label = Component.empty();
    private FormattedCharSequence text = FormattedCharSequence.EMPTY;
    private int textWidth;
    private int color;
    @Nullable private UiIcon icon;
    private List<Component> tooltip = List.of();
    @Nullable private UiAction action;
    private UiTarget target = new UiTarget(UiTarget.Kind.ROW, 0, null, bounds, List.of(), null);
    /** 标签超宽时的滚动计时：只在悬停期间推进，离开即归零。 */
    private int scrollTicks;

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
        rebuildTarget();
    }

    public void setBounds(int x, int y, int width, int height) {
        if (bounds.x() == x && bounds.y() == y && bounds.width() == width && bounds.height() == height) return;
        bounds = new UiRect(x, y, width, height);
        rebuildTarget();
    }

    private void rebuildTarget() {
        target = new UiTarget(icon == null ? UiTarget.Kind.ROW : UiTarget.Kind.ICON,
                0, label, bounds, tooltip, action);
    }

    @Nullable public UiTarget hit(double x, double y) { return bounds.contains(x, y) ? target : null; }

    public void render(GuiGraphics graphics, Font font, int mouseX, int mouseY) {
        boolean hovered = bounds.contains(mouseX, mouseY);
        graphics.fill(bounds.x(), bounds.y(), bounds.right(), bounds.bottom(),
                hovered ? 0xFFE0C798 : 0xFFF2E5C6);
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
                    Math.max(0, bounds.right() - 2 - x), color, hovered, scrollTicks);
        } finally {
            UiTransform.disableScissor(graphics);
        }
    }
}
