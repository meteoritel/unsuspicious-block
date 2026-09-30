package com.meteorite.unsuspiciousblock.client.ui.panel;

import com.meteorite.unsuspiciousblock.client.state.ScenarioSimulationClientState;
import com.meteorite.unsuspiciousblock.client.ui.kit.UiControl;
import com.meteorite.unsuspiciousblock.client.ui.kit.UiControlGroup;
import com.meteorite.unsuspiciousblock.client.ui.kit.UiFocusManager;
import com.meteorite.unsuspiciousblock.client.ui.kit.UiIcon;
import com.meteorite.unsuspiciousblock.client.ui.kit.UiRect;
import com.meteorite.unsuspiciousblock.client.ui.kit.UiScrollView;
import com.meteorite.unsuspiciousblock.client.ui.kit.UiTarget;
import com.meteorite.unsuspiciousblock.client.ui.kit.TextScroll;
import com.meteorite.unsuspiciousblock.client.ui.overlay.OverlayLayer;
import com.meteorite.unsuspiciousblock.client.ui.support.ArchaeologyJournalClientState;
import com.meteorite.unsuspiciousblock.client.ui.support.ScenarioLabel;
import com.meteorite.unsuspiciousblock.client.ui.support.ScenarioPresentation;
import com.meteorite.unsuspiciousblock.client.ui.support.ScenarioUi;
import com.meteorite.unsuspiciousblock.client.ui.support.UiTextPalette;
import com.meteorite.unsuspiciousblock.loottable.catalog.CatalogTableDto;
import com.meteorite.unsuspiciousblock.loottable.catalog.SimulationOptions;
import com.meteorite.unsuspiciousblock.loottable.simulation.ScenarioParams;
import com.meteorite.unsuspiciousblock.loottable.simulation.SimulationInput;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.IntConsumer;

/**
 * 场景跳转浮层：按服务端签发顺序列出场景，与页码共用同一选择。
 *
 * <p>只吃数据与一个回调，因此场景页的「场景」按钮与网格页头部的「切换场景」按钮共用同一实现——
 * 前者把选择映射到页码（会保存框内视图），后者只切换选择（网格数字随之更新）。</p>
 *
 * <p>行列表由 {@link UiScrollView} + {@link UiControlGroup} 承载：控件按场景下标稳定复用，
 * 翻页与滚轮只改滚动偏移、不重建控件；行内容仍在「目录 revision / 秒 / dirty」变化时才重配。</p>
 */
final class ScenarioSelectionOverlay implements OverlayLayer.Overlay {
    private static final int ROWS = 5;
    private static final int ROW_HEIGHT = 42;
    private static final int HEADER_HEIGHT = 24;
    private static final int PANEL_WIDTH = 268;
    /** 面板内边距：行区域左右各留 2 像素，行与行之间留 2 像素。 */
    private static final int PANEL_PADDING = 2;
    private static final int ARROW_WIDTH = 22;
    private static final int ARROW_HEIGHT = 15;
    /** 指针不在视口内时传给控件的占位坐标：任何行矩形都不会命中它。 */
    private static final int NO_HOVER = -1000;

    private final OverlayLayer layer;
    private final ResourceLocation table;
    private final SimulationOptions options;
    private final List<CatalogTableDto.ScenarioAssumptions> scenes;
    private final ScenarioParams params;
    private final IntConsumer onSelect;
    /** 键盘焦点：按视觉顺序登记「场景行 → ◀ → ▶」；鼠标点击不夺取焦点，轮廓只在键盘使用时出现。 */
    private final UiFocusManager focus = new UiFocusManager();
    private final UiScrollView scroll = new UiScrollView();
    private final UiControlGroup rows = new UiControlGroup();
    private final UiControlGroup bar = new UiControlGroup();
    /** 按场景下标持有行控件引用，用于逐帧标记可见窗口（不清空、不重建）。 */
    private final UiControl[] rowsByIndex;
    private final RowDisplay[] displays;
    private int visibleRows;
    private String language = "";
    @Nullable private Font measuredFont;
    private int screenWidth, screenHeight;
    @Nullable private UiControl previousArrow;
    @Nullable private UiControl nextArrow;
    private UiRect bounds = new UiRect(0, 0, 1, 1);
    private int current;
    private long revision = Long.MIN_VALUE;
    private long second = -1;
    private boolean dirty = true;
    /** 首次同步后把当前项居中一次；之后滚动位置保持到关闭为止。 */
    private boolean pendingCenter = true;

