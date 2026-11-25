package com.meteorite.unsuspiciousblock.client.ui.tooltip;

import com.meteorite.meteosuikit.api.geometry.UiRect;
import com.meteorite.meteosuikit.api.input.UiPointerHit;
import com.meteorite.meteosuikit.api.render.UiIcon;
import com.meteorite.meteosuikit.api.render.UiTooltipPanel;
import com.meteorite.meteosuikit.api.style.UiTooltipSkin;
import com.meteorite.unsuspiciousblock.client.ui.theme.UnsuspiciousUiTheme;
import com.meteorite.unsuspiciousblock.core.text.TooltipBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 考古笔记富 Tooltip 统一渲染器。
 *
 * <p>基于 UI Kit {@link UiTooltipPanel} 与 {@link UiTooltipSkin} 绘制原版暗底外框，
 * 内层采用无阴影文字排版并支持图标、胶囊徽章、横向指标条、像素树连线与双列对齐表格。</p>
 */
public final class JournalRichTooltip {

    private static final int MAX_CONTENT_WIDTH = 224;
    private static final int ICON_GAP = 4;
    private static final int BLOCK_GAP = 2;
    private static final int HEADER_EXTRA_GAP = 3;
    private static final int CHIP_GAP = 4;
    private static final int CHIP_ROW_GAP = 3;
    private static final int CHIP_PAD_X = 4;
    private static final int CHIP_PAD_Y = 1;
    private static final int PILL_PAD_X = 4;
    private static final int PILL_PAD_Y = 1;
    private static final int TREE_STEP_PX = 10;
    private static final int TREE_ICON_GAP = 3;
    private static final int DIVIDER_HEIGHT = 5;
    private static final int KV_COLUMN_GAP = 6;

    private static final int DIVIDER_COLOR = 0x38FFFFFF;
    private static final int TREE_LINE_COLOR = 0x70AAAAAA;
    private static final int PILL_BG_ALPHA = 0x2A000000;
    private static final int PILL_BORDER_ALPHA = 0x88000000;
    private static final int CHIP_NEUTRAL_BG = 0x1EFFFFFF;
    private static final int CHIP_NEUTRAL_BORDER = 0x38FFFFFF;
    private static final int OPAQUE_ALPHA = 0xFF000000;

    private JournalRichTooltip() {
    }

    /**
     * 将富 Tooltip 绑定为 {@link UiPointerHit.Tooltip} 呈现回调。
     */
    public static UiPointerHit.Tooltip presentation(JournalTooltipSpec spec) {
        return (graphics, font, px, py) -> render(graphics, font, spec, px, py);
    }

    /**
     * 若 {@code spec} 非空，将其富提示回调附加到命中结果上。
     */
    public static UiPointerHit attach(UiPointerHit hit, @Nullable JournalTooltipSpec spec) {
        if (spec == null || spec.isEmpty()) {
            return hit;
        }
        return hit.withPresentation(presentation(spec));
    }

    /**
     * 在原版 GUI 坐标系中测量并绘制富 Tooltip。
     */
    public static void render(GuiGraphics graphics, Font font, JournalTooltipSpec spec, int mouseX, int mouseY) {
        if (spec.isEmpty()) {
            return;
        }
        UiTooltipSkin skin = UnsuspiciousUiTheme.PARCHMENT.tooltipSkin();
        UiRect host = new UiRect(0, 0, graphics.guiWidth(), graphics.guiHeight());
        int budget = UiTooltipPanel.contentWidth(host, MAX_CONTENT_WIDTH, skin.padding(), UiTooltipPanel.SCREEN_MARGIN);
        if (budget <= 0) {
            return;
        }

        List<MeasuredItem> items = measure(font, spec.blocks(), budget);
        if (items.isEmpty()) {
            return;
        }

        int contentWidth = 0;
        int contentHeight = 0;
        for (int i = 0; i < items.size(); i++) {
            MeasuredItem item = items.get(i);
            contentWidth = Math.max(contentWidth, item.width());
            contentHeight += item.height();
            if (i + 1 < items.size()) {
                contentHeight += gapAfter(item, items.get(i + 1));
            }
        }
        contentWidth = Math.min(budget, contentWidth);

        UiTooltipPanel panel = UiTooltipPanel.layout(host, contentWidth, contentHeight, skin.padding(), mouseX, mouseY);
        int finalContentWidth = panel.contentBounds().width();
        panel.render(graphics, rect -> skin.paint(graphics, rect), content -> {
            int y = content.y();
            for (int i = 0; i < items.size(); i++) {
                MeasuredItem item = items.get(i);
                item.render(graphics, font, content.x(), y, finalContentWidth);
                y += item.height();
                if (i + 1 < items.size()) {
                    y += gapAfter(item, items.get(i + 1));
                }
            }
        });
    }

