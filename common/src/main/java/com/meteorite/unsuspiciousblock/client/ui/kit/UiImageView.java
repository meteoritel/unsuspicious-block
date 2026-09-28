package com.meteorite.unsuspiciousblock.client.ui.kit;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * 图片内容视图：{@link UiLightbox.Content} 的图片实现——contain 适配、有限步进缩放、指针锚点缩放、
 * 平移钳制、严格裁剪绘制与缺图占位。
 *
 * <p>本类**只做一张图**：尺寸与缩放/平移状态；图集、标题、控制按钮与关闭语义都在灯箱外壳里。
 * 因此同一张图既能被图集逐个展示，也能被任意宿主单独放进灯箱。</p>
 *
 * <p>坐标与口径：视口由外壳写入（宿主 GUI 逻辑坐标），{@code offsetX/offsetY} 是**图片中心相对视口中心
 * 的屏幕像素偏移**，{@code scale} 是绘制缩放。任一轴的目标尺寸不超过视口时该轴偏移被锁死为 0（居中），
 * 超过时偏移钳制在 ±(目标尺寸 − 视口尺寸)/2 内，因此图片永远不会被拖出视口留下空白。</p>
 *
 * <p>本类不逐帧解码、不创建纹理：贴图与区域尺寸都由 {@link LightboxImage} 给出，资源可用性只在构造、
 * 内容变更与 {@link #invalidateResources()} 时各判定一次。</p>
 */
public final class UiImageView implements UiLightbox.Content {
    /** 缩放步进（有限步进）：每次滚轮/按钮改变一档。 */
    private static final double STEP = 1.25;
    private static final double MIN_SCALE = 0.05;
    private static final double MAX_SCALE = 8.0;

    /** 缺图重查间隔（帧）：20 帧，60 FPS 下约 0.33 秒。 */
    private static final int RECHECK_INTERVAL = 20;

    private final LightboxImage image;
    @Nullable private final Component missingText;
    private UiRect viewport = new UiRect(0, 0, 0, 0);
    private double scale = 1.0;
    private double offsetX;
    private double offsetY;
    /** 小图默认不主动放大；true 时 fit 允许放大到视口。 */
    private final boolean allowUpscale;
    private boolean available;
    private boolean dragging;
    /** 缺图时的重查计时：约每 20 帧重查一次，避免逐帧 IO，又能在资源补齐后自愈。 */
    private int recheckTicks;

    public UiImageView(LightboxImage image, @Nullable Component missingText) {
        this(image, missingText, false);
    }

    /** 便捷重载：不显示占位文案。 */
    public UiImageView(LightboxImage image) {
        this(image, null, false);
    }

    /**
     * @param allowUpscale 是否允许 fit 放大到视口（默认 false：小图保持原始像素大小）
     */
    public UiImageView(LightboxImage image, @Nullable Component missingText, boolean allowUpscale) {
        this.image = Objects.requireNonNull(image);
        this.missingText = missingText;
        this.allowUpscale = allowUpscale;
        this.available = resolveAvailability();
    }

    // ---------- 内容契约 ----------

    @Override
    public int contentWidth() { return image.width(); }

    @Override
    public int contentHeight() { return image.height(); }

    @Override
    public void setViewport(UiRect viewport) {
        this.viewport = Objects.requireNonNull(viewport);
        // resize 只重新钳制平移、保留用户缩放；首次布局与换内容由外壳紧接着调用 fit()。
        clampOffsets();
    }

    /**
     * contain 适配：按视口与图片尺寸取较小比例，小图默认不放大（scale 上限 1.0），最后钳制在
     * [MIN_SCALE, MAX_SCALE]；偏移归零表示居中。
     */
    @Override
    public void fit() {
        int viewWidth = viewport.width();
        int viewHeight = viewport.height();
        double max = allowUpscale ? MAX_SCALE : 1.0;
        double contain = Math.min(viewWidth / (double) image.width(), viewHeight / (double) image.height());
        scale = Math.clamp(Math.min(max, contain), MIN_SCALE, MAX_SCALE);
        offsetX = 0;
        offsetY = 0;
        clampOffsets();
    }

    /**
     * 指针锚点缩放：缩放前后指针下的同一内容点保持不动；到顶/到底或结果不变时返回 false。
     */
    @Override
    public boolean zoom(double amount, double anchorX, double anchorY) {
        if (amount == 0 || !Double.isFinite(anchorX) || !Double.isFinite(anchorY)) return false;
        double target = Math.clamp(amount > 0 ? scale * STEP : scale / STEP, MIN_SCALE, MAX_SCALE);
        if (target == scale) return false;
        double previousScale = scale;
        double previousOffsetX = offsetX;
        double previousOffsetY = offsetY;
        // 指针下的内容点（相对图片中心的像素坐标）在缩放前后必须落在同一个屏幕位置。
        double centerX = viewport.x() + viewport.width() / 2.0;
        double centerY = viewport.y() + viewport.height() / 2.0;
        double contentX = (anchorX - centerX - offsetX) / scale;
        double contentY = (anchorY - centerY - offsetY) / scale;
        scale = target;
        offsetX = anchorX - centerX - contentX * scale;
        offsetY = anchorY - centerY - contentY * scale;
        clampOffsets();
        return scale != previousScale || offsetX != previousOffsetX || offsetY != previousOffsetY;
    }

    @Override
    public boolean zoomBy(int direction) {
        if (direction == 0) return false;
        return zoom(direction, viewport.x() + viewport.width() / 2.0, viewport.y() + viewport.height() / 2.0);
    }

    @Override
    public boolean panBy(double screenDx, double screenDy) {
        // 只有从视口内按下的拖动才平移；在遮罩/控制栏上拖动不该带着图片跑。
        if (!dragging || !Double.isFinite(screenDx) || !Double.isFinite(screenDy)) return false;
        double previousOffsetX = offsetX;
        double previousOffsetY = offsetY;
        offsetX += screenDx;
        offsetY += screenDy;
        clampOffsets();
        return offsetX != previousOffsetX || offsetY != previousOffsetY;
    }

    @Override
    public boolean mousePressed(double x, double y, int button) {
        if (button != 0) return false;
        dragging = true;
        return true;
    }

    @Override
    public boolean mouseReleased(double x, double y, int button) {
        if (button != 0) return false;
        boolean wasDragging = dragging;
        dragging = false;
        return wasDragging;
    }

    @Override
    public int zoomPercent() { return (int) Math.round(scale * 100.0); }

    @Override
    public @Nullable UiTarget hit(double x, double y) { return null; }

    @Override
    public @Nullable Component placeholder() {
        recheckIfMissing();
        return available ? null : missingText;
    }

    @Override
    public void invalidateResources() {
        boolean previous = available;
        available = resolveAvailability();
        // 「不可用 → 可用」时图片尺寸才真正可用，重新求一次 fit；其余情况保留用户缩放。
        if (!previous && available) fit();
    }

    // ---------- 绘制 ----------

    @Override
    public void render(GuiGraphics graphics, Font font, int mouseX, int mouseY) {
        if (!available || viewport.width() <= 0 || viewport.height() <= 0) return;
        double destWidth = image.width() * scale;
        double destHeight = image.height() * scale;
        int x = (int) Math.round(viewport.x() + (viewport.width() - destWidth) / 2.0 + offsetX);
        int y = (int) Math.round(viewport.y() + (viewport.height() - destHeight) / 2.0 + offsetY);
        // 严格裁剪到视口：放大后的图不会画到控制栏、标题或遮罩上。
        UiTransform.enableScissor(graphics, viewport);
        try {
            graphics.blit(image.texture(), x, y, Math.max(1, (int) Math.round(destWidth)),
                    Math.max(1, (int) Math.round(destHeight)),
                    image.u(), image.v(), image.width(), image.height(),
                    image.textureWidth(), image.textureHeight());
        } finally {
            UiTransform.disableScissor(graphics);
        }
    }

    // ---------- 内部 ----------

    // 目标尺寸不超过视口时该轴居中锁定；超过时限制在可滚范围内，并清掉 NaN 之类的非法值。
    private void clampOffsets() {
        double destWidth = image.width() * scale;
        double destHeight = image.height() * scale;
        if (!Double.isFinite(offsetX)) offsetX = 0;
        if (!Double.isFinite(offsetY)) offsetY = 0;
        if (destWidth <= viewport.width()) {
            offsetX = 0;
        } else {
            double limit = (destWidth - viewport.width()) / 2.0;
            offsetX = Math.clamp(offsetX, -limit, limit);
        }
        if (destHeight <= viewport.height()) {
            offsetY = 0;
        } else {
            double limit = (destHeight - viewport.height()) / 2.0;
            offsetY = Math.clamp(offsetY, -limit, limit);
        }
    }

    // 缺图状态下由外壳每帧调用一次占位查询，这里做限频重查：
    // 贴图由资源包补齐时无需宿主接线也能恢复显示；可用状态不重查，绘制路径仍然零 IO。
    private void recheckIfMissing() {
        if (available) return;
        if (++recheckTicks < RECHECK_INTERVAL) return;
        recheckTicks = 0;
        invalidateResources();
    }

    // 资源可用性在构造、重载通知与限频重查时判定；正常情况下绘制路径不做 IO。
    private boolean resolveAvailability() {
        return Minecraft.getInstance().getResourceManager().getResource(image.texture()).isPresent();
    }
}
