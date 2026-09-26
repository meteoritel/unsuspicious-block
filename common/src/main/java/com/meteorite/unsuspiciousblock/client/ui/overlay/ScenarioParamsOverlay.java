package com.meteorite.unsuspiciousblock.client.ui.overlay;

import com.meteorite.unsuspiciousblock.client.state.ScenarioSimulationClientState;
import com.meteorite.unsuspiciousblock.client.ui.kit.UiAction;
import com.meteorite.unsuspiciousblock.client.ui.kit.UiControl;
import com.meteorite.unsuspiciousblock.client.ui.kit.UiControlGroup;
import com.meteorite.unsuspiciousblock.client.ui.kit.UiFocusManager;
import com.meteorite.unsuspiciousblock.client.ui.kit.UiFocusTarget;
import com.meteorite.unsuspiciousblock.client.ui.kit.UiLinearLayout;
import com.meteorite.unsuspiciousblock.client.ui.kit.UiRect;
import com.meteorite.unsuspiciousblock.client.ui.support.UiTextPalette;
import com.meteorite.unsuspiciousblock.loottable.catalog.SimulationOptions;
import com.meteorite.unsuspiciousblock.loottable.simulation.ScenarioParams;
import com.meteorite.unsuspiciousblock.loottable.simulation.ToolOption;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 参数草稿模态：只有确认才提交；除幸运值原生输入框外，控件统一由 {@link UiControlGroup} 按稳定 key 承载，
 * 落位由三套 {@link UiLinearLayout}（行槽、行内横排、底部按钮行）产出，不再手算控件坐标。
 */
public final class ScenarioParamsOverlay implements OverlayLayer.Overlay {
    private static final int PANEL_WIDTH = 276;
    /** 内容宽：panelX+8 … panelX+268。 */
    private static final int CONTENT_WIDTH = PANEL_WIDTH - 16;
    private final OverlayLayer layer;
    private final ResourceLocation table;
    private final String scene;
    private final SimulationOptions options;
    private final List<ToolOption> tools;
    private final List<Integer> samples;
    private final List<ResourceLocation> enchantments;
    private final Map<ResourceLocation, Integer> levels = new LinkedHashMap<>();
    /** 控件组：稳定 key 复用，禁止每帧新建控件。 */
    private final UiControlGroup controls = new UiControlGroup();
    /** 键盘焦点：按视觉顺序登记「工具行 ◀/▶ → EditBox → 抽样格 → 附魔 −/+ → 取消 → 确认」。 */
    private final UiFocusManager focus = new UiFocusManager();
    /** 原生输入框适配器：只接驳焦点，不接管文字编辑与输入法。 */
    private final UiFocusTarget luckTarget = new LuckFocusTarget();
    /** 行槽布局：每行固定 20 高，槽内控件顶对齐高 16。 */
    private final UiLinearLayout rowsLayout = new UiLinearLayout(UiLinearLayout.Axis.VERTICAL, 0, 0);
    /** 行内横排布局：逐行重配尺寸规则并立即消费其矩形。 */
    private final UiLinearLayout rowLayout = new UiLinearLayout(UiLinearLayout.Axis.HORIZONTAL, 0, 0);
    /** 底部按钮行：取消 68 + 8 间距 + 确认 68。 */
    private final UiLinearLayout footerLayout = new UiLinearLayout(UiLinearLayout.Axis.HORIZONTAL, 0, 0);
    private String draftLuck;
    private int toolIndex, sampleIndex, firstRow;
    private int panelX, panelY, panelHeight;
    private int lastWidth, lastHeight;
    private boolean dirty = true;
    private boolean draggingLuck;
    private int dragAnchor;
    @Nullable private Font measuredFont;
    @Nullable private EditBox luckBox;
    @Nullable private Component error;