    private static int gapAfter(MeasuredItem current, MeasuredItem next) {
        if (current instanceof MeasuredDivider || next instanceof MeasuredDivider || next instanceof MeasuredFooter) {
            return 0;
        }
        if (current instanceof MeasuredHeader) {
            return HEADER_EXTRA_GAP;
        }
        return BLOCK_GAP;
    }

    private static List<MeasuredItem> measure(Font font, List<JournalTooltipSpec.Block> blocks, int budget) {
        int[] kvBadgeWidths = computeKeyColumnWidths(font, blocks);
        List<MeasuredItem> items = new ArrayList<>(blocks.size());
        for (int i = 0; i < blocks.size(); i++) {
            JournalTooltipSpec.Block block = blocks.get(i);
            switch (block) {
                case JournalTooltipSpec.Header header -> items.add(measureHeader(font, header, budget));
                case JournalTooltipSpec.MetricRow row -> items.add(measureMetricRow(font, row, budget));
                case JournalTooltipSpec.TextLine line -> items.add(measureTextLine(font, line, budget));
                case JournalTooltipSpec.TreeRow tree -> items.add(measureTreeRow(font, tree, budget));
                case JournalTooltipSpec.KeyValueRow kv -> items.add(measureKeyValue(font, kv, kvBadgeWidths[i], budget));
                case JournalTooltipSpec.Divider ignored -> items.add(new MeasuredDivider());
                case JournalTooltipSpec.NativeComponent nativeComp -> {
                    ClientTooltipComponent client = ModTooltipComponents.create(nativeComp.component());
                    if (client != null) {
                        items.add(new MeasuredNative(client, client.getWidth(font), client.getHeight()));
                    }
                }
                case JournalTooltipSpec.FooterHint footer -> items.add(measureFooter(font, footer, budget, !items.isEmpty()));
            }
        }
        return items;
    }

    private static int[] computeKeyColumnWidths(Font font, List<JournalTooltipSpec.Block> blocks) {
        int[] widths = new int[blocks.size()];
        int i = 0;
        while (i < blocks.size()) {
            if (!(blocks.get(i) instanceof JournalTooltipSpec.KeyValueRow)) {
                i++;
                continue;
            }
            int start = i;
            int maxWidth = 0;
            while (i < blocks.size() && blocks.get(i) instanceof JournalTooltipSpec.KeyValueRow kv) {
                maxWidth = Math.max(maxWidth, pillWidth(font, kv.keyBadge()));
                i++;
            }
            for (int j = start; j < i; j++) {
                widths[j] = maxWidth;
            }
        }
        return widths;
    }

    private static MeasuredHeader measureHeader(Font font, JournalTooltipSpec.Header header, int budget) {
        int iconW = header.icon() == null ? 0 : header.icon().width() + ICON_GAP;
        int badgeW = header.badge() == null ? 0 : pillWidth(font, header.badge().label()) + 6;
        int textBudget = Math.max(24, budget - iconW - badgeW);
        List<FormattedCharSequence> lines = font.split(header.title().copy().withStyle(header.titleColor()), textBudget);
        if (lines.isEmpty()) {
            lines = List.of(FormattedCharSequence.EMPTY);
        }
        int widestLine = 0;
        for (FormattedCharSequence line : lines) {
            widestLine = Math.max(widestLine, font.width(line));
        }
        int textHeight = lines.size() * font.lineHeight;
        int pillH = header.badge() == null ? 0 : pillHeight(font);
        int iconH = header.icon() == null ? 0 : header.icon().height();
        int totalH = Math.max(textHeight, Math.max(iconH, pillH));
        int totalW = iconW + widestLine + badgeW;
        return new MeasuredHeader(header.icon(), lines, header.badge(), totalW, totalH);
    }

