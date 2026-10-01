package com.meteorite.unsuspiciousblock.client.ui.overlay;

import com.meteorite.unsuspiciousblock.client.state.ScenarioSimulationClientState;
import com.meteorite.unsuspiciousblock.client.ui.kit.*;
import com.meteorite.unsuspiciousblock.client.ui.support.ScenarioUi;
import com.meteorite.unsuspiciousblock.client.ui.support.SimulationCatMascot;
import com.meteorite.unsuspiciousblock.client.ui.support.UiTextPalette;
import com.meteorite.unsuspiciousblock.client.ui.widget.ShadowlessEditBox;
import com.meteorite.unsuspiciousblock.client.ui.widget.SimulationToolDropdown;
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
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.registries.BuiltInRegistries;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;
import java.util.*;

/** 羊皮纸参数草稿：工具浏览、附魔搜索与分层提交；失败保留草稿，不自动跳页。 */
public final class ScenarioParamsOverlay implements OverlayLayer.Overlay {
    private static final int WIDTH = 300;
    private static final int ROW_HEIGHT = 20;
    private final OverlayLayer layer;
    private final ResourceLocation table;
    private final String scene;
    private final String tableHash;
    private final SimulationOptions options;
    private final List<ResourceLocation> enchantments;
    private final Map<ResourceLocation, Integer> levels = new LinkedHashMap<>();
    private final UiControlGroup controls = new UiControlGroup();
    private final UiFocusManager focus = new UiFocusManager();
    private String draftLuck;
    private String query = "";
    private int toolIndex;
    private int firstRow;
    @Nullable private SimulationToolDropdown toolDropdown;
    @Nullable private SimulationCatMascot mascot;
    private ItemStack previewTool = ItemStack.EMPTY;
    private ResourceLocation previewToolId;
    private boolean dirty = true;
    private int panelX, panelY, panelHeight, lastWidth, lastHeight;
    private Font measuredFont;
    private EditBox luckBox;
    private EditBox searchBox;
    private InputFocus luckFocus;
    private InputFocus searchFocus;
    private EditBox dragInput;
    private int dragAnchor;
    private UiRect listBounds = new UiRect(0, 0, 1, 1);
    @Nullable private Component error;

    public ScenarioParamsOverlay(OverlayLayer layer, ResourceLocation table, String scene,
                                 SimulationOptions options, ScenarioParams current) {
        this.layer = layer;
        this.table = table;
        this.scene = scene;
        this.options = options;
        var structure = ScenarioSimulationClientState.table(table);
        tableHash = structure == null ? "" : structure.hash();
        enchantments = options.enchantments().keySet().stream()
                .sorted(Comparator.comparing(ScenarioParamsOverlay::enchantmentName)).toList();
        levels.putAll(current.toolEnchantments());
        for (int index = 0; index < options.tools().size(); index++)
            if (options.tools().get(index).id().equals(current.toolId())) toolIndex = index;
        draftLuck = String.format(Locale.ROOT, "%.2f", current.luck());
        focus.setEnterActivates(false);
        focus.setSpaceActivates(true);
    }

