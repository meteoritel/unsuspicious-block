package com.meteorite.unsuspiciousblock.client.hud;

import com.meteorite.unsuspiciousblock.client.state.ReaderScanHudState;
import com.meteorite.unsuspiciousblock.client.state.ReaderScanHudState.Snapshot;
import com.meteorite.unsuspiciousblock.client.state.ReaderScanHudState.Target;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 左下角扫描汇总与准星侧下方的目标详情；独立于 Jade，不注册或屏蔽其 HUD。 */
public final class SuspiciousReaderHud {
    private static final int MARGIN = 6;
    private static final int MAX_WIDTH = 180;
    private static final int BACKGROUND = 0xC018191C;
    private static final int ACCENT = 0xFFE4BF69;
    private static final int TEXT = 0xFFF0F0F0;
    private static final int META = 0xFFAAAAAA;

    private SuspiciousReaderHud() {
    }

    public static void render(GuiGraphics graphics) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui || mc.screen != null || !ReaderScanHudState.isHudEnabled()) return;
        Snapshot snapshot = ReaderScanHudState.snapshot();
        int alpha = Math.round(snapshot.alpha(ReaderScanHudState.now()) * 255);
        // 原版字体会把极低 alpha 强制改为不透明，此处必须跳过。
        if (alpha < 4) return;
        int screenWidth = mc.getWindow().getGuiScaledWidth();
        int screenHeight = mc.getWindow().getGuiScaledHeight();
        Font font = mc.font;
        int maxWidth = Math.clamp(screenWidth - MARGIN * 2, 1, MAX_WIDTH);
        Target target = ReaderScanHudState.focusedTarget();
        Panel detail = null;
        if (target != null) {
            int textOffset = target.icon().isEmpty() ? 6 : 28;
            int horizontalPadding = textOffset + 6;
            List<String> lines = detailLines(font, target, Math.max(1, maxWidth - horizontalPadding));
            int width = Math.min(maxWidth, horizontalPadding + lines.stream().mapToInt(font::width).max().orElse(0));
            int height = Math.max(28, lines.size() * 11 + 12);
            // 避开 Jade 默认顶部区域，也为准星与快捷栏留出空隙。
            // 上界在极小窗口下可能小于 MARGIN，先抬到 MARGIN 保证 clamp 的 min <= max。
            int x = Math.clamp(screenWidth / 2 + 16, MARGIN, Math.max(MARGIN, screenWidth - width - MARGIN));
            int y = Math.clamp(screenHeight / 2 + 18, MARGIN, Math.max(MARGIN, screenHeight - height - 44));
            detail = new Panel(x, y, width, height);
            panel(graphics, detail, alpha);
            if (!target.icon().isEmpty()) icon(graphics, target.icon(), x + 6, y + 6, alpha);
            for (int i = 0; i < lines.size(); i++) {
                text(graphics, font, lines.get(i), x + textOffset, y + 6 + i * 11, width - horizontalPadding,
                        i == 0 ? TEXT : META, alpha);
            }
        }
        int rows = Math.min(3, snapshot.groups().size());
        boolean footer = snapshot.groups().size() > rows || snapshot.emptyCount() > 0;
        String title = tr("summary", snapshot.blocks().size(), snapshot.containers().size());
        String message = snapshot.blocks().isEmpty() && snapshot.containers().isEmpty()
                ? tr("no_targets") : snapshot.blocks().isEmpty() ? tr("containers_only") : tr("empty");
        int remaining = snapshot.groups().size() - rows;
        String more = remaining == 0 ? tr("empty_count", snapshot.emptyCount())
                : snapshot.emptyCount() == 0 ? tr("more", remaining)
                : tr("remainder", remaining, snapshot.emptyCount());
        // 将图标、名称、数量与内边距一并测量，短文本收缩，长文本仍受上限约束。
        int desiredWidth = font.width(title) + 12;
        if (rows == 0) desiredWidth = Math.max(desiredWidth, font.width(message) + 12);
        if (footer) desiredWidth = Math.max(desiredWidth, font.width(more) + 12);
        for (int i = 0; i < rows; i++) {
            var group = snapshot.groups().get(i);
            desiredWidth = Math.max(desiredWidth,
                    40 + font.width(group.name().getString()) + font.width(tr("count", group.count())));
        }
        int width = Math.min(maxWidth, desiredWidth);
        int height = 27 + rows * 19 + (footer ? 12 : 0);
        Panel summary = new Panel(MARGIN, Math.max(MARGIN, screenHeight - 48 - height), width, height);
        // 小窗口中优先保留正在阅读的目标，避免两个自有面板互相遮挡。
        if (detail != null && summary.intersects(detail)) return;
        panel(graphics, summary, alpha);
        text(graphics, font, title, summary.x + 6, summary.y + 6, width - 12, ACCENT, alpha);
        if (rows == 0) {
            text(graphics, font, message, summary.x + 6, summary.y + 17, width - 12, META, alpha);
        }
        for (int i = 0; i < rows; i++) {
            var group = snapshot.groups().get(i);
            int y = summary.y + 21 + i * 19;
            icon(graphics, group.icon(), summary.x + 6, y, alpha);
            String count = tr("count", group.count());
            int countWidth = font.width(count);
            text(graphics, font, group.name().getString(), summary.x + 28, y + 4,
                    width - 40 - countWidth, TEXT, alpha);
            text(graphics, font, count, summary.x + width - 6 - countWidth, y + 4, countWidth, META, alpha);
        }
        if (footer) {
            text(graphics, font, more, summary.x + 6, summary.y + height - 12, width - 12, META, alpha);
        }
    }

    private static List<String> detailLines(Font font, Target target, int width) {
        List<String> lines = new ArrayList<>();
        var entry = target.entry();
        if (entry == null) {
            lines.add(tr("container"));
            lines.add(tr("unread"));
        } else {
            String name = entry.isEmpty() ? tr("empty") : entry.displayName().getString();
            String count = entry.isEmpty() ? "" : " " + tr("count", entry.count());
            // 数量留在首行右侧，长名字最多两行；英文按实际像素宽度处理。
            if (font.width(name + count) <= width) {
                lines.add(name + count);
            } else {
                String first = font.plainSubstrByWidth(name, Math.max(1, width - font.width(count)));
                lines.add(first + count);
                lines.add(trim(font, name.substring(first.length()).stripLeading(), width));
            }
        }
        var player = Minecraft.getInstance().player;
        if (player == null) return lines;
        double distance = player.getEyePosition().distanceTo(Vec3.atCenterOf(target.pos()));
        int depth = player.blockPosition().getY() - target.pos().getY();
        String height = depth > 0 ? tr("below", depth) : depth < 0 ? tr("above", -depth) : tr("same_height");
        lines.add(tr("position", String.format(Locale.ROOT, "%.1f", distance), height));
        if (entry != null && entry.sealedByPlayer()) {
            lines.add(entry.crafterName().isBlank() ? tr("sealed") : tr("sealed_by", entry.crafterName()));
        }
        return lines;
    }

    private static void panel(GuiGraphics graphics, Panel panel, int alpha) {
        graphics.fill(panel.x, panel.y, panel.x + panel.width, panel.y + panel.height, withAlpha(BACKGROUND, alpha));
        graphics.fill(panel.x, panel.y, panel.x + 1, panel.y + panel.height, withAlpha(ACCENT, alpha));
    }

    private static void text(GuiGraphics graphics, Font font, String value, int x, int y, int width, int color, int alpha) {
        if (width > 0) graphics.drawString(font, trim(font, value, width), x, y, withAlpha(color, alpha), false);
    }

    private static String trim(Font font, String value, int width) {
        if (width <= 0) return "";
        if (font.width(value) <= width) return value;
        String dots = "...";
        if (font.width(dots) > width) return font.plainSubstrByWidth(dots, width);
        return font.plainSubstrByWidth(value, width - font.width(dots)) + dots;
    }

    private static void icon(GuiGraphics graphics, ItemStack icon, int x, int y, int alpha) {
        graphics.flush();
        RenderSystem.enableBlend();
        RenderSystem.setShaderColor(1, 1, 1, alpha / 255.0F);
        try {
            graphics.renderItem(icon, x, y);
            graphics.flush();
        } finally {
            RenderSystem.setShaderColor(1, 1, 1, 1);
            RenderSystem.disableBlend();
        }
    }

    private static String tr(String suffix, Object... args) {
        return Component.translatable("hud.unsuspiciousblock.suspicious_reader." + suffix, args).getString();
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | ((color >>> 24) * alpha / 255 << 24);
    }

    /** 面板边界用于窄窗口避让。 */
    private record Panel(int x, int y, int width, int height) {
        private boolean intersects(Panel other) {
            return x < other.x + other.width && x + width > other.x
                    && y < other.y + other.height && y + height > other.y;
        }
    }
}
