package com.meteorite.unsuspiciousblock.client.ui.layout;

import net.minecraft.client.gui.GuiGraphics;

/**
 * 考古笔记的响应式视口。
 * 将固定尺寸书页保留在逻辑坐标系中，并统一负责渲染缩放与鼠标坐标转换。
 */
public record JournalViewport(float scale, int logicalWidth, int logicalHeight) {
    private static final int REQUIRED_WIDTH = 412;
    private static final int REQUIRED_HEIGHT = 256;

    // 根据当前 GUI 尺寸计算不超过 1:1 的整体缩放比例
    public static JournalViewport compute(int screenWidth, int screenHeight) {
        double widthScale = (double) screenWidth / REQUIRED_WIDTH;
        double heightScale = (double) screenHeight / REQUIRED_HEIGHT;
        float scale = (float) Math.min(1.0D, Math.min(widthScale, heightScale));
        if (scale <= 0.0F) {
            scale = 1.0F;
        }
        int logicalWidth = Math.max(REQUIRED_WIDTH, (int) Math.floor(screenWidth / scale));
        int logicalHeight = Math.max(REQUIRED_HEIGHT, (int) Math.floor(screenHeight / scale));
        return new JournalViewport(scale, logicalWidth, logicalHeight);
    }

    // 进入手册逻辑坐标系
    public void push(GuiGraphics guiGraphics) {
        guiGraphics.pose().pushPose();
        guiGraphics.pose().scale(this.scale, this.scale, 1.0F);
    }

    // 恢复调用方渲染坐标系
    public void pop(GuiGraphics guiGraphics) {
        guiGraphics.pose().popPose();
    }

    public double toLogicalX(double screenX) {
        return screenX / this.scale;
    }

    public double toLogicalY(double screenY) {
        return screenY / this.scale;
    }

    public double toLogicalDistance(double screenDistance) {
        return screenDistance / this.scale;
    }
}