    private void layout(Font font) {
        if (!dirty && font == measuredFont && lastWidth == layer.width() && lastHeight == layer.height()) return;
        boolean luckFocused = luckBox != null && luckBox.isFocused();
        boolean searchFocused = searchBox != null && searchBox.isFocused();
        boolean replaceInputs = luckBox == null || font != measuredFont;
        measuredFont = font;
        lastWidth = layer.width();
        lastHeight = layer.height();
        int availableHeight = Math.max(208, layer.height() - 12);
        int enchantmentRows = Math.min(enchantments.size(), Math.clamp((availableHeight - 228) / ROW_HEIGHT, 1, 4));
        panelHeight = Math.min(244 + enchantmentRows * ROW_HEIGHT, availableHeight);
        panelX = (layer.width() - WIDTH) / 2;
        panelY = (layer.height() - panelHeight) / 2;
        if (replaceInputs) {
            luckBox = input(font, 58, text("params.luck"));
            luckBox.setValue(draftLuck);
            luckBox.setResponder(value -> { draftLuck = value; error = null; });
            searchBox = input(font, WIDTH - 28, text("params.search"));
            searchBox.setValue(query);
            searchBox.setResponder(value -> { query = value; firstRow = 0; dirty = true; });
            luckFocus = new InputFocus(luckBox);
            searchFocus = new InputFocus(searchBox);
        }
        luckBox.setPosition(panelX + 166, panelY + 36);
        searchBox.setPosition(panelX + 14, panelY + 67);
        searchBox.visible = !enchantments.isEmpty();
        luckBox.setFocused(luckFocused);
        searchBox.setFocused(searchFocused && searchBox.visible);
        listBounds = new UiRect(panelX + 10, panelY + 91, WIDTH - 20, enchantmentRows * ROW_HEIGHT);
        List<ResourceLocation> filtered = filteredEnchantments();
        int rowCount = filtered.size();
        int visible = Math.max(1, listBounds.height() / ROW_HEIGHT);
        firstRow = Math.clamp(firstRow, 0, Math.max(0, rowCount - visible));
        controls.beginUpdate();
        focus.beginUpdate();
        ToolOption tool = options.tools().get(toolIndex);
        if (!tool.id().equals(previewToolId)) {
            previewToolId = tool.id();
            previewTool = new ItemStack(BuiltInRegistries.ITEM.get(tool.id()));
        }
        if (options.toolSelectionAllowed()) {
            add(font, "tool", new UiRect(panelX + 10, panelY + 34, 112, 20),
                    tool.displayName().copy().append(" ▾"), previewTool.isEmpty() ? null : new UiIcon.Item(previewTool),
                    this::openToolDropdown, false);
        }
        if (toolDropdown != null) toolDropdown.setPosition(panelX + 10, panelY + 56);
        focus.add(luckFocus);
        if (searchBox.visible) focus.add(searchFocus);
        for (int position = firstRow; position < Math.min(rowCount, firstRow + visible); position++) {
            int rowY = listBounds.y() + (position - firstRow) * ROW_HEIGHT;
            ResourceLocation id = filtered.get(position);
            int level = levels.getOrDefault(id, 0);
            Component label = ScenarioSimulationClientState.text("params.enchant_level", enchantmentName(id), level)
                    .copy().withStyle(style -> style.withColor(level > 0 ? UiTextPalette.Parchment.POSITIVE : UiTextPalette.Parchment.BODY));
            add(font, "name:" + id, new UiRect(listBounds.x(), rowY, listBounds.width() - 44, 18), label, null, null, false);
            UiControl minus = add(font, "minus:" + id, new UiRect(listBounds.right() - 42, rowY, 18, 18),
                    Component.literal("−"), null, () -> changeLevel(id, -1), false);
            UiControl plus = add(font, "plus:" + id, new UiRect(listBounds.right() - 20, rowY, 18, 18),
                    Component.literal("+"), null, () -> changeLevel(id, 1), false);
            minus.setEnabled(level > 0);
            plus.setEnabled(level < options.enchantments().get(id));
        }
        int footerY = panelY + panelHeight - 26;
        add(font, "calculate", new UiRect(panelX + 10, footerY, 100, 18), text("params.apply_calculate"),
                null, () -> confirm(true), true);
        add(font, "apply", new UiRect(panelX + 114, footerY, 82, 18), text("params.apply_only"),
                null, () -> confirm(false), false);
        add(font, "cancel", new UiRect(panelX + 200, footerY, 66, 18), Component.translatable("gui.cancel"),
                null, layer::close, false);
        controls.endUpdate();
        focus.endUpdate();
        dirty = false;
    }

    // 下拉层只覆盖按钮下方区域；外部点击或 Escape 先关闭下拉，不触发窗口底层操作。
    private void openToolDropdown() {
        focus.clearFocus();
        luckBox.setFocused(false);
        searchBox.setFocused(false);
        dragInput = null;
        toolDropdown = new SimulationToolDropdown(options.tools(), toolIndex, selected -> {
            toolIndex = selected;
            toolDropdown = null;
            dirty = true;
        }, () -> toolDropdown = null);
        toolDropdown.setPosition(panelX + 10, panelY + 56);
    }

    private List<ResourceLocation> filteredEnchantments() {
        String needle = query.trim().toLowerCase(Locale.ROOT);
        return enchantments.stream().filter(id -> levels.getOrDefault(id, 0) > 0 || needle.isEmpty()
                        || id.toString().toLowerCase(Locale.ROOT).contains(needle)
                        || enchantmentName(id).toLowerCase(Locale.ROOT).contains(needle))
                .sorted(Comparator.comparingInt(id -> levels.getOrDefault(id, 0) > 0 ? 0 : 1)).toList();
    }