    private static MeasuredMetricRow measureMetricRow(Font font, JournalTooltipSpec.MetricRow row, int budget) {
        List<List<MeasuredChip>> wrappedRows = new ArrayList<>();
        List<MeasuredChip> currentRow = new ArrayList<>();
        int currentWidth = 0;
        int maxRowWidth = 0;
        for (JournalTooltipSpec.MetricChip chip : row.chips()) {
            int chipW = measureChipWidth(font, chip);
            int needed = currentRow.isEmpty() ? chipW : currentWidth + CHIP_GAP + chipW;
            if (!currentRow.isEmpty() && needed > budget) {
                wrappedRows.add(currentRow);
                maxRowWidth = Math.max(maxRowWidth, currentWidth);
                currentRow = new ArrayList<>();
                currentWidth = 0;
            }
            currentRow.add(new MeasuredChip(chip, chipW));
            currentWidth = currentRow.size() == 1 ? chipW : currentWidth + CHIP_GAP + chipW;
        }
        if (!currentRow.isEmpty()) {
            wrappedRows.add(currentRow);
            maxRowWidth = Math.max(maxRowWidth, currentWidth);
        }
        int chipH = chipHeight(font);
        int totalH = wrappedRows.size() * chipH + Math.max(0, wrappedRows.size() - 1) * CHIP_ROW_GAP;
        return new MeasuredMetricRow(wrappedRows, chipH, Math.min(budget, maxRowWidth), totalH);
    }

    private static MeasuredTextLine measureTextLine(Font font, JournalTooltipSpec.TextLine line, int budget) {
        int iconReserve = line.leadingIcon() == null ? 0 : line.leadingIcon().width() + ICON_GAP;
        int textXOffset = line.indentPx() + iconReserve;
        int textBudget = Math.max(24, budget - textXOffset);
        List<FormattedCharSequence> wrapped = font.split(line.text(), textBudget);
        if (wrapped.isEmpty()) {
            wrapped = List.of(FormattedCharSequence.EMPTY);
        }
        int widest = 0;
        for (FormattedCharSequence seq : wrapped) {
            widest = Math.max(widest, font.width(seq));
        }
        int iconH = line.leadingIcon() == null ? 0 : line.leadingIcon().height();
        int height = Math.max(wrapped.size() * font.lineHeight, iconH);
        return new MeasuredTextLine(wrapped, line.leadingIcon(), line.indentPx(), textXOffset + widest, height);
    }

    private static MeasuredTreeRow measureTreeRow(Font font, JournalTooltipSpec.TreeRow tree, int budget) {
        int branchWidth = tree.depth() * TREE_STEP_PX;
        int iconReserve = tree.icon() == null ? 0 : tree.icon().width() + TREE_ICON_GAP;
        int textBudget = Math.max(24, budget - branchWidth - iconReserve);
        List<FormattedCharSequence> wrapped = font.split(tree.text(), textBudget);
        if (wrapped.isEmpty()) {
            wrapped = List.of(FormattedCharSequence.EMPTY);
        }
        int widest = 0;
        for (FormattedCharSequence seq : wrapped) {
            widest = Math.max(widest, font.width(seq));
        }
        int iconH = tree.icon() == null ? 0 : tree.icon().height();
        int height = Math.max(wrapped.size() * font.lineHeight, iconH);
        return new MeasuredTreeRow(tree.depth(), tree.isLast(), tree.ancestorContinues(), tree.icon(),
                wrapped, branchWidth + iconReserve + widest, height);
    }

    private static MeasuredKeyValue measureKeyValue(Font font, JournalTooltipSpec.KeyValueRow kv,
                                                    int keyColumnWidth, int budget) {
        int descBudget = Math.max(24, budget - keyColumnWidth - KV_COLUMN_GAP);
        List<FormattedCharSequence> wrapped = font.split(kv.description(), descBudget);
        if (wrapped.isEmpty()) {
            wrapped = List.of(FormattedCharSequence.EMPTY);
        }
        int widest = 0;
        for (FormattedCharSequence seq : wrapped) {
            widest = Math.max(widest, font.width(seq));
        }
        int pillH = pillHeight(font);
        int height = Math.max(pillH, wrapped.size() * font.lineHeight);
        return new MeasuredKeyValue(kv.keyBadge(), kv.keyColor(), keyColumnWidth, wrapped,
                keyColumnWidth + KV_COLUMN_GAP + widest, height);
    }

