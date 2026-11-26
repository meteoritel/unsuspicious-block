package com.meteorite.unsuspiciousblock.client.ui.tooltip;

import com.meteorite.meteosuikit.api.geometry.UiRect;
import com.meteorite.meteosuikit.api.input.UiPointerHit;
import com.meteorite.meteosuikit.api.render.UiTooltipPanel;
import com.meteorite.meteosuikit.api.style.UiTooltipSkin;
import com.meteorite.unsuspiciousblock.client.ui.theme.UnsuspiciousUiTheme;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 考古笔记 Tooltip 统一呈现器。
 *
 * <p>基于 UI Kit {@link UiTooltipPanel} 与 {@link UiTooltipSkin} 绘制古铜墨板外框，
 * 内层采用 Minecraft 原版阴影字体排版（首行标题留白 + 规范换行 + 空行分段），
 * 并支持在末尾拼接原生或自定义 {@link ClientTooltipComponent}（如标本箱预览）。</p>
 */
public final class JournalRichTooltip {

    private static final int MAX_CONTENT_WIDTH = 220;
    private static final int FIRST_LINE_GAP = 2;
    private static final int LINE_GAP = 1;
    private static final int BLANK_LINE_HEIGHT = 4;
    private static final int IMAGE_TOP_GAP = 3;
    private static final int DEFAULT_TEXT_COLOR = 0xFFFFFFFF;

    private JournalRichTooltip() {
    }

    /**
     * 将 {@link JournalTooltipSpec} 绑定为 {@link UiPointerHit.Tooltip} 呈现回调。
     */
    public static UiPointerHit.Tooltip presentation(JournalTooltipSpec spec) {
        return (graphics, font, px, py) -> render(graphics, font, spec, px, py);
    }

    /**
     * 若 {@code spec} 非空，将其统一呈现回调附加到命中结果上。
     */
    public static UiPointerHit attach(UiPointerHit hit, @Nullable JournalTooltipSpec spec) {
        if (spec == null || spec.isEmpty()) {
            return hit;
        }
        return hit.withPresentation(presentation(spec));
    }

    /**
     * 为仅携带纯文本提示的 {@link UiPointerHit} 升级为统一的原生阴影 Tooltip 呈现。
     */
    public static UiPointerHit attachText(UiPointerHit hit) {
        if (hit.presentation() != null || hit.tooltip() == null || hit.tooltip().tooltip().isEmpty()) {
            return hit;
        }
        return hit.withPresentation(presentation(JournalTooltipSpec.of(hit.tooltip().tooltip())));
    }

    /**
     * 在原版 GUI 坐标系中测量并绘制考古笔记 Tooltip。
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

        List<MeasuredEntry> entries = measureEntries(font, spec, budget);
        if (entries.isEmpty()) {
            return;
        }

        int contentWidth = 0;
        int contentHeight = 0;
        for (int i = 0; i < entries.size(); i++) {
            MeasuredEntry entry = entries.get(i);
            contentWidth = Math.max(contentWidth, entry.width());
            contentHeight += entry.height();
            if (i + 1 < entries.size()) {
                contentHeight += gapBetween(entry, entries.get(i + 1));
            }
        }
        contentWidth = Math.min(budget, contentWidth);

        UiTooltipPanel panel = UiTooltipPanel.layout(host, contentWidth, contentHeight, skin.padding(), mouseX, mouseY);
        panel.render(graphics, rect -> skin.paint(graphics, rect), content -> {
            int y = content.y();
            for (int i = 0; i < entries.size(); i++) {
                MeasuredEntry entry = entries.get(i);
                entry.render(graphics, font, content.x(), y);
                y += entry.height();
                if (i + 1 < entries.size()) {
                    y += gapBetween(entry, entries.get(i + 1));
                }
            }
        });
    }

    private static List<MeasuredEntry> measureEntries(Font font, JournalTooltipSpec spec, int budget) {
        List<MeasuredEntry> entries = new ArrayList<>(spec.lines().size() + 1);
        boolean seenTitle = false;
        for (Component line : spec.lines()) {
            if (line.getString().isEmpty()) {
                if (!entries.isEmpty() && !(entries.getLast() instanceof BlankGap)) {
                    entries.add(BlankGap.INSTANCE);
                }
                continue;
            }
            boolean isTitle = !seenTitle;
            seenTitle = true;
            List<FormattedCharSequence> wrapped = font.split(line, budget);
            for (int i = 0; i < wrapped.size(); i++) {
                FormattedCharSequence seq = wrapped.get(i);
                boolean endsTitle = isTitle && i == wrapped.size() - 1;
                entries.add(new TextRow(seq, font.width(seq), font.lineHeight, endsTitle));
            }
        }
        if (!entries.isEmpty() && entries.getLast() instanceof BlankGap) {
            entries.removeLast();
        }
        if (spec.image() != null) {
            ClientTooltipComponent client = ModTooltipComponents.create(spec.image());
            if (client != null) {
                entries.add(new ImageBlock(client, client.getWidth(font), client.getHeight()));
            }
        }
        return entries;
    }

    private static int gapBetween(MeasuredEntry current, MeasuredEntry next) {
        if (next instanceof ImageBlock) {
            return current instanceof BlankGap ? 0 : IMAGE_TOP_GAP;
        }
        if (current instanceof BlankGap || next instanceof BlankGap) {
            return 0;
        }
        boolean endsTitle = current instanceof TextRow row && row.endsTitle();
        return endsTitle ? FIRST_LINE_GAP + LINE_GAP : LINE_GAP;
    }

    private sealed interface MeasuredEntry permits TextRow, BlankGap, ImageBlock {
        int width();

        int height();

        void render(GuiGraphics graphics, Font font, int x, int y);
    }

    private record TextRow(FormattedCharSequence sequence, int width, int height,
                           boolean endsTitle) implements MeasuredEntry {
        @Override
        public void render(GuiGraphics graphics, Font font, int x, int y) {
            graphics.drawString(font, sequence, x, y, DEFAULT_TEXT_COLOR, true);
        }
    }

    private record BlankGap() implements MeasuredEntry {
        private static final BlankGap INSTANCE = new BlankGap();

        @Override
        public int width() {
            return 0;
        }

        @Override
        public int height() {
            return BLANK_LINE_HEIGHT;
        }

        @Override
        public void render(GuiGraphics graphics, Font font, int x, int y) {
        }
    }

    private record ImageBlock(ClientTooltipComponent component, int width, int height) implements MeasuredEntry {
        @Override
        public void render(GuiGraphics graphics, Font font, int x, int y) {
            component.renderImage(font, x, y, graphics);
        }
    }
}
