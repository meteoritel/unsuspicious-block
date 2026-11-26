package com.meteorite.unsuspiciousblock.client.ui.toast;

import com.meteorite.meteosuikit.api.geometry.UiRect;
import com.meteorite.meteosuikit.api.render.UiIcon;
import com.meteorite.meteosuikit.api.render.UiNineSlice;
import com.meteorite.unsuspiciousblock.client.ui.theme.UiAtlas;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * 模组 Toast 弹框的共享视觉框架与绘制辅助。
 * <p>
 * 统一封装深色青铜铭牌风 9-slice 外框、左侧内凹物品展台槽、右上角计数胶囊与底部进度条，
 * 让考古笔记与猫族关系通知保持一致的材质层次与对比度。
 */
final class ToastFrame {

    static final int PADDING_X = 8;
    static final int SOCKET_SIZE = 22;
    static final int ICON_INSET = 3;
    static final int TEXT_START_X = PADDING_X + SOCKET_SIZE + 6;
    static final int TEXT_RIGHT_MARGIN = 8;
    static final int COUNT_BADGE_GAP = 6;

    private static final int BADGE_HEIGHT = 11;
    private static final int BADGE_PAD_X = 4;
    private static final int BADGE_MIN_WIDTH = 16;
    private static final int PROGRESS_BAR_HEIGHT = 2;
    private static final int PROGRESS_BAR_INSET_X = 7;
    private static final int PROGRESS_BAR_BOTTOM_OFFSET = 6;
    private static final long ENTRANCE_SHEEN_DURATION_MS = 450L;
    private static final float ENTRANCE_SHEEN_MAX_ALPHA = 0.45F;
    private static final String COUNT_BADGE_KEY = "toast.unsuspiciousblock.journal_unlock.badge";

    private static final int TITLE_BRONZE = 0xFFF4ECDC;
    private static final int SUBTITLE_BRONZE = 0xFFE8C87E;
    private static final int DETAIL_BRONZE = 0xFFE6DDD0;
    private static final int SHEEN_BRONZE = 0xFFD8AA54;
    private static final int TRACK_BRONZE = 0x70121016;
    private static final int FILL_BRONZE = 0xFFC9953C;

    private static final int TITLE_GOLD = 0xFFFFE48A;
    private static final int SUBTITLE_GOLD = 0xFFFFF3C4;
    private static final int DETAIL_GOLD = 0xFFF7EBD0;
    private static final int SHEEN_GOLD = 0xFFFFE98A;
    private static final int TRACK_GOLD = 0x701A130D;
    private static final int FILL_GOLD = 0xFFF4CA60;

    private static final int TITLE_CRIMSON = 0xFFF7B2A8;
    private static final int SUBTITLE_CRIMSON = 0xFFE8B4AC;
    private static final int DETAIL_CRIMSON = 0xFFE3C8C4;
    private static final int SHEEN_CRIMSON = 0xFFD97266;
    private static final int TRACK_CRIMSON = 0x70180E12;
    private static final int FILL_CRIMSON = 0xFFC45A50;

    /**
     * Toast 弹框语义色调。
     */
    enum Tone {
        BRONZE(
                UiAtlas.TOAST_FRAME_BRONZE,
                UiAtlas.TOAST_ICON_SOCKET,
                TITLE_BRONZE,
                SUBTITLE_BRONZE,
                DETAIL_BRONZE,
                SHEEN_BRONZE,
                TRACK_BRONZE,
                FILL_BRONZE
        ),
        GOLD(
                UiAtlas.TOAST_FRAME_GOLD,
                UiAtlas.TOAST_ICON_SOCKET_GOLD,
                TITLE_GOLD,
                SUBTITLE_GOLD,
                DETAIL_GOLD,
                SHEEN_GOLD,
                TRACK_GOLD,
                FILL_GOLD
        ),
        CRIMSON(
                UiAtlas.TOAST_FRAME_CRIMSON,
                UiAtlas.TOAST_ICON_SOCKET,
                TITLE_CRIMSON,
                SUBTITLE_CRIMSON,
                DETAIL_CRIMSON,
                SHEEN_CRIMSON,
                TRACK_CRIMSON,
                FILL_CRIMSON
        );

        private final UiNineSlice frame;
        private final UiNineSlice socket;
        private final int titleColor;
        private final int subtitleColor;
        private final int detailColor;
        private final int sheenColor;
        private final int progressTrackColor;
        private final int progressFillColor;

        Tone(UiNineSlice frame, UiNineSlice socket,
             int titleColor, int subtitleColor, int detailColor,
             int sheenColor, int progressTrackColor, int progressFillColor) {
            this.frame = frame;
            this.socket = socket;
            this.titleColor = titleColor;
            this.subtitleColor = subtitleColor;
            this.detailColor = detailColor;
            this.sheenColor = sheenColor;
            this.progressTrackColor = progressTrackColor;
            this.progressFillColor = progressFillColor;
        }