    private ShadowlessEditBox input(Font font, int width, Component label) {
        ShadowlessEditBox box = new ShadowlessEditBox(font, 0, 0, width, 14, label);
        box.setBordered(false);
        box.setTextColor(UiTextPalette.Parchment.NAME);
        box.setMaxLength(128);
        box.setHint(label.copy().withStyle(style -> style.withColor(UiTextPalette.Parchment.HINT)));
        return box;
    }

    private UiControl add(Font font, String key, UiRect bounds, Component label, UiIcon icon,
                          UiAction action, boolean primary) {
        UiControl control = controls.obtain(key);
        control.setBounds(bounds.x(), bounds.y(), bounds.width(), bounds.height());
        control.setStyle(primary ? ScenarioUi.ACTION : ScenarioUi.QUIET);
        control.configure(font, label, UiTextPalette.Parchment.BODY, icon, List.of(label), action);
        if (action != null) focus.add(control);
        return control;
    }

    @Override public void render(GuiGraphics graphics, Font font, int mouseX, int mouseY, float partialTick) {
        layout(font);
        graphics.fill(0, 0, layer.width(), layer.height(), 0x70000000);
        ScenarioUi.renderPage(graphics, new UiRect(panelX, panelY, WIDTH, panelHeight));
        graphics.drawString(font, text("params.title"), panelX + 10, panelY + 9, UiTextPalette.Parchment.TITLE, false);
        graphics.drawString(font, text("params.simulation_only"), panelX + 10, panelY + 21, UiTextPalette.Parchment.HINT, false);
        graphics.drawString(font, text("params.luck"), panelX + 134, panelY + 41, UiTextPalette.Parchment.LABEL, false);
        graphics.drawString(font, text("params.fixed_samples"), panelX + 228, panelY + 41, UiTextPalette.Parchment.HINT, false);
        if (enchantments.isEmpty()) graphics.drawString(font,
                text("params.no_enchantments"), panelX + 12, panelY + 72,
                UiTextPalette.Parchment.LABEL, false);
        if (Minecraft.getInstance().level != null) {
            if (mascot == null) mascot = new SimulationCatMascot(Minecraft.getInstance());
            int mascotSize = Math.clamp((panelY + panelHeight - 52 - listBounds.bottom()) * 2L / 3, 28, 68);
            mascot.render(graphics, panelX + WIDTH / 2, panelY + panelHeight - 48, mascotSize,
                    mouseX, mouseY, previewTool);
        }
        controls.render(graphics, font, mouseX, mouseY);
        renderInput(graphics, luckBox, mouseX, mouseY, partialTick);
        if (searchBox.visible) renderInput(graphics, searchBox, mouseX, mouseY, partialTick);
        if (error != null) graphics.drawString(font, error, panelX + 10, panelY + panelHeight - 42,
                UiTextPalette.Parchment.SEVERE, false);
        if (toolDropdown == null) controls.renderTooltip(graphics, font, mouseX, mouseY);
        else toolDropdown.render(graphics, font, mouseX, mouseY);
    }

    private static void renderInput(GuiGraphics graphics, EditBox box, int mouseX, int mouseY, float partialTick) {
        graphics.fill(box.getX() - 3, box.getY() - 3, box.getX() + box.getWidth() + 3, box.getY() + 17, 0x30B99A60);
        graphics.fill(box.getX() - 3, box.getY() + 16, box.getX() + box.getWidth() + 3, box.getY() + 17,
                box.isFocused() ? ScenarioUi.BRANCH : 0x60977F54);
        box.render(graphics, mouseX, mouseY, partialTick);
    }

    private void changeLevel(ResourceLocation id, int delta) {
        levels.put(id, Math.clamp(levels.getOrDefault(id, 0) + delta, 0, options.enchantments().get(id)));
        error = null; dirty = true;
    }

