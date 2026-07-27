package com.meteorite.unsuspiciousblock.client.ui.support;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import org.joml.Matrix4f;

/** 滚动文字绘制工具 —— 文字超宽时悬停自动滚动 */
public final class ScrollTextHelper {
    private static final float SCROLL_PAUSE_DISTANCE = 20.0F;
    private static final float SCROLL_SPEED = 0.35F;

    private ScrollTextHelper() {
    }

    public static void draw(GuiGraphics guiGraphics, Font font, String text,
                            int x, int y, int maxWidth, int color,
                            boolean hovered, int scrollTicks, boolean centered) {
        if (maxWidth <= 0) {
            return;
        }
        int textWidth = font.width(text);
        if (textWidth <= maxWidth) {
            int drawX = centered ? x + (maxWidth - textWidth) / 2 : x;
            guiGraphics.drawString(font, text, drawX, y, color, false);
            return;
        }

        float offset = hovered ? calculateOffset(scrollTicks, textWidth - maxWidth) : 0.0F;
        enablePoseAwareScissor(guiGraphics, x, y, x + maxWidth, y + font.lineHeight + 1);
        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(-offset, 0.0F, 0.0F);
        guiGraphics.drawString(font, text, x, y, color, false);
        guiGraphics.pose().popPose();
        guiGraphics.disableScissor();
    }

    // 起点与终点分别停顿，再以连续像素位移往返，避免整字符截取产生跳动。
    private static float calculateOffset(int scrollTicks, int overflow) {
        float phase = scrollTicks * SCROLL_SPEED;
        float period = overflow * 2.0F + SCROLL_PAUSE_DISTANCE * 2.0F;
        phase %= period;
        if (phase < SCROLL_PAUSE_DISTANCE) {
            return 0.0F;
        }
        phase -= SCROLL_PAUSE_DISTANCE;
        if (phase < overflow) {
            return phase;
        }
        phase -= overflow;
        if (phase < SCROLL_PAUSE_DISTANCE) {
            return overflow;
        }
        return overflow - (phase - SCROLL_PAUSE_DISTANCE);
    }

    // GuiGraphics 的 scissor 不跟随 PoseStack，先把逻辑坐标转换为实际 GUI 坐标。
    private static void enablePoseAwareScissor(GuiGraphics guiGraphics,
                                                int left, int top, int right, int bottom) {
        Matrix4f pose = guiGraphics.pose().last().pose();
        float x1 = pose.m00() * left + pose.m10() * top + pose.m30();
        float y1 = pose.m01() * left + pose.m11() * top + pose.m31();
        float x2 = pose.m00() * right + pose.m10() * bottom + pose.m30();
        float y2 = pose.m01() * right + pose.m11() * bottom + pose.m31();
        guiGraphics.enableScissor(
                (int) Math.floor(Math.min(x1, x2)),
                (int) Math.floor(Math.min(y1, y2)),
                (int) Math.ceil(Math.max(x1, x2)),
                (int) Math.ceil(Math.max(y1, y2)));
    }
}
