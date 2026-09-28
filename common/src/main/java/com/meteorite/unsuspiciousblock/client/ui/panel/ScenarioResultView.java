package com.meteorite.unsuspiciousblock.client.ui.panel;

import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.client.state.ScenarioSimulationClientState;
import com.meteorite.unsuspiciousblock.client.ui.kit.TextScroll;
import com.meteorite.unsuspiciousblock.client.ui.kit.UiControl;
import com.meteorite.unsuspiciousblock.client.ui.kit.UiControlGroup;
import com.meteorite.unsuspiciousblock.client.ui.kit.UiLinearLayout;
import com.meteorite.unsuspiciousblock.client.ui.kit.UiNineSlice;
import com.meteorite.unsuspiciousblock.client.ui.kit.UiRect;
import com.meteorite.unsuspiciousblock.client.ui.kit.UiScrollView;
import com.meteorite.unsuspiciousblock.client.ui.kit.UiTarget;
import com.meteorite.unsuspiciousblock.client.ui.support.UiTextPalette;
import com.meteorite.unsuspiciousblock.loottable.simulation.ProbabilityFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/** 场景结果的自然字号列表：图标、名称与固定概率列各占自己的位置，滚轮只负责阅读。 */
final class ScenarioResultView {
    private static final int ROW_HEIGHT = 20;
    private static final UiNineSlice BORDER = new UiNineSlice(ResourceLocation.fromNamespaceAndPath(
            Constants.MOD_ID, "textures/gui/scenario_frame.png"), 24, 8);
    private final UiScrollView scroll = new UiScrollView();
    private final UiControlGroup rows = new UiControlGroup();
    private final UiLinearLayout columns = new UiLinearLayout(UiLinearLayout.Axis.HORIZONTAL, 0, 0);
    private final Font font;
    private UiRect bounds = new UiRect(0, 0, 1, 1);
    private List<DisplayRow> content = List.of();
    private String status = "uncomputed";
    @Nullable private Component failure;
    private int visibleFirst = -1;
    private int visibleEnd = -1;
    private int rowWidth = -1;
    private int probabilityWidth = 36;
    private int[] hoverTicks = new int[0];
    private int[] probabilityTicks = new int[0];
    // 列矩形缓存：与 syncRows 的「可见区间 + 行宽」同一次重建，逐帧不再 new UiRect×3/行（P-12）
    private UiRect[] iconRects = new UiRect[0];
    private UiRect[] nameRects = new UiRect[0];
    private UiRect[] probabilityRects = new UiRect[0];
    // 标题文案缓存键与值：键 = 状态 + 条目数 + 语言（P-12）
    private String headingKey = "";
    private Component heading = Component.empty();
    // 行文本宽度缓存（P-10）：与可见区间/行宽/语言/字体同批重建，render 不再逐行 font.width
    private int[] nameWidths = new int[0];
    private int[] probabilityWidths = new int[0];
    private String rowsGeneration = "";

    ScenarioResultView(Font font) {
        this.font = font;
        scroll.setStep(ROW_HEIGHT);
        updateColumns();
    }

    void setBounds(int x, int y, int width, int height) {
        UiRect next = new UiRect(x, y, width, height);
        if (bounds.equals(next)) return;
        bounds = next;
        scroll.setViewport(x + 7, y + 22, Math.max(1, width - 14), Math.max(1, height - 29));
        rebuildViewport();
    }

    void setContent(List<ScenarioPageBuilder.Outcome> outcomes, String status, @Nullable Component failure) {
        List<DisplayRow> next = new ArrayList<>(outcomes.size());
        for (ScenarioPageBuilder.Outcome outcome : outcomes) {
            next.add(new DisplayRow(outcome.item().displayName().copy(),
                    ProbabilityFormat.formatComponent(outcome.probability()), outcome.stack()));
        }
        content = List.copyOf(next);
        probabilityWidth = Math.clamp(
                content.stream().mapToInt(row -> font.width(row.probability())).max().orElse(36) + 2,
                36, 70);
        updateColumns();
        this.status = status;
        this.failure = failure;
        hoverTicks = new int[content.size()];
        probabilityTicks = new int[content.size()];
        iconRects = new UiRect[content.size()];
        nameRects = new UiRect[content.size()];
        probabilityRects = new UiRect[content.size()];
        nameWidths = new int[content.size()];
        probabilityWidths = new int[content.size()];
        scroll.setOffset(0);
        rebuildViewport();
    }

