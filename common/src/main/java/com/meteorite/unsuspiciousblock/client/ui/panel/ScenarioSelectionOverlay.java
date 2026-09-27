package com.meteorite.unsuspiciousblock.client.ui.panel;

import com.meteorite.unsuspiciousblock.client.state.ScenarioSimulationClientState;
import com.meteorite.unsuspiciousblock.client.ui.kit.UiControl;
import com.meteorite.unsuspiciousblock.client.ui.kit.UiControlGroup;
import com.meteorite.unsuspiciousblock.client.ui.kit.UiFocusManager;
import com.meteorite.unsuspiciousblock.client.ui.kit.UiRect;
import com.meteorite.unsuspiciousblock.client.ui.kit.UiScrollView;
import com.meteorite.unsuspiciousblock.client.ui.kit.UiTarget;
import com.meteorite.unsuspiciousblock.client.ui.overlay.OverlayLayer;
import com.meteorite.unsuspiciousblock.client.ui.support.ArchaeologyJournalClientState;
import com.meteorite.unsuspiciousblock.client.ui.support.ScenarioLabel;
import com.meteorite.unsuspiciousblock.client.ui.support.ScenarioPresentation;
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
    private static final int ROWS = 7;
    private static final int ROW_HEIGHT = 20;
    private static final int PANEL_WIDTH = 152;
    /** 面板内边距：行区域左右各留 2 像素，行与行之间留 2 像素。 */
    private static final int PANEL_PADDING = 2;
    private static final int ARROW_WIDTH = 22;
    private static final int ARROW_HEIGHT = 15;
    /** 底部箭头一次位移的行数。 */
    private static final int PAGE_ROWS = ROWS;
    /** 指针不在视口内时传给控件的占位坐标：任何行矩形都不会命中它。 */
    private static final int NO_HOVER = -1000;

    private final OverlayLayer layer;
    private final int anchorX;
    private final int anchorY;
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
    @Nullable private UiControl previousArrow;
    @Nullable private UiControl nextArrow;
    private UiRect bounds = new UiRect(0, 0, 1, 1);
    private int current;
    private long revision = Long.MIN_VALUE;
    private long second = -1;
    private boolean dirty = true;
    /** 首次同步后把当前项居中一次；之后滚动位置保持到关闭为止。 */
    private boolean pendingCenter = true;

    ScenarioSelectionOverlay(OverlayLayer layer, int anchorX, int anchorY, ResourceLocation table,
                             SimulationOptions options, ScenarioParams params, int currentIndex,
                             IntConsumer onSelect) {
        this.layer = layer;
        this.anchorX = anchorX;
        this.anchorY = anchorY;
        this.table = table;
        this.options = options;
        this.scenes = options.scenes();
        this.params = params;
        this.current = currentIndex;
        this.onSelect = onSelect;
        this.rowsByIndex = new UiControl[this.scenes.size()];
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
        if (!dirty && revision == nextRevision && second == nextSecond) return;
        dirty = false;
        revision = nextRevision;
        second = nextSecond;
        if (scenes.isEmpty()) {
            layer.close();
            return;
        }
        int visibleRows = Math.min(ROWS, scenes.size());
        int height = visibleRows * ROW_HEIGHT + ROW_HEIGHT;
        bounds = new UiRect(Math.clamp(anchorX, 2, Math.max(2, layer.width() - PANEL_WIDTH - 2)),
                Math.clamp(anchorY, 2, Math.max(2, layer.height() - height - 2)), PANEL_WIDTH, height);
        int viewportWidth = PANEL_WIDTH - PANEL_PADDING * 2;
        scroll.setViewport(bounds.x() + PANEL_PADDING, bounds.y() + PANEL_PADDING,
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
        previousArrow = configureArrow(font, bar.obtain("previous"), -PAGE_ROWS, bounds.x() + PANEL_PADDING);
        nextArrow = configureArrow(font, bar.obtain("next"), PAGE_ROWS,
                bounds.right() - ARROW_WIDTH - PANEL_PADDING);
        bar.endUpdate();
        // 焦点序列按视觉顺序登记：场景行（即绘制层序）→ ◀ → ▶；禁用与不可见的行由 canFocus() 自动出列。
        focus.beginUpdate();
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

    // 行内容与迁移前逐项一致：当前项加粗，tooltip = 定义 + 状态 + 失败原因。
    private void configureRow(Font font, UiControl row, int index) {
        String sceneKey = scenes.get(index).scenarioKey();
        ScenarioPresentation presentation = ScenarioPresentation.resolve(table, sceneKey, params);
        Component label = ScenarioLabel.label(options, sceneKey);
        if (index == current) label = label.copy().withStyle(ChatFormatting.BOLD);
        List<Component> tooltip = new ArrayList<>(ScenarioLabel.definition(options, sceneKey));
        tooltip.add(ScenarioSimulationClientState.text(presentation.status()));
        if (presentation.status().equals("failed")) {
            String input = new SimulationInput(sceneKey, Map.of(), params).key();
            tooltip.add(ScenarioSimulationClientState.text(
                    "failure." + ScenarioSimulationClientState.failure(table, input)));
        }
        row.configure(font, label, UiTextPalette.Parchment.BODY, presentation.badge(), tooltip,
                () -> select(index));
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
        scroll.setOffset((index - ROWS / 2) * ROW_HEIGHT);
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
        graphics.fill(bounds.x() - 1, bounds.y() - 1, bounds.right() + 1, bounds.bottom() + 1, 0xFF896C48);
        graphics.fill(bounds.x(), bounds.y(), bounds.right(), bounds.bottom(), 0xFFF2E5C6);
        boolean inViewport = scroll.contains(x, y);
        updateVisibleRows();
        scroll.push(graphics);
        try {
            rows.render(graphics, font,
                    inViewport ? (int) scroll.toContentX(x) : NO_HOVER,
                    inViewport ? (int) scroll.toContentY(y) : NO_HOVER);
        } finally {
            scroll.pop(graphics);
        }
        scroll.renderScrollbar(graphics, rows.style());
        bar.render(graphics, font, x, y);
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