    private void confirm(boolean calculate) {
        try {
            float luck = Float.parseFloat(draftLuck.trim().replace(',', '.'));
            ScenarioParams params = new ScenarioParams(luck, options.tools().get(toolIndex).id(), levels,
                    ScenarioParams.DEFAULT_SAMPLE_COUNT);
            var latest = ScenarioSimulationClientState.table(table);
            if (latest == null || latest.options() == null || !latest.hash().equals(tableHash)
                    || latest.options().generation() != options.generation() || latest.options().rejects(scene, params)) {
                error = text("failure.rejected_input"); return;
            }
            ScenarioSimulationClientState.select(table, scene, params);
            if (calculate) ScenarioSimulationClientState.request(table, true);
            layer.close();
        } catch (IllegalArgumentException exception) { error = text("luck_invalid"); }
    }

    @Override public boolean mouseClicked(double mouseX, double mouseY, int button) {
        layout(Minecraft.getInstance().font);
        if (toolDropdown != null) {
            toolDropdown.mouseClicked(Minecraft.getInstance().font, mouseX, mouseY, button);
            return true;
        }
        if (button != 0) return true;
        focus.clearFocus();
        luckBox.setFocused(false);
        searchBox.setFocused(false);
        for (EditBox box : List.of(luckBox, searchBox)) {
            box.setFocused(box.visible && box.isMouseOver(mouseX, mouseY));
            if (box.isFocused()) {
                focus.focusOn(box == luckBox ? luckFocus : searchFocus);
                box.mouseClicked(mouseX, mouseY, button);
                dragInput = box; dragAnchor = box.getCursorPosition(); return true;
            }
        }
        controls.mousePressed(mouseX, mouseY, button);
        return true;
    }
    @Override public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        if (toolDropdown != null) return true;
        if (button == 0 && dragInput != null) { dragInput.onClick(mouseX, mouseY); dragInput.setHighlightPos(dragAnchor); }
        return true;
    }
    @Override public boolean mouseReleased(double mouseX, double mouseY, int button) {
        dragInput = null; controls.mouseReleased(button); return true;
    }
    @Override public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        if (toolDropdown != null) { toolDropdown.mouseScrolled(amount); return true; }
        if (listBounds.contains(mouseX, mouseY)) { firstRow -= (int) Math.signum(amount); dirty = true; }
        return true;
    }
    @Override public boolean keyPressed(int key, int scan, int modifiers) {
        layout(Minecraft.getInstance().font);
        if (toolDropdown != null) { toolDropdown.keyPressed(key, modifiers); return true; }
        if (key == GLFW.GLFW_KEY_ESCAPE) { layer.close(); return true; }
        for (EditBox box : List.of(luckBox, searchBox)) {
            if (box.visible && box.isFocused()) {
                if (box.keyPressed(key, scan, modifiers) || key == GLFW.GLFW_KEY_BACKSPACE) return true;
            }
        }
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) confirm(true);
        else if (key == GLFW.GLFW_KEY_PAGE_DOWN || key == GLFW.GLFW_KEY_PAGE_UP) {
            firstRow += key == GLFW.GLFW_KEY_PAGE_DOWN ? 1 : -1; dirty = true;
        } else focus.keyPressed(key, scan, modifiers);
        return true;
    }
    @Override public boolean charTyped(char value, int modifiers) {
        if (toolDropdown != null) return true;
        for (EditBox box : List.of(luckBox, searchBox)) if (box.visible && box.isFocused()) return box.charTyped(value, modifiers);
        return true;
    }

    /** 原版文本控件只适配焦点，不改写输入法与文字编辑行为。 */
    private record InputFocus(EditBox box) implements UiFocusTarget {
        @Override public boolean canFocus() { return box.visible && box.active; }
        @Override public void setFocused(boolean value) { box.setFocused(value); }
        @Override public boolean isFocused() { return box.isFocused(); }
        @Override public boolean activate() { return false; }
        @Override public Component accessibleName() { return box.getMessage(); }
        @Override public UiRect bounds() { return new UiRect(box.getX(), box.getY(), box.getWidth(), box.getHeight()); }
    }

    public static String enchantmentName(ResourceLocation id) {
        var level = Minecraft.getInstance().level;
        if (level == null) return id.toString();
        var enchantment = level.registryAccess().registry(Registries.ENCHANTMENT).map(registry -> registry.get(id)).orElse(null);
        return enchantment == null ? id.toString() : enchantment.description().getString();
    }
    private static Component text(String key) { return ScenarioSimulationClientState.text(key); }
}