    int offset() { return scroll.offset(); }
    void setOffset(int offset) { scroll.setOffset(offset); invalidateVisibleRows(); }

    private void rebuildViewport() {
        scroll.setContentHeight("cached".equals(status) ? content.size() * ROW_HEIGHT : 0);
        scroll.setScrollbarVisible(scroll.maxOffset() > 0);
        invalidateVisibleRows();
    }

    private void updateColumns() {
        columns.setChildren(List.of(
                UiLinearLayout.Child.fixed(16),
                UiLinearLayout.Child.remain().withLeading(2),
                UiLinearLayout.Child.fixed(probabilityWidth).withLeading(2)));
    }

    private void invalidateVisibleRows() {
        visibleFirst = -1;
        visibleEnd = -1;
        rowWidth = -1;
    }

    private void syncRows() {
        int first = "cached".equals(status) ? scroll.offset() / ROW_HEIGHT : 0;
        int end = "cached".equals(status)
                ? Math.min(content.size(), (scroll.offset() + scroll.viewport().height() + ROW_HEIGHT - 1) / ROW_HEIGHT)
                : 0;
        int width = scroll.viewport().width() - (scroll.isScrollbarVisible() ? UiScrollView.SCROLLBAR_WIDTH : 0);
        // 宽度的失效点与行缓存同一代：语言代码 + Font 实例（字体宽度只在同一实例内可比）
        String generation = Minecraft.getInstance().getLanguageManager().getSelected()
                + "#" + System.identityHashCode(this.font);
        if (first == visibleFirst && end == visibleEnd && width == rowWidth
                && generation.equals(this.rowsGeneration)) {
            return;
        }
        visibleFirst = first;
        visibleEnd = end;
        rowWidth = width;
        rowsGeneration = generation;
        rows.beginUpdate();
        for (int i = first; i < end; i++) {
            DisplayRow row = content.get(i);
            UiControl control = rows.obtain(i);
            control.setBounds(0, i * ROW_HEIGHT, width, ROW_HEIGHT - 1);
            control.configure(font, Component.empty(), UiTextPalette.Parchment.BODY, null,
                    List.of(row.name(), row.probability()), null);
            // 列矩形与可见区间同步重算（行宽变化时失效），绘制循环只读缓存
            columns.setBounds(2, i * ROW_HEIGHT + 1, Math.max(0, width - 4), 17);
            iconRects[i] = columns.bounds(0);
            nameRects[i] = columns.bounds(1);
            probabilityRects[i] = columns.bounds(2);
            // 文本宽度与列矩形同批测一次（P-10）：同代内 render 只读数组
            nameWidths[i] = this.font.width(row.name());
            probabilityWidths[i] = this.font.width(row.probability());
        }
        rows.endUpdate();
    }

    void render(GuiGraphics graphics, int mouseX, int mouseY) {
        BORDER.render(graphics, bounds);
        graphics.drawString(font, heading(), bounds.x() + 8, bounds.y() + 8, UiTextPalette.Parchment.TITLE, false);
        if (!"cached".equals(status) || content.isEmpty()) {
            renderEmpty(graphics);
            return;
        }
        syncRows();
        boolean hovered = scroll.contains(mouseX, mouseY) && !scroll.hitScrollbar(mouseX, mouseY);
        int contentX = hovered ? (int) scroll.toContentX(mouseX) : -1000;
        int contentY = hovered ? (int) scroll.toContentY(mouseY) : -1000;
        scroll.push(graphics);
        try {
            rows.render(graphics, font, contentX, contentY);
            for (int i = visibleFirst; i < visibleEnd; i++) {
                DisplayRow row = content.get(i);
                UiRect icon = iconRects[i];
                UiRect name = nameRects[i];
                UiRect probability = probabilityRects[i];
                graphics.renderItem(row.stack(), icon.x(), icon.y());
                boolean nameHovered = hovered && name.contains(contentX, contentY);
                hoverTicks[i] = nameHovered ? hoverTicks[i] + 1 : 0;
                TextScroll.draw(graphics, font, row.name().getVisualOrderText(), nameWidths[i],
                        name.x(), name.y() + 4, name.width(), UiTextPalette.Parchment.NAME,
                        nameHovered, hoverTicks[i]);
                int textWidth = probabilityWidths[i];
                boolean probabilityHovered = hovered && probability.contains(contentX, contentY);
                probabilityTicks[i] = probabilityHovered ? probabilityTicks[i] + 1 : 0;
                TextScroll.draw(graphics, font, row.probability().getVisualOrderText(), textWidth,
                        probability.x() + Math.max(0, probability.width() - textWidth), probability.y() + 4,
                        probability.width(), UiTextPalette.Parchment.BODY,
                        probabilityHovered, probabilityTicks[i]);
            }
        } finally {
            scroll.pop(graphics);
        }
        scroll.renderScrollbar(graphics, rows.style());
    }

