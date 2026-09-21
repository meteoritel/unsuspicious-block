package com.meteorite.unsuspiciousblock.client.ui.kit;

import net.minecraft.client.gui.GuiGraphics;
import org.joml.Matrix4f;

/** 内容到调用方 GUI 坐标的正向、逆向变换；平移使用未缩放的内容单位。 */
public final class UiTransform {
    private static final double[] SCALES = {0.5, 1, 2, 3};
    private double originX;
    private double originY;
    private double panX;
    private double panY;
    private int zoomIndex = 1;

    public double scale() { return SCALES[zoomIndex]; }
    public int zoomIndex() { return zoomIndex; }
    public double panX() { return panX; }
    public double panY() { return panY; }
    public double toLocalX(double x) { return (x - originX) / scale() - panX; }
    public double toLocalY(double y) { return (y - originY) / scale() - panY; }
    public double toScreenX(double x) { return originX + (x + panX) * scale(); }
    public double toScreenY(double y) { return originY + (y + panY) * scale(); }

    // 恢复持久化的档位与平移：档位越界时夹紧，非有限平移归零，避免旧存档把视图置成非法值。
    public void restore(int zoomIndex, double panX, double panY) {
        this.zoomIndex = Math.clamp(zoomIndex, 0, SCALES.length - 1);
        this.panX = Double.isFinite(panX) ? panX : 0.0;
        this.panY = Double.isFinite(panY) ? panY : 0.0;
    }

    // 原点由视口设置；移动视口不改变平移和缩放状态。
    void setOrigin(double x, double y) { originX = x; originY = y; }

    public void panBy(double screenDx, double screenDy) {
        if (!Double.isFinite(screenDx) || !Double.isFinite(screenDy)) return;
        panX += screenDx / scale();
        panY += screenDy / scale();
    }

    // 缩放只改变绘制变换，锚点下方的内容坐标保持不变。
    public void zoomBy(int direction, double anchorX, double anchorY) {
        if (!Double.isFinite(anchorX) || !Double.isFinite(anchorY)) return;
        double localX = toLocalX(anchorX);
        double localY = toLocalY(anchorY);
        zoomIndex = Math.clamp(zoomIndex + Integer.signum(direction), 0, SCALES.length - 1);
        panX = (anchorX - originX) / scale() - localX;
        panY = (anchorY - originY) / scale() - localY;
    }

    public void reset() { zoomIndex = 1; panX = 0; panY = 0; }

    void push(GuiGraphics graphics) {
        graphics.pose().pushPose();
        graphics.pose().translate(toScreenX(0), toScreenY(0), 0);
        graphics.pose().scale((float) scale(), (float) scale(), 1);
    }

    // 1.21.1 的 enableScissor 不读 pose；先将轴对齐矩形换算为 GUI 屏幕坐标。
    // 在内容变换入栈前调用，因此裁剪框不会随内容平移、缩放而移动。
    static void enableScissor(GuiGraphics graphics, UiRect viewport) {
        Matrix4f matrix = graphics.pose().last().pose();
        double left = matrix.m00() * viewport.x() + matrix.m30();
        double top = matrix.m11() * viewport.y() + matrix.m31();
        double right = matrix.m00() * viewport.right() + matrix.m30();
        double bottom = matrix.m11() * viewport.bottom() + matrix.m31();
        // GuiGraphics 使用整数 GUI 像素；向内取整保证边界外不泄漏。
        int x = (int) Math.ceil(Math.min(left, right));
        int y = (int) Math.ceil(Math.min(top, bottom));
        graphics.flush();
        graphics.enableScissor(x, y, Math.max(x, (int) Math.floor(Math.max(left, right))),
                Math.max(y, (int) Math.floor(Math.max(top, bottom))));
    }

    static void disableScissor(GuiGraphics graphics) {
        graphics.flush();
        graphics.disableScissor();
    }
}