    ScenarioSelectionOverlay(OverlayLayer layer, ResourceLocation table,
                             SimulationOptions options, ScenarioParams params, int currentIndex,
                             IntConsumer onSelect) {
        this.layer = layer;
        this.table = table;
        this.options = options;
        this.scenes = options.scenes();
        this.params = params;
        this.current = currentIndex;
        this.onSelect = onSelect;
        this.rowsByIndex = new UiControl[this.scenes.size()];
        this.displays = new RowDisplay[this.scenes.size()];
        rows.setStyle(ScenarioUi.QUIET);
        bar.setStyle(ScenarioUi.QUIET);
        // 激活键仲裁：Enter 保持「选择并关闭」的既有契约，Space 用来激活键盘焦点目标。
        focus.setEnterActivates(false);
        focus.setSpaceActivates(true);
        // 焦点进视口：键盘把焦点移到某一行时，保证该行整行可见。
        focus.setListener((previousTarget, focusedTarget) -> {
            if (focusedTarget == null) return;
            for (int index = 0; index < rowsByIndex.length; index++) {
                if (rowsByIndex[index] != focusedTarget) continue;
                scroll.ensureVisible(new UiRect(0, index * ROW_HEIGHT, 0, ROW_HEIGHT - PANEL_PADDING));
                refreshArrows();
                return;
            }
        });
    }

    // 内容门控与迁移前一致：目录 revision、秒或显式 dirty 变化才重建控件内容。
    private void sync(Font font) {
        long nextRevision = ArchaeologyJournalClientState.getCatalogRevision();
        long nextSecond = System.currentTimeMillis() / 1000;
        String nextLanguage = Minecraft.getInstance().getLanguageManager().getSelected();
        if (!dirty && revision == nextRevision && second == nextSecond && language.equals(nextLanguage)
                && measuredFont == font && screenWidth == layer.width() && screenHeight == layer.height()) return;
        language = nextLanguage;
        measuredFont = font;
        screenWidth = layer.width(); screenHeight = layer.height();
        dirty = false;
        revision = nextRevision;
        second = nextSecond;
        if (scenes.isEmpty()) {
            layer.close();
            return;
        }
        visibleRows = Math.clamp((layer.height() - 8 - HEADER_HEIGHT - 22) / ROW_HEIGHT,
                1, Math.min(ROWS, scenes.size()));
        int panelWidth = Math.clamp(layer.width() - 4, 1, PANEL_WIDTH);
        int height = visibleRows * ROW_HEIGHT + HEADER_HEIGHT + (scenes.size() > visibleRows ? 22 : 4);
        bounds = new UiRect((layer.width() - panelWidth) / 2,
                (layer.height() - height) / 2, panelWidth, height);
        int viewportWidth = panelWidth - PANEL_PADDING * 2;
        scroll.setViewport(bounds.x() + PANEL_PADDING, bounds.y() + HEADER_HEIGHT,
                viewportWidth, visibleRows * ROW_HEIGHT);
        scroll.setStep(ROW_HEIGHT);
        scroll.setContentHeight(scenes.size() * ROW_HEIGHT);
        // 溢出时滚动条占住视口右侧 4 像素，行宽相应内缩。
        boolean overflowing = scroll.contentHeight() > scroll.viewport().height();
        scroll.setScrollbarVisible(overflowing);
        int rowWidth = viewportWidth - (overflowing ? UiScrollView.SCROLLBAR_WIDTH : 0);
        // 整表控件只建一次：稳定 key 是场景下标，翻页与滚轮都不会重建控件。
        rows.beginUpdate();
        for (int index = 0; index < scenes.size(); index++) {
            UiControl row = rows.obtain(index);
            row.setBounds(0, index * ROW_HEIGHT, rowWidth, ROW_HEIGHT - PANEL_PADDING);
            configureRow(font, row, index);
            rowsByIndex[index] = row;
        }
        rows.endUpdate();
        bar.beginUpdate();
        previousArrow = configureArrow(font, bar.obtain("previous"), -visibleRows, bounds.x() + PANEL_PADDING);
        nextArrow = configureArrow(font, bar.obtain("next"), visibleRows,
                bounds.right() - ARROW_WIDTH - PANEL_PADDING);
        previousArrow.setVisible(overflowing);
        nextArrow.setVisible(overflowing);
        UiControl close = bar.obtain("close");
        close.setBounds(bounds.right() - 21, bounds.y() + 4, 17, 16);
        close.configure(font, Component.empty(), UiTextPalette.Parchment.BODY, ScenarioUi.icon(ScenarioUi.Icon.CLOSE),
                List.of(ScenarioSimulationClientState.text("params.cancel")), layer::close);
        close.setAccessibleName(ScenarioSimulationClientState.text("params.cancel"));
        bar.endUpdate();
        // 焦点序列按视觉顺序登记：场景行（即绘制层序）→ ◀ → ▶；禁用与不可见的行由 canFocus() 自动出列。
        focus.beginUpdate();
        focus.add(close);
        for (UiControl row : rowsByIndex) focus.add(row);
        if (previousArrow != null) focus.add(previousArrow);
        if (nextArrow != null) focus.add(nextArrow);
        focus.endUpdate();
        if (pendingCenter) {
            pendingCenter = false;
            centerOn(current);
        }
        refreshArrows();
    }

