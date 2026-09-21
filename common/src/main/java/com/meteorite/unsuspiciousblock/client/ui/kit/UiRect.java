package com.meteorite.unsuspiciousblock.client.ui.kit;

/** 排版阶段创建的逻辑矩形，右边界和下边界不参与命中。 */
public record UiRect(int x, int y, int width, int height) {
    public UiRect {
        if (width < 0 || height < 0) throw new IllegalArgumentException("Negative UI extent");
    }

    public int right() { return x + width; }
    public int bottom() { return y + height; }

    public boolean contains(double mouseX, double mouseY) {
        return mouseX >= x && mouseX < right() && mouseY >= y && mouseY < bottom();
    }
}