    public ScenarioParamsOverlay(OverlayLayer layer, ResourceLocation table, String scene,
                                 SimulationOptions options, ScenarioParams current) {
        this.layer = layer;
        this.table = table;
        this.scene = scene;
        this.options = options;
        tools = options.tools();
        samples = options.samples();
        List<ResourceLocation> ids = new ArrayList<>(options.enchantments().keySet());
        ids.sort(Comparator.comparing(ResourceLocation::toString));
        enchantments = List.copyOf(ids);
        for (int i = 0; i < tools.size(); i++) if (tools.get(i).id().equals(current.toolId())) toolIndex = i;
        sampleIndex = Math.max(0, samples.indexOf(current.sampleCount()));
        for (ResourceLocation id : enchantments) levels.put(id, Math.clamp(current.toolEnchantments().getOrDefault(id, 0),
                0, options.enchantments().get(id)));
        draftLuck = String.format(Locale.ROOT, "%.2f", current.luck());
        // 激活键仲裁：Enter 保持「确认」的既有契约，Space 用来激活键盘焦点控件。
        focus.setEnterActivates(false);
        focus.setSpaceActivates(true);
        // 本浮层是**分页窗口**模型：只创建并登记窗口内那一屏的行，因此 Tab 永远只在窗口内环绕，
        // 「焦点滚出视口」不可能发生，也就不需要焦点进视口的监听（窗口的移动由 PageUp/PageDown 负责）。
    }

