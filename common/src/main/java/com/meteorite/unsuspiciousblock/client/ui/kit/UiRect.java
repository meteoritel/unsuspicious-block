package com.meteorite.unsuspiciousblock.client.ui.kit;

/**
 * 排版阶段创建的逻辑矩形，右边界和下边界不参与命中。
 *
 * <p>负宽高在构造期抛 {@link IllegalArgumentException}：这是矩形自身的不变式。kit 的公开几何入口
 * （{@code UiControl.setBounds}、{@code UiDocument.setViewport}、{@code UiScrollView.setViewport}）
 * 会先把负尺寸钳到合法范围再构造，调用方不需要自行钳制；直接 new 的调用方必须自己保证非负。</p>
 */
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
