package com.meteorite.unsuspiciousblock.client.ui.kit;

import net.minecraft.client.gui.GuiGraphics;

/**
 * 通用纵向滚动视口：持有视口矩形、内容高度与滚动偏移，负责裁剪、坐标换算以及滚动条的命中与绘制。
 *
 * <p>坐标口径：视口矩形使用宿主 GUI 逻辑坐标，与 {@link UiControl} 的矩形同坐标系。绘制内容前调用
 * {@link #push(GuiGraphics)} 进入内容坐标——原点在内容顶部左对齐（x 与视口左边界对齐）；绘制后调用
 * {@link #pop(GuiGraphics)} 返回宿主坐标，再由宿主调用 {@link #renderScrollbar(GuiGraphics, UiControlStyle)}。
 * 命中走同一套换算：{@link #toContentX(double)} / {@link #toContentY(double)} 把屏幕坐标换成内容坐标，
 * 交给 {@link UiControlGroup} 使用。</p>
 *
 * <p>偏移恒钳制在 [0, {@link #maxOffset()}]：视口尺寸变化、内容变短以及 {@link #setContentHeight(int)}
 * 之后都会重新钳制。内容高度不超过视口高度时偏移必为 0、{@link #maxOffset()} 为 0，滚动条不显示。</p>
 *
 * <p>本类只做几何与偏移，不持有任何业务状态、不负责输入分发；滚轮是否消费由宿主按
 * {@link #contains(double, double)} 决定。</p>
 */
public final class UiScrollView {
    /** 滚动条占用的宽度；宿主可据此在内容溢出时让出内容宽度。 */
    public static final int SCROLLBAR_WIDTH = 4;
    /** 滑块最小高度：内容再长也要留出可抓取区域。 */
    private static final int MIN_THUMB_HEIGHT = 8;

    private UiRect viewport = new UiRect(0, 0, 1, 1);
    private int contentHeight;
    private int offset;
    private int step = 20;
    private boolean scrollbarVisible;
    private boolean dragging;
    /** 按下时指针相对滑块顶部的距离；拖动期间保持不变，滑块才会跟着指针平移而不是跳到指针处。 */
    private double dragGrab;

    // ---------- 几何与偏移 ----------

    // 视口尺寸变化立即重新钳制：内容不足一屏时偏移必须回到 0。
    public void setViewport(int x, int y, int width, int height) {
        viewport = new UiRect(x, y, Math.max(0, width), Math.max(0, height));
        clampOffset();
    }

    public UiRect viewport() { return viewport; }

    public void setContentHeight(int contentHeight) {
        this.contentHeight = Math.max(0, contentHeight);
        clampOffset();
    }

    public int contentHeight() { return contentHeight; }

    public int offset() { return offset; }

    public int maxOffset() { return Math.max(0, contentHeight - viewport.height()); }

    public void setOffset(int offset) { this.offset = Math.clamp(offset, 0, maxOffset()); }

    public void setStep(int step) { this.step = Math.max(1, step); }

    public int step() { return step; }

    // 滚轮：正数朝内容顶部（偏移减小），与 GLFW / Screen 的纵向滚动符号一致；按 signum 走整步并钳制。
    public boolean scrollBy(double amount) {
        int direction = (int) Math.signum(amount);
        if (direction == 0) return false;
        int previous = offset;
        setOffset(direction < 0 ? offset + step : offset - step);
        return offset != previous;
    }

    /** 指针是否落在视口内；宿主据此只消费自身区域的滚轮与命中。 */
    public boolean contains(double x, double y) { return viewport.contains(x, y); }

    // 最小滚动量让矩形完整可见：先贴上边界，再下推到底边界，最后统一钳制。
    public void ensureVisible(UiRect contentRect) {
        int next = offset;
        if (contentRect.y() < next) next = contentRect.y();
        if (contentRect.bottom() > next + viewport.height()) next = contentRect.bottom() - viewport.height();
        offset = Math.clamp(next, 0, maxOffset());
    }

    public double toContentX(double screenX) { return screenX - viewport.x(); }

    public double toContentY(double screenY) { return screenY - viewport.y() + offset; }

    public int toScreenY(double contentY) { return (int) Math.round(viewport.y() + contentY - offset); }

    // ---------- 变换与裁剪 ----------

    // 进入内容坐标：先 flush 已有顶点，再按当前 pose 设置视口裁剪，最后平移。
    public void push(GuiGraphics graphics) {
        graphics.flush();
        UiTransform.enableScissor(graphics, viewport);
        graphics.pose().pushPose();
        graphics.pose().translate(viewport.x(), viewport.y() - offset, 0.0F);
    }