    // 内容重排：仅在 dirty、字体变化或画布尺寸变化时执行；控件按稳定 key 复用。
    private void layout(Font font) {
        if (!dirty && measuredFont == font && lastWidth == layer.width() && lastHeight == layer.height()) return;
        if (luckBox == null || measuredFont != font) {
            luckBox = new EditBox(font, 0, 0, 76, 16, text("params.luck"));
            luckBox.setMaxLength(16);
            luckBox.setValue(draftLuck);
            luckBox.setResponder(value -> draftLuck = value);
        }
        measuredFont = font;
        lastWidth = layer.width(); lastHeight = layer.height(); dirty = false;
        int totalRows = 3 + enchantments.size();
        int visibleRows = Math.clamp((layer.height() - 94) / 20, 1, totalRows);
        firstRow = Math.clamp(firstRow, 0, Math.max(0, totalRows - visibleRows));
        panelHeight = 64 + visibleRows * 20;
        panelX = (layer.width() - PANEL_WIDTH) / 2;
        panelY = (layer.height() - panelHeight) / 2;

        controls.beginUpdate();
        focus.beginUpdate();
        // 标题：固定矩形（panelX+8, panelY+6, 260, 16）。
        place(font, "title", text("params.title"), panelX + 8, panelY + 6, CONTENT_WIDTH, 16, null);

        // 行槽：y = panelY+26+(row-firstRow)*20、槽高 20 全部交给纵向布局。
        List<UiLinearLayout.Child> slots = new ArrayList<>(visibleRows);
        for (int i = 0; i < visibleRows; i++) slots.add(UiLinearLayout.Child.fixed(20));
        rowsLayout.setChildren(slots);
        rowsLayout.setBounds(panelX + 8, panelY + 26, CONTENT_WIDTH, visibleRows * 20);

        luckBox.visible = false;
        for (int row = firstRow; row < firstRow + visibleRows; row++) {
            var slot = rowsLayout.bounds(row - firstRow);
            // 行内横排：高度 16（槽内顶对齐），x/宽直接取槽位。
            rowLayout.setBounds(slot.x(), slot.y(), slot.width(), 16);
            if (row == 0) {
                // 工具行：标签 96 + 2 + ◀ 16 + 1 + 名称 128 + 1 + ▶ 16 = 260。
                rowLayout.setChildren(List.of(
                        UiLinearLayout.Child.fixed(96),
                        UiLinearLayout.Child.fixed(16).withLeading(2),
                        UiLinearLayout.Child.fixed(128).withLeading(1),
                        UiLinearLayout.Child.fixed(16).withLeading(1)));
                place(font, "tool.label", text("params.tool"), rowLayout, 0, null);
                UiControl toolPrev = place(font, "tool.prev", Component.literal("◀"), rowLayout, 1,
                        () -> { toolIndex = cycle(toolIndex, -1, tools.size()); dirty = true; });
                Component name = tools.isEmpty() ? text("unavailable") : tools.get(toolIndex).displayName();
                place(font, "tool.name", name, rowLayout, 2, null);
                UiControl toolNext = place(font, "tool.next", Component.literal("▶"), rowLayout, 3,
                        () -> { toolIndex = cycle(toolIndex, 1, tools.size()); dirty = true; });
                // 这一行只有两个箭头是停靠点：标签与工具名是只读文本。
                focus.add(toolPrev);
                focus.add(toolNext);
            } else if (row == 1) {
                // 幸运值行：标签 96 + 2 + 原生输入框 76（abs x = panelX+106，高 16）。
                rowLayout.setChildren(List.of(
                        UiLinearLayout.Child.fixed(96),
                        UiLinearLayout.Child.fixed(76).withLeading(2)));
                place(font, "luck.label", text("params.luck"), rowLayout, 0, null);
                var boxSlot = rowLayout.bounds(1);
                luckBox.visible = true;
                luckBox.setX(boxSlot.x());
                luckBox.setY(boxSlot.y());
                // 原生输入框夹在工具行与抽样行之间：只有登记到同一序列里，Tab 才能从它移进移出。
                focus.add(luckTarget);
            } else if (row == 2) {
                // 抽样行：标签 96 后接等宽样本格，第 i 格 abs x = panelX+106+i*cellWidth。
                int cellWidth = 162 / Math.max(1, samples.size());
                List<UiLinearLayout.Child> cells = new ArrayList<>(samples.size() + 1);
                cells.add(UiLinearLayout.Child.fixed(96));
                for (int i = 0; i < samples.size(); i++) {
                    // 样本数极端多时 cellWidth-2 会为负，钳到 0 避免布局拒绝。
                    cells.add(UiLinearLayout.Child.fixed(Math.max(0, cellWidth - 2)).withLeading(2));
                }
                rowLayout.setChildren(cells);
                place(font, "samples.label", text("params.samples"), rowLayout, 0, null);
                for (int i = 0; i < samples.size(); i++) {
                    int sample = i;
                    Component sampleLabel = Component.literal(Integer.toString(samples.get(i)));
                    if (i == sampleIndex) sampleLabel = sampleLabel.copy().withStyle(net.minecraft.ChatFormatting.BOLD);
                    UiControl cell = place(font, "sample:" + i, sampleLabel, rowLayout, i + 1,
                            () -> { sampleIndex = sample; dirty = true; });
                    // 语义选中态：当前抽样格常亮选中底色（原有加粗保留），键盘与鼠标共用同一状态。
                    cell.setSelected(i == sampleIndex);
                    focus.add(cell);
                }
            } else {
                // 附魔行：名称 150 + 12 + − 20 + 2 + 数值 44 + 2 + ＋ 24（abs 170 / 192 / 238）。
                ResourceLocation id = enchantments.get(row - 3);
                rowLayout.setChildren(List.of(
                        UiLinearLayout.Child.fixed(150),
                        UiLinearLayout.Child.fixed(20).withLeading(12),
                        UiLinearLayout.Child.fixed(44).withLeading(2),
                        UiLinearLayout.Child.fixed(24).withLeading(2)));
                place(font, "ench.label:" + id, Component.literal(enchantmentName(id)), rowLayout, 0, null);
                UiControl minus = place(font, "ench.minus:" + id, Component.literal("−"), rowLayout, 1,
                        () -> changeLevel(id, -1));
                place(font, "ench.level:" + id, Component.literal(Integer.toString(levels.get(id))), rowLayout, 2, null);
                UiControl plus = place(font, "ench.plus:" + id, Component.literal("+"), rowLayout, 3,
                        () -> changeLevel(id, 1));
                focus.add(minus);
                focus.add(plus);
            }
        }
        if (!luckBox.visible) { luckBox.setFocused(false); draggingLuck = false; }

        // 提示行固定矩形；底部按钮行由横排布局产出（panelX+66 起，取消 68 + 8 + 确认 68）。
        Component hint = error != null ? error : (visibleRows < totalRows ? text("params.scroll_hint") : Component.empty());
        place(font, "hint", hint, panelX + 8, panelY + panelHeight - 36, CONTENT_WIDTH, 14, null);
        footerLayout.setChildren(List.of(
                UiLinearLayout.Child.fixed(68),
                UiLinearLayout.Child.fixed(68).withLeading(8)));
        footerLayout.setBounds(panelX + 66, panelY + panelHeight - 20, 144, 16);
        UiControl cancel = place(font, "cancel", text("params.cancel"), footerLayout, 0, layer::close);
        UiControl confirm = place(font, "confirm", text("params.confirm"), footerLayout, 1, this::confirm);
        // 底部按钮排在最后：Tab 走完可见行才落到取消/确认。
        focus.add(cancel);
        focus.add(confirm);

        controls.endUpdate();
        // 焦点收尾必须在控件组裁剪之后：被移除的控件不能以「仍可聚焦」的旧状态留在序列里。
        focus.endUpdate();
    }

