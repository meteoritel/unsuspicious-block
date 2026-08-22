package com.meteorite.unsuspiciousblock.client.hud;

import com.meteorite.unsuspiciousblock.client.state.ReaderScanHudState;
import com.mojang.blaze3d.systems.RenderSystem;
import com.meteorite.unsuspiciousblock.network.payload.s2c.SyncReaderScanResultPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/** 可疑解析仪扫描结果 HUD，显示最多五条拥有独立生命周期的信息。 */
public final class SuspiciousReaderHud {
    private static final int SCREEN_MARGIN = 6;
    private static final int BOTTOM_OFFSET = 48;
    private static final int MIN_PANEL_WIDTH = 108;
    private static final int MAX_PANEL_WIDTH = 148;
    private static final int PANEL_PADDING = 4;
    private static final int HEADER_HEIGHT = 14;
    private static final int ROW_HEIGHT = 20;
    private static final int ICON_SIZE = 16;
    private static final int ICON_TEXT_GAP = 3;

    private static final int PANEL_BACKGROUND = 0x90101010;
    private static final int PANEL_BORDER = 0xA0A78235;
    private static final int PANEL_ACCENT = 0xD0F0C95A;
    private static final int HEADER_COLOR = 0xFFF0D77A;
    private static final int ITEM_COLOR = 0xFFF2E6BD;
    private static final int OLD_ITEM_COLOR = 0xFFAAAAAA;
    private static final int META_COLOR = 0xFF9C9C9C;

    // 原版 Font.adjustColor 会把 alpha 字节小于 4 的文字颜色强制改为完全不透明，
    // 因此任何文字的 alpha 字节低于该值时必须跳过绘制，否则淡出末尾会闪现全不透明文字
    private static final int MIN_TEXT_ALPHA = 4;

    private SuspiciousReaderHud() {
    }

