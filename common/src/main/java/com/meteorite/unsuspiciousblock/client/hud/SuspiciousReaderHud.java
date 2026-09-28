package com.meteorite.unsuspiciousblock.client.hud;

import com.meteorite.unsuspiciousblock.client.state.ReaderScanHudState;
import com.meteorite.unsuspiciousblock.client.state.ReaderScanHudState.DetailLayout;
import com.meteorite.unsuspiciousblock.client.state.ReaderScanHudState.GroupRow;
import com.meteorite.unsuspiciousblock.client.state.ReaderScanHudState.HudLayout;
import com.meteorite.unsuspiciousblock.client.state.ReaderScanHudState.Snapshot;
import com.meteorite.unsuspiciousblock.client.state.ReaderScanHudState.SummaryLayout;
import com.meteorite.unsuspiciousblock.client.state.ReaderScanHudState.Target;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

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
        long now = ReaderScanHudState.now();
        if (!snapshot.visible(now)) return;
        int alpha = Math.round(snapshot.alpha(now) * 255);
        int screenWidth = mc.getWindow().getGuiScaledWidth();
        int screenHeight = mc.getWindow().getGuiScaledHeight();
        Font font = mc.font;
        int maxWidth = Math.clamp(screenWidth - MARGIN * 2, 1, MAX_WIDTH);
        // 派生文本与布局随快照缓存；这里只做主循环与绘制
        Language language = Language.getInstance();
        HudLayout layout = snapshot.hud();
        Target target = ReaderScanHudState.focusedTarget();
        // 距离行随玩家位置逐帧变化，单独计算；它的宽度参与面板几何，因此一并作为缓存键
        String positionLine = target == null ? null : positionLine(target);
        int positionWidth = positionLine == null ? 0 : font.width(positionLine);

        DetailLayout detail = layout.detailIfValid(language, screenWidth, screenHeight, target, maxWidth, positionWidth);
        if (detail == null && target != null) {
            detail = buildDetail(font, target, maxWidth, screenWidth, screenHeight, positionLine);
            layout.putDetail(language, screenWidth, screenHeight, target, maxWidth, positionWidth, detail);
        }
        if (detail != null) {
            panel(graphics, detail.x(), detail.y(), detail.width(), detail.height(), alpha);
            if (detail.hasIcon() && target != null) icon(graphics, target.icon(), detail.x() + 6, detail.y() + 6, alpha);
            List<String> nameLines = detail.nameLines();
            for (int i = 0; i < nameLines.size(); i++) {
                cachedText(graphics, font, nameLines.get(i), detail.x() + detail.textOffset(),
                        detail.y() + 6 + i * 11, i == 0 ? TEXT : META, alpha);
            }
            int lineIndex = nameLines.size();
            if (positionLine != null) {
                metaText(graphics, font, positionLine, detail.x() + detail.textOffset(),
                        detail.y() + 6 + lineIndex * 11, detail.lineWidth(), alpha);
                lineIndex++;
            }
            if (detail.sealedLine() != null) {
                cachedText(graphics, font, detail.sealedLine(), detail.x() + detail.textOffset(),
                        detail.y() + 6 + lineIndex * 11, META, alpha);
            }
        }

        SummaryLayout summary = layout.summaryIfValid(language, screenWidth, screenHeight);
        if (summary == null) {
            summary = buildSummary(font, snapshot, maxWidth, screenHeight);
            layout.putSummary(language, screenWidth, screenHeight, summary);
        }
        // 小窗口中优先保留正在阅读的目标，避免两个自有面板互相遮挡。
        if (detail != null && intersects(summary, detail)) return;
        panel(graphics, summary.x(), summary.y(), summary.width(), summary.height(), alpha);
        cachedText(graphics, font, summary.title(), summary.x() + 6, summary.y() + 6, ACCENT, alpha);
        if (summary.rows() == 0) {
            cachedText(graphics, font, summary.message(), summary.x() + 6, summary.y() + 17, META, alpha);
        }
        for (GroupRow row : summary.groupRows()) {
            icon(graphics, row.group().icon(), summary.x() + 6, row.y(), alpha);
            cachedText(graphics, font, row.nameText(), row.nameX(), row.y() + 4, TEXT, alpha);
            cachedText(graphics, font, row.countText(), row.countX(), row.y() + 4, META, alpha);
        }
        if (summary.footer()) {
            cachedText(graphics, font, summary.more(), summary.x() + 6, summary.y() + summary.height() - 12, META, alpha);
        }
    }

    // 构建汇总面板布局：翻译文案、测量宽度与分组行坐标都在这里一次算完并随快照缓存
    private static SummaryLayout buildSummary(Font font, Snapshot snapshot, int maxWidth, int screenHeight) {
        List<ReaderScanHudState.ItemGroup> groups = snapshot.groups();
        int rows = Math.min(3, groups.size());
        boolean footer = groups.size() > rows || snapshot.emptyCount() > 0;
        String title = tr("summary", snapshot.blocks().size(), snapshot.containers().size());
        String message = snapshot.blocks().isEmpty() && snapshot.containers().isEmpty()
                ? tr("no_targets") : snapshot.blocks().isEmpty() ? tr("containers_only") : tr("empty");
        int remaining = groups.size() - rows;
        String more = remaining == 0 ? tr("empty_count", snapshot.emptyCount())
                : snapshot.emptyCount() == 0 ? tr("more", remaining)
                : tr("remainder", remaining, snapshot.emptyCount());
        // 将图标、名称、数量与内边距一并测量，短文本收缩，长文本仍受上限约束。
        int desiredWidth = font.width(title) + 12;
        if (rows == 0) desiredWidth = Math.max(desiredWidth, font.width(message) + 12);
        if (footer) desiredWidth = Math.max(desiredWidth, font.width(more) + 12);
        List<String> countTexts = new ArrayList<>(rows);
        for (int i = 0; i < rows; i++) {
            ReaderScanHudState.ItemGroup group = groups.get(i);
            String countText = tr("count", group.count());
            countTexts.add(countText);
            desiredWidth = Math.max(desiredWidth,
                    40 + font.width(group.name().getString()) + font.width(countText));
        }
        int width = Math.min(maxWidth, desiredWidth);
        int height = 27 + rows * 19 + (footer ? 12 : 0);
        int x = MARGIN;
        int y = Math.max(MARGIN, screenHeight - 48 - height);
        List<GroupRow> groupRows = new ArrayList<>(rows);
        for (int i = 0; i < rows; i++) {
            ReaderScanHudState.ItemGroup group = groups.get(i);
            String countText = countTexts.get(i);
            int countWidth = font.width(countText);
            int rowY = y + 21 + i * 19;
            int nameWidth = width - 40 - countWidth;
            groupRows.add(new GroupRow(group, trim(font, group.name().getString(), nameWidth),
                    x + 28, nameWidth, countText, x + width - 6 - countWidth, countWidth, rowY));
        }
        return new SummaryLayout(x, y, width, height, title, message, more, footer, List.copyOf(groupRows));
    }

    // 构建详情面板布局；名称行与封存行随快照缓存，距离行只借用当前文本参与测量
    private static DetailLayout buildDetail(Font font, Target target, int maxWidth,
                                            int screenWidth, int screenHeight, @Nullable String positionLine) {
        int textOffset = target.icon().isEmpty() ? 6 : 28;
        int horizontalPadding = textOffset + 6;
        List<String> nameLines = detailNameLines(font, target, Math.max(1, maxWidth - horizontalPadding));
        String sealed = sealedLine(target);
        // 面板宽度与高度沿用旧口径：距离行与封存行同样参与测量
        List<String> measured = new ArrayList<>(nameLines.size() + 2);
        measured.addAll(nameLines);
        if (positionLine != null) measured.add(positionLine);
        if (sealed != null) measured.add(sealed);
        int width = Math.min(maxWidth, horizontalPadding + measured.stream().mapToInt(font::width).max().orElse(0));
        int height = Math.max(28, measured.size() * 11 + 12);
        // 避开 Jade 默认顶部区域，也为准星与快捷栏留出空隙。
        // 上界在极小窗口下可能小于 MARGIN，先抬到 MARGIN 保证 clamp 的 min <= max。
        int x = Math.clamp(screenWidth / 2 + 16, MARGIN, Math.max(MARGIN, screenWidth - width - MARGIN));
        int y = Math.clamp(screenHeight / 2 + 18, MARGIN, Math.max(MARGIN, screenHeight - height - 44));
        int lineWidth = width - horizontalPadding;
        List<String> trimmed = new ArrayList<>(nameLines.size());
        for (String line : nameLines) trimmed.add(trim(font, line, lineWidth));
        return new DetailLayout(x, y, width, height, textOffset, horizontalPadding,
                !target.icon().isEmpty(), List.copyOf(trimmed), sealed);
    }

    // 详情卡首行组：容器未知或物品名 + 数量，长名字最多两行
    private static List<String> detailNameLines(Font font, Target target, int width) {
        var entry = target.entry();
        if (entry == null) {
            return List.of(tr("container"), tr("unread"));
        }
        List<String> lines = new ArrayList<>(2);
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
        return lines;
    }

    // 玩家到目标中心的距离与高度关系；直接按分量算距离，避免每帧分配 Vec3
    @Nullable
    private static String positionLine(Target target) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return null;
        var eye = player.getEyePosition();
        BlockPos pos = target.pos();
        double dx = eye.x - (pos.getX() + 0.5D);
        double dy = eye.y - (pos.getY() + 0.5D);
        double dz = eye.z - (pos.getZ() + 0.5D);
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        int depth = player.blockPosition().getY() - pos.getY();
        String height = depth > 0 ? tr("below", depth) : depth < 0 ? tr("above", -depth) : tr("same_height");
        return tr("position", String.format(Locale.ROOT, "%.1f", distance), height);
    }

    // 封存者行只与条目数据有关，随快照缓存
    @Nullable
    private static String sealedLine(Target target) {
        var entry = target.entry();
        if (entry == null || !entry.sealedByPlayer()) return null;
        return entry.crafterName().isBlank() ? tr("sealed") : tr("sealed_by", entry.crafterName());
    }

    private static void panel(GuiGraphics graphics, int x, int y, int width, int height, int alpha) {
        graphics.fill(x, y, x + width, y + height, withAlpha(BACKGROUND, alpha));
        graphics.fill(x, y, x + 1, y + height, withAlpha(ACCENT, alpha));
    }

    // 绘制已按宽度截断的缓存文本；空串代表该行无需绘制
    private static void cachedText(GuiGraphics graphics, Font font, @Nullable String value, int x, int y,
                                   int color, int alpha) {
        if (value == null || value.isEmpty()) return;
        graphics.drawString(font, value, x, y, withAlpha(color, alpha), false);
    }

    // 绘制逐帧变化的次要文本（距离行）；按可用宽度裁剪后再落笔
    private static void metaText(GuiGraphics graphics, Font font, String value, int x, int y, int width, int alpha) {
        if (width > 0) cachedText(graphics, font, trim(font, value, width), x, y, META, alpha);
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

    // 面板边界用于窄窗口避让
    private static boolean intersects(SummaryLayout summary, DetailLayout detail) {
        return summary.x() < detail.x() + detail.width() && summary.x() + summary.width() > detail.x()
                && summary.y() < detail.y() + detail.height() && summary.y() + summary.height() > detail.y();
    }
}