    // 退出内容坐标：先弹回宿主 pose，再恢复上一层裁剪（原版 scissor 交集栈）。
    public void pop(GuiGraphics graphics) {
        graphics.pose().popPose();
        UiTransform.disableScissor(graphics);
    }

    // ---------- 滚动条 ----------

    // 宿主只在内容溢出时打开；内容变短或钳制后没有可滚距离时一律不显示。
    public void setScrollbarVisible(boolean visible) {
        scrollbarVisible = visible;
        if (!visible) dragging = false;
    }

    public boolean isScrollbarVisible() { return scrollbarVisible && maxOffset() > 0; }

    public boolean hitScrollbar(double x, double y) {
        return isScrollbarVisible() && scrollbarTrack().contains(x, y);
    }

    /** 指针是否落在当前滑块上：宿主据此决定悬停反馈，几何只在这里算一次。 */
    public boolean hitThumb(double x, double y) {
        if (!isScrollbarVisible()) return false;
        UiRect track = scrollbarTrack();
        int top = thumbTop();
        return x >= track.x() && x < track.right() && y >= top && y < top + thumbHeight();
    }

    /** 滑块拖动是否进行中：宿主据此在整个拖动期间消费输入，即使这一帧偏移没有变化。 */
    public boolean isDragging() { return dragging; }

    public boolean mousePressed(double x, double y, int button) {
        if (button != 0 || !hitScrollbar(x, y)) return false;
        // 点在滑块之外（轨道空白）时先把滑块中心对到指针：否则拖动时滑块带着一个很大的抓取偏移，跟不上手。
        if (y < thumbTop() || y >= thumbTop() + thumbHeight()) {
            int travel = thumbTravel();
            if (travel > 0) {
                double top = Math.clamp(y - viewport.y() - thumbHeight() / 2.0, 0.0, travel);
                offset = Math.clamp((int) Math.round(top * maxOffset() / (double) travel), 0, maxOffset());
            }
        }
        dragging = true;
        // 抓取偏移必须在跳转之后取样，而且要用**未取整**的滑块位置：
        // 取跳转前的值会让下一次 mouseDragged 把滑块拉回原处，取跳转后的整数像素又会让第一个 drag 事件产生不足 1 像素的对齐跳动。
        dragGrab = maxOffset() <= 0 ? 0.0 : y - viewport.y() - (double) thumbTravel() * offset / maxOffset();
        return true;
    }

    // 拖动滑块：指针位移按「滑块可走距离 : 内容可滚距离」映射为偏移，返回偏移是否变化。
    public boolean mouseDragged(double y) {
        if (!dragging || !isScrollbarVisible()) return false;
        int travel = thumbTravel();
        if (travel <= 0) return false;
        double top = Math.clamp(y - dragGrab - viewport.y(), 0.0, travel);
        int next = Math.clamp((int) Math.round(top * maxOffset() / travel), 0, maxOffset());
        if (next == offset) return false;
        offset = next;
        return true;
    }

    public void mouseReleased() { dragging = false; }

    // 滚动条画在视口右侧；宿主在 pop() 之后调用，结构色取自传入样式。
    public void renderScrollbar(GuiGraphics graphics, UiControlStyle style) {
        if (!isScrollbarVisible()) return;
        UiRect track = scrollbarTrack();
        graphics.fill(track.x(), track.y(), track.right(), track.bottom(), style.scrollbarTrack());
        int top = thumbTop();
        graphics.fill(track.x(), top, track.right(), Math.min(track.bottom(), top + thumbHeight()),
                style.scrollbarThumb());
    }

    // ---------- 内部几何 ----------

    private void clampOffset() { offset = Math.clamp(offset, 0, maxOffset()); }

    // 滚动条始终贴着视口右边界；视口窄于滚动条时退化为整个视口宽。
    private UiRect scrollbarTrack() {
        int width = Math.min(SCROLLBAR_WIDTH, viewport.width());
        return new UiRect(viewport.right() - width, viewport.y(), width, viewport.height());
    }

    // 滑块高度按「视口/内容」比例缩放，并保底 MIN_THUMB_HEIGHT；没有可滚距离时占满轨道。
    private int thumbHeight() {
        int view = viewport.height();
        if (view <= 0 || maxOffset() <= 0) return view;
        int scaled = (int) Math.round((double) view * view / contentHeight);
        return Math.clamp(scaled, Math.min(MIN_THUMB_HEIGHT, view), view);
    }

    private int thumbTravel() { return Math.max(0, viewport.height() - thumbHeight()); }

    // 滑块位置按偏移占可滚距离的比例映射；无位移空间时停在轨道顶部。
    private int thumbTop() {
        int travel = thumbTravel();
        if (travel <= 0 || maxOffset() <= 0) return viewport.y();
        return viewport.y() + (int) Math.round((double) travel * offset / maxOffset());
    }
}
