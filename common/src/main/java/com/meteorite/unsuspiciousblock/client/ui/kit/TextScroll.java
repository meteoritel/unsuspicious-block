package com.meteorite.unsuspiciousblock.client.ui.kit;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import org.joml.Matrix4f;

/**
 * 超宽文本的悬停滚动绘制——宽度够就照常画，超宽且悬停时在起点/终点各停顿一次后往返平移。
 *
 * <p>这是 kit 内**唯一**的滚动文字实现：{@code UiDocument} 的行文本与 {@code UiControl} 的标签都走它，
 * 旧的 {@code support/ScrollTextHelper} 仅作为 {@code String} 门面委托到此处，避免两份几何与节奏各写一遍。</p>
 *
 * <p>裁剪使用 pose 感知的 scissor：调用点可能已经处于内容变换（缩放/平移）之中，
 * 而原版 {@code GuiGraphics.enableScissor} 只吃 GUI 逻辑坐标，因此先把矩形换算一次。
 * 原版 scissor 是栈式交集，嵌套调用在退出时自动恢复外层裁剪。</p>
 */
public final class TextScroll {
    /** 起点与终点各停顿的位移量：太短会让往返显得急促。 */
    private static final float PAUSE_DISTANCE = 20.0F;
    /** 每 tick 的位移像素：越小越慢。 */
    private static final float SPEED = 0.35F;

    private TextScroll() {
    }

    /**
     * 绘制一段可滚动的文本（已缓存的视觉序列，kit 行文本走这条）。
     *
     * @param textWidth 已测得的文本宽度，避免重复测量
     * @param maxWidth  文本可用宽度（内容坐标）；{@code <= 0} 时直接跳过
     * @param hovered   是否处于悬停：只有悬停才推进位移，离开即回到起点
     * @param ticks     调用方自持的滚动计时；悬停期间应逐帧递增，离开后归零
     */
    public static void draw(GuiGraphics graphics, Font font, FormattedCharSequence text, int textWidth,
                            int x, int y, int maxWidth, int color, boolean hovered, int ticks) {
        if (maxWidth <= 0) return;
        if (textWidth <= maxWidth) {
            graphics.drawString(font, text, x, y, color, false);
            return;
        }
        float offset = hovered ? offset(ticks, textWidth - maxWidth) : 0.0F;
        enablePoseAwareScissor(graphics, x, y, x + maxWidth, y + font.lineHeight + 1);
        try {
            graphics.pose().pushPose();
            graphics.pose().translate(-offset, 0.0F, 0.0F);
            graphics.drawString(font, text, x, y, color, false);
            graphics.pose().popPose();
        } finally {
            graphics.disableScissor();
        }
    }

    /**
     * 绘制一段可滚动的文本（{@code String} 门面，供既有面板沿用）。
     *
     * @param centered 宽度足够时是否在可用宽度内居中
     */
    public static void draw(GuiGraphics graphics, Font font, String text,
                            int x, int y, int maxWidth, int color,
                            boolean hovered, int scrollTicks, boolean centered) {
        if (maxWidth <= 0) return;
        int textWidth = font.width(text);
        if (textWidth <= maxWidth) {
            graphics.drawString(font, text, centered ? x + (maxWidth - textWidth) / 2 : x, y, color, false);
            return;
        }
        draw(graphics, font, FormattedCharSequence.forward(text, Style.EMPTY), textWidth,
                x, y, maxWidth, color, hovered, scrollTicks);
    }

    // 起点与终点分别停顿，再以连续像素位移往返，避免整字符截取产生跳动。
    private static float offset(int scrollTicks, int overflow) {
        float phase = scrollTicks * SPEED;
        float period = overflow * 2.0F + PAUSE_DISTANCE * 2.0F;
        phase %= period;
        if (phase < PAUSE_DISTANCE) return 0.0F;
        phase -= PAUSE_DISTANCE;
        if (phase < overflow) return phase;
        phase -= overflow;
        if (phase < PAUSE_DISTANCE) return overflow;
        return overflow - (phase - PAUSE_DISTANCE);
    }

    private static void enablePoseAwareScissor(GuiGraphics graphics, int left, int top, int right, int bottom) {
        Matrix4f pose = graphics.pose().last().pose();
        float x1 = pose.m00() * left + pose.m10() * top + pose.m30();
        float y1 = pose.m01() * left + pose.m11() * top + pose.m31();
        float x2 = pose.m00() * right + pose.m10() * bottom + pose.m30();
        float y2 = pose.m01() * right + pose.m11() * bottom + pose.m31();
        graphics.enableScissor(
                (int) Math.floor(Math.min(x1, x2)),
                (int) Math.floor(Math.min(y1, y2)),
                (int) Math.ceil(Math.max(x1, x2)),
                (int) Math.ceil(Math.max(y1, y2)));
    }
}