    // 行控件承载背景、命中和提示；主名称、状态与条件摘要在两个独立文本区绘制。
    private void configureRow(Font font, UiControl row, int index) {
        String sceneKey = scenes.get(index).scenarioKey();
        ScenarioPresentation presentation = ScenarioPresentation.resolve(table, sceneKey, params);
        Component name = ScenarioLabel.shortLabel(sceneKey);
        if (index == current) name = name.copy().withStyle(ChatFormatting.BOLD);
        List<Component> tooltip = new ArrayList<>(ScenarioLabel.definition(options, sceneKey));
        tooltip.addFirst(ScenarioLabel.shortLabel(sceneKey));
        tooltip.add(ScenarioSimulationClientState.text(presentation.status()));
        if (presentation.status().equals("failed")) {
            String input = new SimulationInput(sceneKey, Map.of(), params).key();
            tooltip.add(ScenarioSimulationClientState.text(
                    "failure." + ScenarioSimulationClientState.failure(table, input)));
        }
        row.configure(font, Component.empty(), UiTextPalette.Parchment.BODY, null, tooltip, () -> select(index));
        List<FormattedCharSequence> wrapped = font.split(ScenarioLabel.detailLabel(options, sceneKey),
                Math.max(1, row.bounds().width() - 18));
        List<FormattedCharSequence> detail = new ArrayList<>(wrapped.subList(0, Math.min(2, wrapped.size())));
        if (wrapped.size() > 2) detail.set(1, FormattedCharSequence.composite(detail.get(1), Component.literal("…").getVisualOrderText()));
        displays[index] = new RowDisplay(name, List.copyOf(detail),
                ScenarioSimulationClientState.text("results.status." + presentation.status()),
                presentation.badge());
        // 语义选中态：当前场景行常亮选中底色（原有加粗保留），键盘上下键改选中时同步跟随。
        row.setSelected(index == current);
    }

