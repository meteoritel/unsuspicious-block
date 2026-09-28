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
        // 向内取整保证边界外不泄漏；切换前提交绘制批次，因为 blit 这类绘制在非 managed
        // 上下文里不会自动 endBatch（GuiGraphics.flushIfUnmanaged 只覆盖 fill/drawString）。
        pushScissor(graphics, viewport.x(), viewport.y(), viewport.right(), viewport.bottom(), false, true);
    }

    // 在已入栈的 pose 内绘制文本时使用（传入的矩形就是该 pose 坐标系下的矩形）。
    // 向外取整避免分数缩放时啃掉字形边缘；不提交批次是因为文本走 drawString，
    // 它在非 managed 上下文里会立刻 endBatch，额外 flush 只会多切一次批次。
    static void enableScissorInPose(GuiGraphics graphics, int left, int top, int right, int bottom) {
        pushScissor(graphics, left, top, right, bottom, true, false);
    }

    static void disableScissor(GuiGraphics graphics) {
        graphics.flush();
        graphics.disableScissor();
    }

    /*
     * 两条裁剪入口共用的换算核心：把当前 pose 坐标系里的矩形换算成整型 GUI 像素后设置 scissor。
     * 这里用完整 2x2 变换（含 m01/m10）；kit 只支持轴对齐的平移与缩放，此时与只取对角线等价。
     * outward=true 向外取整（floor/ceil，宁可多留 1 像素），false 向内取整（ceil/floor，宁可少留）。
     */
    private static void pushScissor(GuiGraphics graphics, double left, double top, double right, double bottom,
                                    boolean outward, boolean flush) {
        Matrix4f matrix = graphics.pose().last().pose();
        double x1 = matrix.m00() * left + matrix.m10() * top + matrix.m30();
        double y1 = matrix.m01() * left + matrix.m11() * top + matrix.m31();
        double x2 = matrix.m00() * right + matrix.m10() * bottom + matrix.m30();
        double y2 = matrix.m01() * right + matrix.m11() * bottom + matrix.m31();
        double minX = Math.min(x1, x2);
        double minY = Math.min(y1, y2);
        double maxX = Math.max(x1, x2);
        double maxY = Math.max(y1, y2);
        int x = (int) (outward ? Math.floor(minX) : Math.ceil(minX));
        int y = (int) (outward ? Math.floor(minY) : Math.ceil(minY));
        int rightPx = (int) (outward ? Math.ceil(maxX) : Math.floor(maxX));
        int bottomPx = (int) (outward ? Math.ceil(maxY) : Math.floor(maxY));
        if (flush) graphics.flush();
        graphics.enableScissor(x, y, Math.max(x, rightPx), Math.max(y, bottomPx));
    }
}
