package com.meteorite.unsuspiciousblock.client.ui.kit;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/** 九宫格贴图：四角保持原生尺寸，边和中心各一次拉伸绘制，总计九个四边形。 */
public record UiNineSlice(ResourceLocation texture, int size, int corner) {
    public UiNineSlice {
        if (corner <= 0 || size <= corner * 2) throw new IllegalArgumentException("Invalid nine slice");
    }

    public void render(GuiGraphics graphics, UiRect rect) {
        int edgeX = Math.min(corner, rect.width() / 2);
        int edgeY = Math.min(corner, rect.height() / 2);
        graphics.flush();
        for (int row = 0; row < 3; row++) {
            int y = row == 0 ? rect.y() : row == 1 ? rect.y() + edgeY : rect.bottom() - edgeY;
            int height = row == 1 ? rect.height() - edgeY * 2 : edgeY;
            int v = row == 0 ? 0 : row == 1 ? corner : size - corner;
            int sourceHeight = row == 1 ? size - corner * 2 : corner;
            for (int col = 0; col < 3; col++) {
                int x = col == 0 ? rect.x() : col == 1 ? rect.x() + edgeX : rect.right() - edgeX;
                int width = col == 1 ? rect.width() - edgeX * 2 : edgeX;
                int u = col == 0 ? 0 : col == 1 ? corner : size - corner;
                int sourceWidth = col == 1 ? size - corner * 2 : corner;
                if (width > 0 && height > 0) {
                    graphics.blit(texture, x, y, width, height, (float) u, (float) v,
                            sourceWidth, sourceHeight, size, size);
                }
            }
        }
    }
}