    // 底部翻页箭头：尺寸与落位与迁移前相同，动作改为滚动一页。
    private UiControl configureArrow(Font font, UiControl arrow, int deltaRows, int x) {
        arrow.setBounds(x, bounds.bottom() - 17, ARROW_WIDTH, ARROW_HEIGHT);
        Component name = ScenarioSimulationClientState.text(deltaRows < 0 ? "scene.previous" : "more");
        arrow.configure(font, Component.literal(deltaRows < 0 ? "◀" : "▶"), UiTextPalette.Parchment.TITLE,
                null, List.of(name),
                () -> {
                    scroll.setOffset(scroll.offset() + deltaRows * ROW_HEIGHT);
                    refreshArrows();
                });
        // 符号按钮本身不具名：显式给一个可读名称，供焦点调试与旁白使用。
        arrow.setAccessibleName(name);
        return arrow;
    }

    // 到边界后对应箭头禁用：旧实现点上去是空操作，禁用态把这件事显示出来。
    private void refreshArrows() {
        if (previousArrow != null) previousArrow.setEnabled(scroll.offset() > 0);
        if (nextArrow != null) nextArrow.setEnabled(scroll.offset() < scroll.maxOffset());
    }

    // 让指定行尽量居中，并保证整行可见；内容不足一屏时钳制到 0。
    private void centerOn(int index) {
        scroll.setOffset((index - visibleRows / 2) * ROW_HEIGHT);
        scroll.ensureVisible(new UiRect(0, index * ROW_HEIGHT, 0, ROW_HEIGHT - PANEL_PADDING));
        refreshArrows();
    }

    // 视口外的行标记不可见：省掉背景与文本的提交，也不进入焦点序列。
    private void updateVisibleRows() {
        int viewportTop = scroll.viewport().y();
        int viewportBottom = scroll.viewport().bottom();
        for (int index = 0; index < rowsByIndex.length; index++) {
            UiControl row = rowsByIndex[index];
            if (row == null) continue;
            int rowTop = scroll.toScreenY(index * ROW_HEIGHT);
            row.setVisible(rowTop + ROW_HEIGHT > viewportTop && rowTop < viewportBottom);
        }
    }

    private void select(int index) {
        if (index < 0 || index >= scenes.size()) return;
        current = index;
        onSelect.accept(index);
        layer.close();
    }

    @Override public void render(GuiGraphics graphics, Font font, int x, int y, float partialTick) {
        sync(font);
        graphics.fill(0, 0, layer.width(), layer.height(), 0x88000000);
        ScenarioUi.PANEL.render(graphics, bounds);
        graphics.drawString(font, ScenarioSimulationClientState.text("scene.toggle"),
                bounds.x() + 8, bounds.y() + 6, UiTextPalette.Parchment.TITLE, false);
        boolean inViewport = scroll.contains(x, y) && !scroll.hitScrollbar(x, y);
        updateVisibleRows();
        scroll.push(graphics);
        try {
            rows.render(graphics, font,
                    inViewport ? (int) scroll.toContentX(x) : NO_HOVER,
                    inViewport ? (int) scroll.toContentY(y) : NO_HOVER);
            int first = scroll.offset() / ROW_HEIGHT;
            int end = Math.min(scenes.size(),
                    (scroll.offset() + scroll.viewport().height() + ROW_HEIGHT - 1) / ROW_HEIGHT);
            int width = scroll.viewport().width()
                    - (scroll.isScrollbarVisible() ? UiScrollView.SCROLLBAR_WIDTH : 0);
            for (int index = first; index < end; index++) {
                RowDisplay display = displays[index];
                int top = index * ROW_HEIGHT;
                if (index == current) graphics.fill(0, top + 2, 2, top + ROW_HEIGHT - 4, ScenarioUi.BRANCH);
                graphics.drawString(font, display.name(), 5, top + 3, UiTextPalette.Parchment.TITLE, false);
                display.badge().render(graphics, width - 85, top + 2);
                TextScroll.draw(graphics, font, display.status().getVisualOrderText(), font.width(display.status()),
                        width - 70, top + 4, 65, UiTextPalette.Parchment.LABEL, false, 0);
                for (int line = 0; line < display.detail().size(); line++) {
                    graphics.drawString(font, display.detail().get(line), 5, top + 17 + line * 11,
                            UiTextPalette.Parchment.BODY, false);
                }
            }
        } finally {
            scroll.pop(graphics);
        }
        scroll.renderScrollbar(graphics, rows.style());
        bar.render(graphics, font, x, y);
        if (scroll.maxOffset() > 0) {
            int first = scroll.offset() / ROW_HEIGHT + 1;
            int last = Math.min(scenes.size(), (scroll.offset() + scroll.viewport().height() + ROW_HEIGHT - 1) / ROW_HEIGHT);
            Component position = ScenarioSimulationClientState.text("scene.position", first, last, scenes.size());
            graphics.drawString(font, position, bounds.x() + (bounds.width() - font.width(position)) / 2,
                    bounds.bottom() - 13, UiTextPalette.Parchment.LABEL, false);
        }
        // 同一位置唯一 tooltip：视口内取行控件的最上层目标，否则交给底部箭头组。
        if (inViewport) {
            UiTarget hovered = rows.targetAt(scroll.toContentX(x), scroll.toContentY(y));
            if (hovered != null && !hovered.tooltip().isEmpty()) {
                graphics.renderComponentTooltip(font, hovered.tooltip(), x, y);
            }
        } else {
            bar.renderTooltip(graphics, font, x, y);
        }
    }