    // 由平台 HUD 渲染事件调用
    public static void render(GuiGraphics graphics) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null
                || minecraft.options.hideGui
                || minecraft.screen != null) {
            return;
        }
        // 单帧快照：面板锚点与条目在同一次加锁中读取，保证淡出同步
        ReaderScanHudState.Snapshot snapshot = ReaderScanHudState.snapshot();
        List<ReaderScanHudState.HudEntry> entries = snapshot.entries();
        if (entries.isEmpty()) {
            return;
        }

        long now = System.currentTimeMillis();
        Font font = minecraft.font;
        String title = Component.translatable("hud.unsuspiciousblock.suspicious_reader.title",
                entries.size(), snapshot.lootContainerCount()).getString();
        int panelWidth = calculatePanelWidth(font, title, entries,
                minecraft.getWindow().getGuiScaledWidth());
        int panelHeight = PANEL_PADDING + HEADER_HEIGHT + entries.size() * ROW_HEIGHT + PANEL_PADDING;
        int panelX = SCREEN_MARGIN;
        int panelY = Math.max(SCREEN_MARGIN,
                minecraft.getWindow().getGuiScaledHeight() - BOTTOM_OFFSET - panelHeight);

        // 面板淡出绑定最新一条条目的淡出曲线（不含淡入），新条目到达时面板保持可见不闪烁
        ReaderScanHudState.HudEntry anchor = snapshot.anchor();
        int containerAlpha = Math.round(anchor.fadeOutAlpha(now) * 255.0F);
        // 锚点淡出末尾（alpha 字节 < 4）时所有条目必然更透明，直接跳过整帧绘制
        if (containerAlpha < MIN_TEXT_ALPHA) {
            return;
        }
        drawPanel(graphics, panelX, panelY, panelWidth, panelHeight, containerAlpha);
        int contentX = panelX + PANEL_PADDING;
        int contentWidth = panelWidth - PANEL_PADDING * 2;
        graphics.drawString(font, trimToWidth(font, title, contentWidth),
                contentX, panelY + PANEL_PADDING, withAlpha(HEADER_COLOR, containerAlpha), false);

        int rowY = panelY + PANEL_PADDING + HEADER_HEIGHT;
        for (ReaderScanHudState.HudEntry hudEntry : entries) {
            int alpha = Math.round(hudEntry.alpha(now) * 255.0F);
            // alpha 字节 < 4 时跳过绘制但保留行占位：既避开原版文字不透明强制，也避免布局跳动
            if (alpha >= MIN_TEXT_ALPHA) {
                drawResultRow(graphics, font, hudEntry, contentX, rowY, contentWidth, alpha);
            }
            rowY += ROW_HEIGHT;
        }
    }

    private static void drawPanel(GuiGraphics graphics, int x, int y, int width, int height, int alpha) {
        graphics.fill(x, y, x + width, y + height, withAlpha(PANEL_BACKGROUND, alpha));
        graphics.fill(x, y, x + width, y + 1, withAlpha(PANEL_BORDER, alpha));
        graphics.fill(x, y + height - 1, x + width, y + height, withAlpha(PANEL_BORDER, alpha));
        graphics.fill(x, y, x + 2, y + height, withAlpha(PANEL_ACCENT, alpha));
    }

    private static void drawResultRow(GuiGraphics graphics, Font font,
                                      ReaderScanHudState.HudEntry hudEntry,
                                      int x, int y, int width, int alpha) {
        int textX = x + ICON_SIZE + ICON_TEXT_GAP;
        int textWidth = width - ICON_SIZE - ICON_TEXT_GAP;
        SyncReaderScanResultPayload.ScanEntry entry = hudEntry.scanEntry();
        if (entry == null) {
            graphics.drawString(font, trimToWidth(font, Component.translatable(
                            "hud.unsuspiciousblock.suspicious_reader.containers_only").getString(), textWidth),
                    textX, y + 4, withAlpha(META_COLOR, alpha), false);
            return;
        }

        String itemName;
        if (entry.isEmpty()) {
            graphics.fill(x + 2, y + 3, x + ICON_SIZE - 2, y + ICON_SIZE + 1,
                    withAlpha(0x605C5C5C, alpha));
            itemName = Component.translatable("hud.unsuspiciousblock.suspicious_reader.empty").getString();
        } else {
            ItemStack icon = new ItemStack(BuiltInRegistries.ITEM.get(entry.itemId()));
            RenderSystem.enableBlend();
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, alpha / 255.0F);
            graphics.renderItem(icon, x, y + 1);
            String countText = entry.count() > 1 ? String.valueOf(entry.count()) : null;
            graphics.renderItemDecorations(font, icon, x, y + 1, countText);
            graphics.flush();
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
            itemName = entry.displayName().getString();
        }

        graphics.drawString(font, trimToWidth(font, itemName, textWidth), textX, y + 1,
                withAlpha(entry.alreadyScanned() ? OLD_ITEM_COLOR : ITEM_COLOR, alpha), false);
        String meta = buildMetaText(entry);
        if (!meta.isEmpty()) {
            graphics.drawString(font, trimToWidth(font, meta, textWidth), textX, y + 11,
                    withAlpha(META_COLOR, alpha), false);
        }
    }

    private static String buildMetaText(SyncReaderScanResultPayload.ScanEntry entry) {
        if (entry.alreadyScanned()) {
            return Component.translatable("hud.unsuspiciousblock.suspicious_reader.already_scanned").getString();
        }
        if (entry.sealedByPlayer()) {
            String crafterName = entry.crafterName().isBlank()
                    ? Component.translatable("item.unsuspiciousblock.suspicious_reader.unknown_player").getString()
                    : entry.crafterName();
            return Component.translatable("hud.unsuspiciousblock.suspicious_reader.sealed_by", crafterName).getString();
        }
        return "";
    }

    private static int calculatePanelWidth(Font font, String title,
                                           List<ReaderScanHudState.HudEntry> entries,
                                           int screenWidth) {
        int desiredWidth = font.width(title) + PANEL_PADDING * 2;
        int iconAndPadding = PANEL_PADDING * 2 + ICON_SIZE + ICON_TEXT_GAP;
        for (ReaderScanHudState.HudEntry hudEntry : entries) {
            SyncReaderScanResultPayload.ScanEntry entry = hudEntry.scanEntry();
            String name = entry == null
                    ? Component.translatable("hud.unsuspiciousblock.suspicious_reader.containers_only").getString()
                    : (entry.isEmpty() ? Component.translatable("hud.unsuspiciousblock.suspicious_reader.empty").getString()
                    : entry.displayName().getString());
            desiredWidth = Math.max(desiredWidth, iconAndPadding + font.width(name));
            if (entry != null) {
                desiredWidth = Math.max(desiredWidth, iconAndPadding + font.width(buildMetaText(entry)));
            }
        }
        int availableWidth = Math.max(1, screenWidth - SCREEN_MARGIN * 2);
        int upperBound = Math.min(MAX_PANEL_WIDTH, availableWidth);
        return Math.min(Math.max(MIN_PANEL_WIDTH, desiredWidth), upperBound);
    }

    // 按 Font 的真实像素宽度截断，避免文本溢出面板
    private static String trimToWidth(Font font, String text, int maxWidth) {
        if (maxWidth <= 0 || text.isEmpty()) return "";
        if (font.width(text) <= maxWidth) return text;
        String ellipsis = "...";
        int contentWidth = Math.max(0, maxWidth - font.width(ellipsis));
        return font.plainSubstrByWidth(text, contentWidth) + ellipsis;
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | ((color >>> 24) * alpha / 255 << 24);
    }
}