    private static MeasuredFooter measureFooter(Font font, JournalTooltipSpec.FooterHint footer,
                                                int budget, boolean hasTopDivider) {
        int iconW = footer.icon() == null ? 0 : footer.icon().width() + ICON_GAP;
        int badgeW = pillWidth(font, footer.actionBadge());
        int prefixW = iconW + badgeW + 4;
        int descBudget = Math.max(24, budget - prefixW);
        List<FormattedCharSequence> wrapped = font.split(footer.description().copy().withStyle(TooltipBuilder.HINT), descBudget);
        if (wrapped.isEmpty()) {
            wrapped = List.of(FormattedCharSequence.EMPTY);
        }
        int widest = 0;
        for (FormattedCharSequence seq : wrapped) {
            widest = Math.max(widest, font.width(seq));
        }
        int dividerH = hasTopDivider ? DIVIDER_HEIGHT : 0;
        int bodyH = Math.max(pillHeight(font), wrapped.size() * font.lineHeight);
        return new MeasuredFooter(footer.icon(), footer.actionBadge(), footer.badgeColor(), badgeW,
                wrapped, hasTopDivider, prefixW + widest, dividerH + bodyH);
    }

    private static int pillWidth(Font font, Component label) {
        return font.width(label) + PILL_PAD_X * 2;
    }

    private static int pillHeight(Font font) {
        return font.lineHeight + PILL_PAD_Y * 2;
    }

    private static int chipHeight(Font font) {
        return Math.max(10 + CHIP_PAD_Y * 2, font.lineHeight + CHIP_PAD_Y * 2);
    }

    private static int measureChipWidth(Font font, JournalTooltipSpec.MetricChip chip) {
        int iconW = chip.icon() == null ? 0 : chip.icon().width() + 3;
        int pad = chip.bordered() ? CHIP_PAD_X * 2 : 0;
        return pad + iconW + font.width(chip.text());
    }

    private static void drawPill(GuiGraphics graphics, Font font, int x, int y, int width,
                                 Component label, ChatFormatting color) {
        int height = pillHeight(font);
        int rgb = colorRgb(color);
        drawPillBox(graphics, x, y, width, height, rgb | PILL_BG_ALPHA, rgb | PILL_BORDER_ALPHA);
        int textX = x + (width - font.width(label)) / 2;
        int textY = y + PILL_PAD_Y + 1;
        graphics.drawString(font, label, textX, textY, rgb | OPAQUE_ALPHA, false);
    }

    private static void drawPillBox(GuiGraphics graphics, int x, int y, int width, int height,
                                    int fill, int border) {
        graphics.fill(x + 1, y, x + width - 1, y + height, fill);
        graphics.fill(x, y + 1, x + 1, y + height - 1, fill);
        graphics.fill(x + width - 1, y + 1, x + width, y + height - 1, fill);
        graphics.fill(x + 1, y, x + width - 1, y + 1, border);
        graphics.fill(x + 1, y + height - 1, x + width - 1, y + height, border);
        graphics.fill(x, y + 1, x + 1, y + height - 1, border);
        graphics.fill(x + width - 1, y + 1, x + width, y + height - 1, border);
    }

    private static int colorRgb(ChatFormatting formatting) {
        Integer rgb = formatting.getColor();
        return rgb == null ? 0xFFFFFF : rgb;
    }

    private static int colorArgb(ChatFormatting formatting) {
        return colorRgb(formatting) | OPAQUE_ALPHA;
    }

    private sealed interface MeasuredItem permits MeasuredHeader, MeasuredMetricRow, MeasuredTextLine,
            MeasuredTreeRow, MeasuredKeyValue, MeasuredDivider, MeasuredNative, MeasuredFooter {
        int width();

        int height();

        void render(GuiGraphics graphics, Font font, int x, int y, int contentWidth);
    }