    // 摆放控件：按稳定 key 取用并重配；只读标签传 action=null，保持迁移前的悬停底色与文本色。
    private UiControl place(Font font, Object key, Component label, int x, int y, int width, int height,
                       @Nullable UiAction action) {
        UiControl control = controls.obtain(key);
        control.setBounds(x, y, width, height);
        control.configure(font, label, error != null && label == error ? UiTextPalette.Parchment.NEGATIVE : UiTextPalette.Parchment.BODY,
                null, List.of(label), action);
        return control;
    }

    // 摆放控件：坐标直接取布局产出的矩形。
    private UiControl place(Font font, Object key, Component label, UiLinearLayout layout, int index,
                       @Nullable UiAction action) {
        var rect = layout.bounds(index);
        return place(font, key, label, rect.x(), rect.y(), rect.width(), rect.height(), action);
    }

    /** 原生输入框适配器：只把可聚焦性、焦点态、矩形与名称映射给焦点管理器，文字编辑仍归原版控件。 */
    private final class LuckFocusTarget implements UiFocusTarget {
        @Override public boolean canFocus() { return luckBox != null && luckBox.visible; }

        @Override public void setFocused(boolean focused) {
            if (luckBox != null) luckBox.setFocused(focused);
        }

        // 输入框没有 Enter/Space 的「激活」语义：返回 false 不消费。
        @Override public boolean activate() { return false; }

        @Override public UiRect bounds() {
            if (luckBox == null) return new UiRect(0, 0, 0, 0);
            return new UiRect(luckBox.getX(), luckBox.getY(), luckBox.getWidth(), luckBox.getHeight());
        }

        // 收紧可空性：输入框的名称恒存在，接口上的 @Nullable 只是通用的宽松约定。
        @Override public Component accessibleName() { return text("params.luck"); }
    }

    @Override public void render(GuiGraphics graphics, Font font, int mouseX, int mouseY, float partialTick) {
        layout(font);
        graphics.fill(0, 0, layer.width(), layer.height(), 0xC0101010);
        graphics.fill(panelX - 1, panelY - 1, panelX + PANEL_WIDTH + 1, panelY + panelHeight + 1, 0xFF896C48);
        graphics.fill(panelX, panelY, panelX + PANEL_WIDTH, panelY + panelHeight, 0xFFF2E5C6);
        controls.render(graphics, font, mouseX, mouseY);
        if (luckBox != null && luckBox.visible) luckBox.render(graphics, mouseX, mouseY, partialTick);
        // 唯一 tooltip：只取最上层命中控件，避免标签与按钮重复绘制。
        controls.renderTooltip(graphics, font, mouseX, mouseY);
    }

    @Override public boolean mouseClicked(double x, double y, int button) {
        layout(Minecraft.getInstance().font);
        draggingLuck = false;
        if (button != 0) return true;
        // 原生输入框命中优先级高于控件组：夺焦后接管拖动选区。
        if (luckBox != null) {
            boolean boxHit = luckBox.visible && luckBox.isMouseOver(x, y);
            luckBox.setFocused(boxHit);
            // 输入框的焦点由原版自身维护，这里只把它同步给焦点管理器，避免两套焦点同时生效；
            // 点击普通控件不夺取焦点（焦点轮廓只在键盘使用时出现）。
            if (boxHit) focus.focusOn(luckTarget);
            else if (focus.focused() == luckTarget) focus.clearFocus();
            if (boxHit) {
                draggingLuck = luckBox.mouseClicked(x, y, button);
                dragAnchor = luckBox.getCursorPosition();
                return true;
            }
        }
        // 命中控件即激活；只读标签无 action，不产生副作用。
        controls.mousePressed(x, y, button);
        return true;
    }

