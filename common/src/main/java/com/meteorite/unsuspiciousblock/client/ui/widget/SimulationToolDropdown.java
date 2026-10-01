package com.meteorite.unsuspiciousblock.client.ui.widget;

import com.meteorite.unsuspiciousblock.client.ui.kit.*;
import com.meteorite.unsuspiciousblock.client.ui.support.ScenarioUi;
import com.meteorite.unsuspiciousblock.client.ui.support.UiTextPalette;
import com.meteorite.unsuspiciousblock.loottable.simulation.ToolOption;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.function.IntConsumer;

/*** 工具按钮下方的小型下拉层；打开时由宿主优先分发输入，不替换附魔列表。 */
public final class SimulationToolDropdown {
    public static final int WIDTH = 32;
    private static final int ROW_HEIGHT = 18;
    private static final int MAX_ROWS = 6;
    private final List<ToolOption> tools;
    private final IntConsumer select;
    private final Runnable close;
    private final UiControlGroup controls = new UiControlGroup();
    private UiRect bounds = new UiRect(0, 0, WIDTH, 0);
    private int highlighted;
    private int firstRow;
    private boolean dirty = true;
    private Font measuredFont;

    public SimulationToolDropdown(List<ToolOption> tools, int selected, IntConsumer select, Runnable close) {
        this.tools = tools;
        this.highlighted = selected;
        this.select = select;
        this.close = close;
        firstRow = Math.clamp(selected, 0, Math.max(0, tools.size() - MAX_ROWS));
    }

    // 位置由参数窗口锚定；滚动最多显示六行。
    public void setPosition(int x, int y) {
        UiRect next = new UiRect(x, y, WIDTH, 6 + Math.min(MAX_ROWS, tools.size()) * ROW_HEIGHT);
        if (!next.equals(bounds)) { bounds = next; dirty = true; }
    }

    private void layout(Font font) {
        if (!dirty && font == measuredFont) return;
        measuredFont = font;
        controls.beginUpdate();
        for (int index = firstRow; index < Math.min(tools.size(), firstRow + MAX_ROWS); index++) {
            ToolOption tool = tools.get(index);
            ItemStack stack = new ItemStack(BuiltInRegistries.ITEM.get(tool.id()));
            UiControl row = controls.obtain(tool.id().toString());
            row.setBounds(bounds.x() + 3, bounds.y() + 3 + (index - firstRow) * ROW_HEIGHT,
                    bounds.width() - 6, ROW_HEIGHT);
            row.setStyle(ScenarioUi.QUIET);
            row.setSelected(index == highlighted);
            int selected = index;
            row.configure(font, stack.isEmpty() ? Component.literal("—") : Component.empty(), UiTextPalette.Parchment.BODY,
                    stack.isEmpty() ? null : new UiIcon.Item(stack),
                    tool.predicateText() == null ? List.of(tool.displayName())
                            : List.of(tool.displayName(), tool.predicateText()), () -> select.accept(selected));
            row.setAccessibleName(tool.displayName());
        }
        controls.endUpdate();
        dirty = false;
    }

    public void render(GuiGraphics graphics, Font font, int mouseX, int mouseY) {
        layout(font);
        graphics.flush();
        graphics.pose().pushPose();
        try {
            graphics.pose().translate(0, 0, 100);
            ScenarioUi.renderPage(graphics, bounds);
            controls.render(graphics, font, mouseX, mouseY);
            if (tools.size() > MAX_ROWS) {
                int trackHeight = bounds.height() - 6;
                int thumbHeight = Math.max(8, trackHeight * MAX_ROWS / tools.size());
                int thumbY = bounds.y() + 3 + (trackHeight - thumbHeight) * firstRow / (tools.size() - MAX_ROWS);
                graphics.fill(bounds.right() - 3, thumbY, bounds.right() - 2,
                        thumbY + thumbHeight, UiTextPalette.Parchment.LABEL);
            }
            controls.renderTooltip(graphics, font, mouseX, mouseY);
            graphics.flush();
        } finally { graphics.pose().popPose(); }
    }

    public void mouseClicked(Font font, double mouseX, double mouseY, int button) {
        layout(font);
        if (!bounds.contains(mouseX, mouseY)) close.run();
        else if (button == 0) controls.mousePressed(mouseX, mouseY, button);
    }

    public void mouseScrolled(double amount) {
        firstRow = Math.clamp(firstRow - (int) Math.signum(amount), 0, Math.max(0, tools.size() - MAX_ROWS));
        dirty = true;
    }

    public void keyPressed(int key, int modifiers) {
        if (key == GLFW.GLFW_KEY_ESCAPE) { close.run(); return; }
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER || key == GLFW.GLFW_KEY_SPACE) {
            select.accept(highlighted); return;
        }
        int delta = switch (key) {
            case GLFW.GLFW_KEY_UP -> -1;
            case GLFW.GLFW_KEY_DOWN -> 1;
            case GLFW.GLFW_KEY_PAGE_UP -> -MAX_ROWS;
            case GLFW.GLFW_KEY_PAGE_DOWN -> MAX_ROWS;
            case GLFW.GLFW_KEY_TAB -> (modifiers & GLFW.GLFW_MOD_SHIFT) != 0 ? -1 : 1;
            default -> 0;
        };
        if (delta == 0) return;
        highlighted = Math.clamp(highlighted + delta, 0, tools.size() - 1);
        if (highlighted < firstRow) firstRow = highlighted;
        if (highlighted >= firstRow + MAX_ROWS) firstRow = highlighted - MAX_ROWS + 1;
        dirty = true;
    }
}