    private record MeasuredHeader(@Nullable UiIcon icon, List<FormattedCharSequence> lines,
                                  @Nullable JournalTooltipSpec.Pill badge,
                                  int width, int height) implements MeasuredItem {
        @Override
        public void render(GuiGraphics graphics, Font font, int x, int y, int contentWidth) {
            int textX = x;
            if (icon != null) {
                int iconY = y + (height - icon.height()) / 2;
                icon.render(graphics, x, iconY);
                textX += icon.width() + ICON_GAP;
            }
            int textBlockH = lines.size() * font.lineHeight;
            int lineY = y + Math.max(0, (height - textBlockH) / 2);
            for (FormattedCharSequence line : lines) {
                graphics.drawString(font, line, textX, lineY, OPAQUE_ALPHA | 0xFFFFFF, false);
                lineY += font.lineHeight;
            }
            if (badge != null) {
                int pw = pillWidth(font, badge.label());
                int px = x + contentWidth - pw;
                int py = y + Math.max(0, (height - pillHeight(font)) / 2);
                drawPill(graphics, font, px, py, pw, badge.label(), badge.color());
            }
        }
    }

    private record MeasuredChip(JournalTooltipSpec.MetricChip chip, int width) {
    }

    private record MeasuredMetricRow(List<List<MeasuredChip>> rows, int chipHeight,
                                     int width, int height) implements MeasuredItem {
        @Override
        public void render(GuiGraphics graphics, Font font, int x, int y, int contentWidth) {
            int rowY = y;
            for (List<MeasuredChip> row : rows) {
                int cx = x;
                for (MeasuredChip measured : row) {
                    JournalTooltipSpec.MetricChip chip = measured.chip();
                    int rgb = colorRgb(chip.color());
                    if (chip.bordered()) {
                        int fill = chip.color() == TooltipBuilder.LABEL ? CHIP_NEUTRAL_BG : (rgb | PILL_BG_ALPHA);
                        int border = chip.color() == TooltipBuilder.LABEL ? CHIP_NEUTRAL_BORDER : (rgb | PILL_BORDER_ALPHA);
                        drawPillBox(graphics, cx, rowY, measured.width(), chipHeight, fill, border);
                    }
                    int innerX = cx + (chip.bordered() ? CHIP_PAD_X : 0);
                    if (chip.icon() != null) {
                        int iconY = rowY + (chipHeight - chip.icon().height()) / 2;
                        chip.icon().render(graphics, innerX, iconY);
                        innerX += chip.icon().width() + 3;
                    }
                    int textY = rowY + (chipHeight - font.lineHeight) / 2 + 1;
                    graphics.drawString(font, chip.text(), innerX, textY, rgb | OPAQUE_ALPHA, false);
                    cx += measured.width() + CHIP_GAP;
                }
                rowY += chipHeight + CHIP_ROW_GAP;
            }
        }
    }

    private record MeasuredTextLine(List<FormattedCharSequence> lines, @Nullable UiIcon leadingIcon,
                                    int indentPx, int width, int height) implements MeasuredItem {
        @Override
        public void render(GuiGraphics graphics, Font font, int x, int y, int contentWidth) {
            int drawX = x + indentPx;
            if (leadingIcon != null) {
                int iconY = y + Math.max(0, (font.lineHeight - leadingIcon.height()) / 2);
                leadingIcon.render(graphics, drawX, iconY);
                drawX += leadingIcon.width() + ICON_GAP;
            }
            int lineY = y + Math.max(0, (height - lines.size() * font.lineHeight) / 2);
            for (FormattedCharSequence line : lines) {
                graphics.drawString(font, line, drawX, lineY, OPAQUE_ALPHA | 0xFFFFFF, false);
                lineY += font.lineHeight;
            }
        }
    }

