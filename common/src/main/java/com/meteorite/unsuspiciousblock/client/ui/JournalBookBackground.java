package com.meteorite.unsuspiciousblock.client.ui;

import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.client.ui.layout.JournalLayout;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/** 书页背景：384x256 展开书本纹理 + 坐标计算 */
public final class JournalBookBackground {

    public static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "textures/gui/archaeology_journal_book.png");

    private JournalBookBackground() {
    }

    // 根据屏幕尺寸计算书本居中位置及各页面内容区
    public static BookLayout compute(int screenWidth, int screenHeight) {
        int bookX = (screenWidth - JournalLayout.TEXTURE_WIDTH) / 2;
        int bookY = (screenHeight - JournalLayout.TEXTURE_HEIGHT) / 2;
        return new BookLayout(
                bookX, bookY,
                bookX + JournalLayout.LEFT_PAGE_X_OFFSET, bookY + JournalLayout.PAGE_Y_OFFSET,
                JournalLayout.PAGE_WIDTH, JournalLayout.PAGE_HEIGHT,
                bookX + JournalLayout.RIGHT_PAGE_X_OFFSET, bookY + JournalLayout.PAGE_Y_OFFSET,
                JournalLayout.PAGE_WIDTH, JournalLayout.PAGE_HEIGHT
        );
    }

    // 渲染书页背景纹理
    public static void render(GuiGraphics guiGraphics, BookLayout layout) {
        guiGraphics.blit(
                TEXTURE,
                layout.bookX(), layout.bookY(),
                0, 0,
                JournalLayout.TEXTURE_WIDTH, JournalLayout.TEXTURE_HEIGHT,
                JournalLayout.TEXTURE_WIDTH, JournalLayout.TEXTURE_HEIGHT
        );
    }

    public record BookLayout(
            int bookX, int bookY,
            int leftPageX, int leftPageY, int leftPageWidth, int leftPageHeight,
            int rightPageX, int rightPageY, int rightPageWidth, int rightPageHeight
    ) {
        public int leftPageRight() {
            return leftPageX + leftPageWidth;
        }

        public int leftPageBottom() {
            return leftPageY + leftPageHeight;
        }

        public int rightPageRight() {
            return rightPageX + rightPageWidth;
        }

        public int rightPageBottom() {
            return rightPageY + rightPageHeight;
        }
    }
}