    @Override public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        if (draggingLuck && button == 0 && luckBox != null) {
            luckBox.onClick(x, y);
            luckBox.setHighlightPos(dragAnchor);
        }
        return true;
    }
    @Override public boolean mouseReleased(double x, double y, int button) {
        if (button == 0) draggingLuck = false;
        controls.mouseReleased(button);
        return true;
    }
    @Override public boolean mouseScrolled(double x, double y, double amount) {
        firstRow -= (int) Math.signum(amount);
        dirty = true;
        return true;
    }

    private static int cycle(int index, int delta, int size) { return size == 0 ? 0 : Math.floorMod(index + delta, size); }
    private void changeLevel(ResourceLocation id, int delta) {
        levels.put(id, Math.clamp(levels.get(id) + delta, 0, options.enchantments().get(id)));
        dirty = true;
    }

    private void confirm() {
        try {
            if (tools.isEmpty() || samples.isEmpty()) throw new IllegalArgumentException("No options");
            float luck = Float.parseFloat(draftLuck.trim().replace(',', '.'));
            Map<ResourceLocation, Integer> selected = new LinkedHashMap<>();
            levels.forEach((id, level) -> { if (level > 0) selected.put(id, level); });
            ScenarioParams params = new ScenarioParams(luck, tools.get(toolIndex).id(), selected, samples.get(sampleIndex));
            var current = ScenarioSimulationClientState.table(table);
            if (current == null || current.options() == null || current.options().rejects(scene, params)) {
                error = text("failure.rejected_input"); dirty = true; return;
            }
            ScenarioSimulationClientState.select(table, scene, params);
            layer.close();
        } catch (IllegalArgumentException ignored) {
            error = text("luck_invalid");
            dirty = true;
        }
    }

    @Override public boolean keyPressed(int key, int scan, int modifiers) {
        layout(Minecraft.getInstance().font);
        if (key == GLFW.GLFW_KEY_ESCAPE) layer.close();
        else if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) confirm();
        // 输入框持有焦点时先让它处理（光标、退格、剪贴板、快捷键）；它不消费的键继续下传，
        // 因此 Tab/Shift+Tab 能从这个原生控件移出去。
        else if (luckBox != null && luckBox.visible && luckBox.isFocused() && luckBox.keyPressed(key, scan, modifiers)) return true;
        // 键盘翻窗口：窗口外的附魔行只有这一条键盘通路（Tab 只在当前窗口内环绕）。
        // 焦点策略与滚轮翻页保持一致：不显式清除，焦点目标若被移出窗口，由 endUpdate 丢弃并自然清空。
        else if (key == GLFW.GLFW_KEY_PAGE_DOWN || key == GLFW.GLFW_KEY_PAGE_UP) {
            firstRow += key == GLFW.GLFW_KEY_PAGE_DOWN ? 1 : -1;
            dirty = true;
        }
        // 其余交给焦点管理器：Tab/Shift+Tab 移焦点、Space 激活焦点控件；Enter 已在上方按既有契约确认。
        else focus.keyPressed(key, scan, modifiers);
        return true;
    }
    @Override public boolean charTyped(char value, int modifiers) {
        return luckBox != null && luckBox.visible && luckBox.charTyped(value, modifiers);
    }

    // 数据驱动附魔名称由当前客户端注册表提供。
    public static String enchantmentName(ResourceLocation id) {
        var level = Minecraft.getInstance().level;
        if (level == null) return id.toString();
        var enchantment = level.registryAccess().registry(Registries.ENCHANTMENT).map(registry -> registry.get(id)).orElse(null);
        return enchantment == null ? id.toString() : enchantment.description().getString();
    }
    private static Component text(String key) { return ScenarioSimulationClientState.text(key); }
}