    private record MeasuredTreeRow(int depth, boolean isLast, List<Boolean> ancestorContinues,
                                   @Nullable UiIcon icon, List<FormattedCharSequence> lines,
                                   int width, int height) implements MeasuredItem {
        @Override
        public void render(GuiGraphics graphics, Font font, int x, int y, int contentWidth) {
            int rowBottom = y + height + BLOCK_GAP;
            for (int level = 0; level < ancestorContinues.size(); level++) {
                if (ancestorContinues.get(level)) {
                    int lineX = x + level * TREE_STEP_PX + 3;
                    graphics.fill(lineX, y - BLOCK_GAP, lineX + 1, rowBottom, TREE_LINE_COLOR);
                }
            }
            if (depth > 0) {
                int trunkX = x + (depth - 1) * TREE_STEP_PX + 3;
                int midY = y + font.lineHeight / 2;
                int verticalEnd = isLast ? midY + 1 : rowBottom;
                graphics.fill(trunkX, y - BLOCK_GAP, trunkX + 1, verticalEnd, TREE_LINE_COLOR);
                graphics.fill(trunkX, midY, x + depth * TREE_STEP_PX - 1, midY + 1, TREE_LINE_COLOR);
            }
            int contentX = x + depth * TREE_STEP_PX;
            if (icon != null) {
                int iconY = y + Math.max(0, (font.lineHeight - icon.height()) / 2);
                icon.render(graphics, contentX, iconY);
                contentX += icon.width() + TREE_ICON_GAP;
            }
            int lineY = y;
            for (FormattedCharSequence line : lines) {
                graphics.drawString(font, line, contentX, lineY, OPAQUE_ALPHA | 0xFFFFFF, false);
                lineY += font.lineHeight;
            }
        }
    }

    private record MeasuredKeyValue(Component keyBadge, ChatFormatting keyColor, int keyColumnWidth,
                                    List<FormattedCharSequence> lines, int width, int height) implements MeasuredItem {
        @Override
        public void render(GuiGraphics graphics, Font font, int x, int y, int contentWidth) {
            int pw = pillWidth(font, keyBadge);
            int pillY = y + Math.max(0, (font.lineHeight - pillHeight(font)) / 2);
            drawPill(graphics, font, x, pillY, pw, keyBadge, keyColor);
            int descX = x + keyColumnWidth + KV_COLUMN_GAP;
            int lineY = y + Math.max(0, (height - lines.size() * font.lineHeight) / 2);
            for (FormattedCharSequence line : lines) {
                graphics.drawString(font, line, descX, lineY, colorArgb(TooltipBuilder.LABEL), false);
                lineY += font.lineHeight;
            }
        }
    }

    private record MeasuredDivider() implements MeasuredItem {
        @Override
        public int width() {
            return 1;
        }

        @Override
        public int height() {
            return DIVIDER_HEIGHT;
        }

        @Override
        public void render(GuiGraphics graphics, Font font, int x, int y, int contentWidth) {
            graphics.fill(x, y + 2, x + contentWidth, y + 3, DIVIDER_COLOR);
        }
    }

    private record MeasuredNative(ClientTooltipComponent client, int width, int height) implements MeasuredItem {
        @Override
        public void render(GuiGraphics graphics, Font font, int x, int y, int contentWidth) {
            client.renderImage(font, x, y, graphics);
        }
    }

    private record MeasuredFooter(@Nullable UiIcon icon, Component actionBadge, ChatFormatting badgeColor,
                                  int badgeWidth, List<FormattedCharSequence> lines, boolean hasTopDivider,
                                  int width, int height) implements MeasuredItem {
        @Override
        public void render(GuiGraphics graphics, Font font, int x, int y, int contentWidth) {
            int bodyY = y;
            if (hasTopDivider) {
                graphics.fill(x, y + 2, x + contentWidth, y + 3, DIVIDER_COLOR);
                bodyY += DIVIDER_HEIGHT;
            }
            int cx = x;
            if (icon != null) {
                int iconY = bodyY + Math.max(0, (pillHeight(font) - icon.height()) / 2);
                icon.render(graphics, cx, iconY);
                cx += icon.width() + ICON_GAP;
            }
            drawPill(graphics, font, cx, bodyY, badgeWidth, actionBadge, badgeColor);
            cx += badgeWidth + 4;
            int textY = bodyY + Math.max(0, (pillHeight(font) - lines.size() * font.lineHeight) / 2) + 1;
            for (FormattedCharSequence line : lines) {
                graphics.drawString(font, line, cx, textY, colorArgb(TooltipBuilder.HINT), false);
                textY += font.lineHeight;
            }
        }
    }
}