    private record RowDisplay(Component name, List<FormattedCharSequence> detail, Component status, UiIcon badge) {}

    @Override public boolean mouseClicked(double x, double y, int button) {
        sync(Minecraft.getInstance().font);
        if (!bounds.contains(x, y)) {
            layer.close();
            return true;
        }
        if (button != 0) return true;
        // 鼠标点击不夺取焦点：先把键盘焦点收掉，避免轮廓「粘」在被点过的控件上。
        focus.clearFocus();
        // 滚动条先于行命中：滑块压在行右侧，同一次点击不能穿透到行。
        if (scroll.mousePressed(x, y, button)) {
            refreshArrows();
            return true;
        }
        if (scroll.contains(x, y)) {
            rows.mousePressed(scroll.toContentX(x), scroll.toContentY(y), button);
            return true;
        }
        bar.mousePressed(x, y, button);
        return true;
    }

    @Override public boolean mouseDragged(double x, double y, int button, double dragX, double dragY) {
        // 拖动滑块同样改变偏移：立即刷新箭头禁用态，否则要等下一秒的 sync 才纠正。
        if (scroll.mouseDragged(y)) refreshArrows();
        return true;
    }

    @Override public boolean mouseReleased(double x, double y, int button) {
        scroll.mouseReleased();
        rows.mouseReleased(button);
        bar.mouseReleased(button);
        return true;
    }

    @Override public boolean mouseScrolled(double x, double y, double amount) {
        sync(Minecraft.getInstance().font);
        // 指针不在视口内不消费：滚动只属于这个视口，浮层其余区域交回宿主契约。
        if (!scroll.contains(x, y)) return false;
        if (scroll.scrollBy(amount)) refreshArrows();
        return true;
    }

    @Override public boolean keyPressed(int key, int scan, int modifiers) {
        sync(Minecraft.getInstance().font);
        if (scenes.isEmpty()) {
            layer.close();
            return true;
        }
        if (key == GLFW.GLFW_KEY_ESCAPE || key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
            layer.close();
            return true;
        }
        if (key == GLFW.GLFW_KEY_UP || key == GLFW.GLFW_KEY_DOWN) {
            int index = Math.clamp(current + (key == GLFW.GLFW_KEY_DOWN ? 1 : -1), 0, scenes.size() - 1);
            current = index;
            onSelect.accept(index);
            centerOn(index);
            dirty = true;
            return true;
        }
        // Tab / Shift+Tab 移焦点、Space 激活焦点行；Enter 与上下键已在上方按既有契约处理。
        return focus.keyPressed(key, scan, modifiers);
    }
}