        int titleColor() {
            return titleColor;
        }

        int subtitleColor() {
            return subtitleColor;
        }

        int detailColor() {
            return detailColor;
        }
    }

    private ToastFrame() {
    }

    /**
     * 绘制带半透明混合的 9-slice 铭牌背景与入场微光。
     */
    static void paintBackground(GuiGraphics graphics, int width, int height, Tone tone, long elapsedMs) {
        paintBlendedNineSlice(graphics, tone.frame, new UiRect(0, 0, width, height));
        if (elapsedMs >= 0L && elapsedMs < ENTRANCE_SHEEN_DURATION_MS && width > 12) {
            float progress = 1.0F - (float) elapsedMs / ENTRANCE_SHEEN_DURATION_MS;
            int alpha = Math.round(255.0F * ENTRANCE_SHEEN_MAX_ALPHA * progress * progress);
            if (alpha >= 4) {
                int sheen = withAlpha(tone.sheenColor, alpha);
                graphics.fill(4, 2, width - 4, 3, sheen);
            }
        }
    }

    /**
     * 绘制左侧 22×22 内凹图标展台槽，并将 16×16 图标居中嵌入。
     */
    static void paintIconSocket(GuiGraphics graphics, int x, int y, Tone tone, UiIcon icon) {
        paintBlendedNineSlice(graphics, tone.socket, new UiRect(x, y, SOCKET_SIZE, SOCKET_SIZE));
        icon.render(graphics, x + ICON_INSET, y + ICON_INSET);
    }

    /**
     * 测量右上角剩余条数徽章所需宽度（含与标题间距）；无剩余条数时返回 0。
     */
    static int measureCountBadgeReservedWidth(Font font, int remainingCount) {
        if (remainingCount <= 0) {
            return 0;
        }
        String text = formatCountBadge(remainingCount);
        int pillWidth = Math.max(BADGE_MIN_WIDTH, font.width(text) + BADGE_PAD_X * 2);
        return pillWidth + COUNT_BADGE_GAP;
    }

    /**
     * 在右上角绘制紧凑计数胶囊徽章。
     */
    static void paintCountBadge(GuiGraphics graphics, Font font, int rightEdgeX, int y,
                                int remainingCount, Tone tone) {
        if (remainingCount <= 0) {
            return;
        }
        String text = formatCountBadge(remainingCount);
        int textWidth = font.width(text);
        int pillWidth = Math.max(BADGE_MIN_WIDTH, textWidth + BADGE_PAD_X * 2);
        int pillX = rightEdgeX - pillWidth;
        paintBlendedNineSlice(graphics, UiAtlas.TOAST_COUNT_PILL, new UiRect(pillX, y, pillWidth, BADGE_HEIGHT));
        int textX = pillX + (pillWidth - textWidth) / 2;
        graphics.drawString(font, text, textX, y + 2, tone.subtitleColor(), false);
    }

    /**
     * 在底部边框内侧绘制剩余展示时间细进度条。
     */
    static void paintProgressBar(GuiGraphics graphics, int width, int height, double remainingRatio, Tone tone) {
        int trackLeft = PROGRESS_BAR_INSET_X;
        int trackRight = width - PROGRESS_BAR_INSET_X;
        if (trackRight <= trackLeft) {
            return;
        }
        int barTop = height - PROGRESS_BAR_BOTTOM_OFFSET;
        int barBottom = barTop + PROGRESS_BAR_HEIGHT;
        graphics.fill(trackLeft, barTop, trackRight, barBottom, tone.progressTrackColor);
        double clamped = Math.clamp(remainingRatio, 0.0D, 1.0D);
        int fillWidth = (int) Math.round((trackRight - trackLeft) * clamped);
        if (fillWidth <= 0) {
            return;
        }
        int fillRight = trackLeft + fillWidth;
        graphics.fill(trackLeft, barTop, fillRight, barBottom, tone.progressFillColor);
        if (fillWidth >= 3) {
            graphics.fill(fillRight - 1, barTop, fillRight, barBottom, tone.titleColor());
        }
    }

    private static String formatCountBadge(int remainingCount) {
        return Component.translatable(COUNT_BADGE_KEY, remainingCount).getString();
    }

    // Toast 在 3D 世界 HUD 阶段绘制，显式开关混合以保留 2px 切角透明外缘与半透明底色。
    private static void paintBlendedNineSlice(GuiGraphics graphics, UiNineSlice slice, UiRect rect) {
        graphics.flush();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        try {
            slice.render(graphics, rect);
            graphics.flush();
        } finally {
            RenderSystem.disableBlend();
        }
    }

    private static int withAlpha(int rgb, int alpha) {
        return (rgb & 0x00FFFFFF) | ((alpha & 0xFF) << 24);
    }
}