    // 标题文案按「状态 + 条目数 + 语言」缓存：原实现每帧重建 Component（P-12）
    private Component heading() {
        String key = this.status + "#" + this.content.size() + "#"
                + Minecraft.getInstance().getLanguageManager().getSelected();
        if (!key.equals(this.headingKey)) {
            this.headingKey = key;
            this.heading = "cached".equals(this.status) && !this.content.isEmpty()
                    ? ScenarioSimulationClientState.text("outcome_header", this.content.size())
                    : ScenarioSimulationClientState.text("results.heading");
        }
        return this.heading;
    }

    private void renderEmpty(GuiGraphics graphics) {
        Component message = switch (status) {
            case "cached" -> ScenarioSimulationClientState.text("outcome_empty");
            case "pending" -> ScenarioSimulationClientState.text("results.pending");
            case "failed" -> failure == null ? ScenarioSimulationClientState.text("failed") : failure;
            default -> ScenarioSimulationClientState.text("results.uncomputed");
        };
        int y = scroll.viewport().y() + 5;
        for (var line : font.split(message, Math.max(1, scroll.viewport().width() - 4))) {
            if (y + font.lineHeight > scroll.viewport().bottom()) break;
            graphics.drawString(font, line, scroll.viewport().x() + 2, y, UiTextPalette.Parchment.BODY, false);
            y += font.lineHeight + 3;
        }
    }

    void renderTooltip(GuiGraphics graphics, int x, int y) {
        if (!hoveredItem(x, y).isEmpty()) return;
        if (!scroll.contains(x, y) || scroll.hitScrollbar(x, y)) return;
        syncRows();
        UiTarget target = rows.targetAt(scroll.toContentX(x), scroll.toContentY(y));
        if (target != null && !target.tooltip().isEmpty()) {
            graphics.renderComponentTooltip(font, target.tooltip(), x, y);
        }
    }

    ItemStack hoveredItem(double x, double y) {
        if (!"cached".equals(status) || !scroll.contains(x, y) || scroll.hitScrollbar(x, y)) {
            return ItemStack.EMPTY;
        }
        int row = (int) scroll.toContentY(y) / ROW_HEIGHT;
        if (row < 0 || row >= content.size()) return ItemStack.EMPTY;
        int contentX = (int) scroll.toContentX(x);
        int rowY = (int) scroll.toContentY(y) - row * ROW_HEIGHT;
        return contentX >= 2 && contentX < 18 && rowY >= 1 && rowY < 17
                ? content.get(row).stack() : ItemStack.EMPTY;
    }

    boolean mouseClicked(double x, double y, int button) {
        if (!scroll.contains(x, y)) return false;
        if (scroll.mousePressed(x, y, button)) return true;
        syncRows();
        return rows.mousePressed(scroll.toContentX(x), scroll.toContentY(y), button);
    }

    boolean mouseDragged(double y) {
        if (!scroll.isDragging()) return false;
        scroll.mouseDragged(y);
        invalidateVisibleRows();
        return true;
    }

    boolean mouseReleased(int button) {
        boolean handled = scroll.isDragging() || rows.isPressed();
        scroll.mouseReleased();
        rows.mouseReleased(button);
        return handled;
    }

    boolean mouseScrolled(double x, double y, double amount) {
        if (!scroll.contains(x, y)) return false;
        if (scroll.scrollBy(amount)) invalidateVisibleRows();
        return true;
    }

    private record DisplayRow(Component name, Component probability, ItemStack stack) {}
}
