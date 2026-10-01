package com.meteorite.unsuspiciousblock.client.ui.overlay;

import com.meteorite.unsuspiciousblock.client.state.ScenarioSimulationClientState;
import com.meteorite.unsuspiciousblock.client.ui.kit.*;
import com.meteorite.unsuspiciousblock.client.ui.support.ScenarioUi;
import com.meteorite.unsuspiciousblock.client.ui.support.SimulationCatMascot;
import com.meteorite.unsuspiciousblock.client.ui.support.UiTextPalette;
import com.meteorite.unsuspiciousblock.client.ui.widget.ShadowlessEditBox;
import com.meteorite.unsuspiciousblock.client.ui.widget.SimulationToolDropdown;
import com.meteorite.unsuspiciousblock.loottable.catalog.SimulationOptions;
import com.meteorite.unsuspiciousblock.loottable.catalog.CatalogTableDto;
import com.meteorite.unsuspiciousblock.text.TooltipBuilder;
import com.meteorite.unsuspiciousblock.loottable.analysis.LuckGateAnalysis;
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

/***
 * 羊皮纸装备卡：吉祥物坐镇左侧，四项参数作为能力值列在它右侧；下方是可滚动的附魔清单。
 * 失败保留草稿，不自动跳页。
 */
public final class ScenarioParamsOverlay implements OverlayLayer.Overlay {
    private static final int WIDTH = 284;
    private static final float MIN_LUCK = (float) LuckGateAnalysis.MIN_LUCK;
    private static final float MAX_LUCK = (float) LuckGateAnalysis.MAX_LUCK;
    private static final int PAD = 10;
    private static final int ROW_HEIGHT = 20;
    // 能力值行高：四行读数并排于吉祥物右侧，数值贴面板右边界对齐成一条读数轴。
    private static final int ATTRIBUTE_ROW_HEIGHT = 28;
    private static final int ATTRIBUTE_ROWS = 4;
    // 吉祥物列宽：猫与能力值列的横向分界，同时决定猫的水平居中位置。
    private static final int MASCOT_COLUMN = 88;
    private static final int MASCOT_SIZE = 52;
    // 成年猫的脚边留出铭牌与提示文字的位置。
    private static final int MASCOT_BASELINE_LIFT = 25;
    private static final int CARD_TOP = 32;
    private static final int CARD_HEIGHT = ATTRIBUTE_ROWS * ATTRIBUTE_ROW_HEIGHT;
    // 附魔清单顶部：装备卡下方留出搜索框带。 */
    private static final int LIST_TOP = CARD_TOP + CARD_HEIGHT + 28;
    // 内容底到面板底之间留给底部按钮的高度。 */
    private static final int FOOTER_BAND = 30;
    // 标题带与装备卡之间的分隔线，沿纸面装饰色。 */
    private static final int RULE = 0x50A99370;
    // 读数由原生输入框自绘的行，跳过文字绘制（输入框的透明色不会与任何值色冲突）。
    private static final int NO_VALUE = 0;
    private static final int LUCK_GATE_MARKER = 0xFF9C641F;
    private static final int PLAYER_LUCK_MARKER = 0xFF376B89;
    @Nullable private CatalogTableDto markerTable;
    private List<LuckMarker> luckMarkers = List.of();
    private float playerLuck = Float.NaN;
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
    private boolean draggingLuck;
    private UiControl luckSlider;
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
        int availableHeight = Math.max(LIST_TOP + ROW_HEIGHT + FOOTER_BAND, layer.height() - 12);
        int enchantmentRows = Math.min(enchantments.size(),
                Math.clamp((availableHeight - LIST_TOP - FOOTER_BAND) / ROW_HEIGHT, 1, 4));
        // 无附魔时面板收成一张紧凑装备卡：只保留吉祥物与能力值列。
        panelHeight = enchantments.isEmpty() ? CARD_TOP + CARD_HEIGHT + FOOTER_BAND
                : LIST_TOP + enchantmentRows * ROW_HEIGHT + FOOTER_BAND;
        panelHeight = Math.min(panelHeight, availableHeight);
        panelX = (layer.width() - WIDTH) / 2;
        panelY = (layer.height() - panelHeight) / 2;
        if (replaceInputs) {
            luckBox = input(font, 58, text("params.luck"));
            luckBox.setValue(draftLuck);
            luckBox.setTextColor(luckColor(draftLuck));
            luckBox.setResponder(value -> {
                draftLuck = value;
                error = null;
                luckBox.setTextColor(luckColor(value));
            });
            searchBox = input(font, WIDTH - 28, text("params.search"));
            searchBox.setValue(query);
            searchBox.setResponder(value -> { query = value; firstRow = 0; dirty = true; });
            luckFocus = new InputFocus(luckBox);
            searchFocus = new InputFocus(searchBox);
        }
        // 幸运框每帧按文本宽度重新贴右，位置在 render 里计算。
        EditBox luck = Objects.requireNonNull(luckBox);
        EditBox search = Objects.requireNonNull(searchBox);
        search.setPosition(panelX + PAD, panelY + CARD_TOP + CARD_HEIGHT + 6);
        search.visible = !enchantments.isEmpty();
        luck.setFocused(luckFocused);
        search.setFocused(searchFocused && search.visible);
        listBounds = new UiRect(panelX + PAD, panelY + LIST_TOP, WIDTH - 2 * PAD, enchantmentRows * ROW_HEIGHT);
        List<ResourceLocation> filtered = filteredEnchantments();
        int rowCount = filtered.size();
        int visible = Math.max(1, listBounds.height() / ROW_HEIGHT);
        firstRow = Math.clamp(firstRow, 0, Math.max(0, rowCount - visible));
        controls.beginUpdate();
        focus.beginUpdate();
        luckSlider = add(font, "luck:slider", sliderBounds(), Component.empty(),
                List.of(), () -> setLuck(0), false);
        luckSlider.setAccessibleName(text("params.luck"));
        focus.add(luckFocus);
        ToolOption tool = options.tools().get(toolIndex);
        if (!tool.id().equals(previewToolId)) {
            previewToolId = tool.id();
            previewTool = new ItemStack(BuiltInRegistries.ITEM.get(tool.id()));
        }
        // 能力值行既是读数也是入口：工具行换装备，附魔行跳到清单，样本行只解释为何不可调。
        if (options.toolSelectionAllowed()) {
            add(font, "attr:tool", attributeBounds(1), Component.empty(),
                    List.of(tool.displayName(), text("params.choose_tool")), this::openToolDropdown, false);
        }
        add(font, "attr:enchantments", attributeBounds(2), Component.empty(),
                List.of(text(enchantments.isEmpty() ? "params.no_enchantments" : "params.search")),
                enchantments.isEmpty() ? null : this::focusEnchantmentSearch, false);
        add(font, "attr:samples", attributeBounds(3), Component.empty(),
                List.of(text("params.fixed_samples")), null, false);
        if (toolDropdown != null) {
            toolDropdown.setPosition(panelX + WIDTH - PAD - 8 - SimulationToolDropdown.WIDTH,
                    panelY + CARD_TOP + 2 * ATTRIBUTE_ROW_HEIGHT);
        }
        if (search.visible) focus.add(searchFocus);
        for (int position = firstRow; position < Math.min(rowCount, firstRow + visible); position++) {
            int rowY = listBounds.y() + (position - firstRow) * ROW_HEIGHT;
            ResourceLocation id = filtered.get(position);
            int level = levels.getOrDefault(id, 0);
            Component label = ScenarioSimulationClientState.text("params.enchant_level", enchantmentName(id), level)
                    .copy().withStyle(style -> style.withColor(level > 0 ? UiTextPalette.Parchment.POSITIVE : UiTextPalette.Parchment.BODY));
            add(font, "name:" + id, new UiRect(listBounds.x(), rowY, listBounds.width() - 44, 18), label, null, false);
            UiControl minus = add(font, "minus:" + id, new UiRect(listBounds.right() - 42, rowY, 18, 18),
                    Component.literal("−"), () -> changeLevel(id, -1), false);
            UiControl plus = add(font, "plus:" + id, new UiRect(listBounds.right() - 20, rowY, 18, 18),
                    Component.literal("+"), () -> changeLevel(id, 1), false);
            minus.setEnabled(level > 0);
            plus.setEnabled(level < options.enchantments().get(id));
        }
        int footerY = panelY + panelHeight - 26;
        add(font, "calculate", new UiRect(panelX + 10, footerY, 100, 18), text("params.apply_calculate"),
                () -> confirm(true), true);
        add(font, "apply", new UiRect(panelX + 114, footerY, 82, 18), text("params.apply_only"),
                () -> confirm(false), false);
        add(font, "cancel", new UiRect(panelX + 200, footerY, 66, 18), Component.translatable("gui.cancel"),
                layer::close, false);
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
        draggingLuck = false;
        toolDropdown = new SimulationToolDropdown(options.tools(), toolIndex, selected -> {
            toolIndex = selected;
            toolDropdown = null;
            dirty = true;
        }, () -> toolDropdown = null);
        toolDropdown.setPosition(panelX + WIDTH - PAD - 8 - SimulationToolDropdown.WIDTH,
                    panelY + CARD_TOP + 2 * ATTRIBUTE_ROW_HEIGHT);
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

    private UiControl add(Font font, String key, UiRect bounds, Component label,
                          UiAction action, boolean primary) {
        return add(font, key, bounds, label, List.of(label), action, primary);
    }

    // 能力值行不画自己的文字（读数由 renderAttributes 贴边对齐），控件只承担命中、焦点与提示。
    private UiControl add(Font font, String key, UiRect bounds, Component label,
                          List<Component> tooltip, UiAction action, boolean primary) {
        UiControl control = controls.obtain(key);
        control.setBounds(bounds.x(), bounds.y(), bounds.width(), bounds.height());
        control.setStyle(primary ? ScenarioUi.ACTION : ScenarioUi.QUIET);
        control.configure(font, label, UiTextPalette.Parchment.BODY, null, tooltip, action);
        if (action != null) focus.add(control);
        return control;
    }

    @Override public void render(GuiGraphics graphics, Font font, int mouseX, int mouseY, float partialTick) {
        layout(font);
        graphics.fill(0, 0, layer.width(), layer.height(), 0x70000000);
        ScenarioUi.renderPage(graphics, new UiRect(panelX, panelY, WIDTH, panelHeight));
        graphics.drawString(font, text("params.title"), panelX + PAD, panelY + 7, UiTextPalette.Parchment.TITLE, false);
        // 出错时占用副标题的位置，避免为一行提示在紧凑卡片里再挤出一条高度。
        graphics.drawString(font, error != null ? error : text("params.simulation_only"),
                panelX + PAD, panelY + 19,
                error != null ? UiTextPalette.Parchment.SEVERE : UiTextPalette.Parchment.HINT, false);
        graphics.fill(panelX + PAD, panelY + 29, panelX + WIDTH - PAD, panelY + 30, RULE);
        placeLuckBox(font);
        renderPortrait(graphics, font);
        if (Minecraft.getInstance().level != null) {
            if (mascot == null) mascot = new SimulationCatMascot(Minecraft.getInstance());
            mascot.render(graphics, panelX + PAD + (MASCOT_COLUMN - 8) / 2,
                    panelY + CARD_TOP + CARD_HEIGHT - MASCOT_BASELINE_LIFT,
                    MASCOT_SIZE, mouseX, mouseY, previewTool);
        }
        renderAttributeCard(graphics);
        controls.render(graphics, font, mouseX, mouseY);
        renderAttributes(graphics, font);
        luckBox.render(graphics, mouseX, mouseY, partialTick);
        renderLuckSlider(graphics);
        if (searchBox.visible) renderInput(graphics, searchBox, mouseX, mouseY, partialTick);
        if (toolDropdown == null) {
            if (mascot != null && mascot.isHeadHovered(mouseX, mouseY))
                graphics.renderTooltip(font, text("params.pet_hint"), mouseX, mouseY);
            else if (!renderLuckTooltip(graphics, font, mouseX, mouseY))
                controls.renderTooltip(graphics, font, mouseX, mouseY);
        }
        else toolDropdown.render(graphics, font, mouseX, mouseY);
    }

    // 幸运框宽度跟随草稿文本，右边界恒贴能力值列的读数轴，读起来与其它三项数值对齐。
    private void placeLuckBox(Font font) {
        int right = panelX + WIDTH - PAD - 10;
        int width = Math.clamp(font.width(draftLuck) + 2, 30, 58);
        luckBox.setWidth(width);
        luckBox.setX(right - width);
        luckBox.setY(panelY + CARD_TOP + 5);
    }

    // 肖像底纹、角饰与铭牌沿用纸面结构色。
    private void renderPortrait(GuiGraphics graphics, Font font) {
        int x = panelX + PAD, y = panelY + CARD_TOP;
        int w = MASCOT_COLUMN - 8;
        graphics.fill(x, y, x + w, y + CARD_HEIGHT, 0x187D6846);
        graphics.fill(x + w + 3, y + 4, x + w + 4, y + CARD_HEIGHT - 4, RULE);
        for (int corner : new int[]{0, 1}) {
            int cx = corner == 0 ? x : x + w - 8;
            graphics.fill(cx, y, cx + 8, y + 1, RULE);
            graphics.fill(cx, y + CARD_HEIGHT - 1, cx + 8, y + CARD_HEIGHT, RULE);
        }
        graphics.drawString(font, text("params.companion"),
                x + (w - font.width(text("params.companion"))) / 2, y + 5,
                UiTextPalette.Parchment.LABEL, false);
        graphics.fill(x + 20, y + CARD_HEIGHT - 20, x + w - 20, y + CARD_HEIGHT - 17, 0x22705B3D);
        graphics.drawString(font, text("params.pet_caption"),
                x + (w - font.width(text("params.pet_caption"))) / 2, y + CARD_HEIGHT - 11,
                UiTextPalette.Parchment.HINT, false);
    }

    private UiRect sliderBounds() {
        return new UiRect(panelX + PAD + MASCOT_COLUMN + 10, panelY + CARD_TOP + 17,
                WIDTH - 2 * PAD - MASCOT_COLUMN - 20, 10);
    }

    private float sliderLuck() {
        try {
            float value = Float.parseFloat(draftLuck.trim().replace(',', '.'));
            return Float.isFinite(value) ? Math.clamp(value, MIN_LUCK, MAX_LUCK) : 0;
        } catch (NumberFormatException ignored) { return 0; }
    }

    private void setLuck(float value) {
        float clamped = Math.clamp(value, MIN_LUCK, MAX_LUCK);
        luckBox.setValue(String.format(Locale.ROOT, "%.2f", Math.round(clamped * 100) / 100.0F));
    }

    private void dragLuck(double mouseX) {
        UiRect track = sliderBounds();
        double fraction = Math.clamp((mouseX - track.x() - 3) / (track.width() - 6), 0, 1);
        setLuck((float) (MIN_LUCK + fraction * (MAX_LUCK - MIN_LUCK)));
    }

    // 双向填充以零点为基准；拖动只改草稿，计算仍由确认按钮发起。
    private void renderLuckSlider(GuiGraphics graphics) {
        refreshLuckMarkers();
        UiRect bounds = sliderBounds();
        int left = bounds.x() + 3, width = bounds.width() - 6, y = bounds.y() + 4;
        float range = MAX_LUCK - MIN_LUCK;
        int zero = left + Math.round(-MIN_LUCK / range * width);
        int knob = left + Math.round((sliderLuck() - MIN_LUCK) / range * width);
        graphics.fill(left, y, left + width, y + 2, 0x60977F54);
        graphics.fill(Math.min(zero, knob), y, Math.max(zero, knob) + 1, y + 2, luckColor(draftLuck));
        for (int tick = (int) MIN_LUCK; tick <= MAX_LUCK; tick++) {
            int tx = left + Math.round((tick - MIN_LUCK) / range * width);
            graphics.fill(tx, y + 3, tx + 1, y + (tick == 0 ? 6 : 4), RULE);
        }
        graphics.fill(knob - 3, y - 2, knob + 4, y + 4, ScenarioUi.BRANCH);
        graphics.fill(knob - 2, y - 1, knob + 3, y + 3, 0xFFE6D2A0);
        graphics.fill(knob, y - 1, knob + 1, y + 3, 0xFF977546);
        // 门槛与玩家分居轨道上下方，同值时仍可辨认，且不会被滑块盖住。
        for (LuckMarker marker : luckMarkers) {
            int x = luckMarkerX(bounds, marker.value());
            graphics.fill(x - 1, y - 4, x + 2, y - 1, LUCK_GATE_MARKER);
        }
        if (Float.isFinite(playerLuck)) {
            int x = luckMarkerX(bounds, playerLuck);
            graphics.fill(x, y + 3, x + 1, y + 4, PLAYER_LUCK_MARKER);
            graphics.fill(x - 2, y + 4, x + 3, y + 6, PLAYER_LUCK_MARKER);
        }
    }

    // 目录 DTO 不可变，仅在引用变化时收集并去重；不在每帧遍历分支或重新分析战利品表。
    private void refreshLuckMarkers() {
        CatalogTableDto current = ScenarioSimulationClientState.table(table);
        if (current != markerTable) {
            markerTable = current;
            Set<LuckMarker> markers = new LinkedHashSet<>();
            if (current != null) for (var branch : current.branches()) {
                branch.luck().minLuck().ifPresent(value -> markers.add(new LuckMarker(value, false)));
                branch.luck().bonusRollsGate().ifPresent(value -> markers.add(new LuckMarker(value, true)));
            }
            luckMarkers = markers.stream().filter(marker -> Double.isFinite(marker.value()))
                    .sorted(Comparator.comparingDouble(LuckMarker::value)).toList();
        }
        // getLuck 读取客户端已有的属性缓存；属性同步后下一帧刷新，无网络请求或布局重建。
        var player = Minecraft.getInstance().player;
        playerLuck = player == null ? Float.NaN : player.getLuck();
    }

    private static int luckMarkerX(UiRect bounds, double luck) {
        double fraction = (Math.clamp(luck, MIN_LUCK, MAX_LUCK) - MIN_LUCK) / (MAX_LUCK - MIN_LUCK);
        return bounds.x() + 3 + (int) Math.round(fraction * (bounds.width() - 6));
    }

    // 提示仅由幸运标题触发，滑轨与标记保持无遮挡；拖动经过标题时也不弹出提示。
    private boolean renderLuckTooltip(GuiGraphics graphics, Font font, int mouseX, int mouseY) {
        UiRect title = new UiRect(panelX + PAD + MASCOT_COLUMN + 10, panelY + CARD_TOP + 5,
                font.width(text("params.luck")), font.lineHeight);
        if (draggingLuck || !title.contains(mouseX, mouseY)) return false;
        List<Component> lines = new ArrayList<>();
        lines.add(text("params.luck_description").copy().withStyle(TooltipBuilder.BODY));
        lines.add(text("params.luck_slider").copy().withStyle(TooltipBuilder.HINT));
        if (Float.isFinite(playerLuck)) {
            lines.add(text("params.luck_marker.player", String.format(Locale.ROOT, "%.2f", playerLuck))
                    .copy().withStyle(TooltipBuilder.NAME));
        }
        for (LuckMarker marker : luckMarkers) {
            lines.add(text(marker.bonus() ? "params.luck_marker.bonus" : "params.luck_marker.gate",
                    String.format(Locale.ROOT, "%.2f", marker.value())).copy().withStyle(TooltipBuilder.TITLE));
        }
        graphics.renderTooltip(font, lines, Optional.empty(), mouseX, mouseY);
        return true;
    }

    /*** 同值同类型门槛合并绘制；权重门槛与额外抽取门槛保留独立语义。 */
    private record LuckMarker(double value, boolean bonus) {}

    @Override public void closed() {
        draggingLuck = false;
        dragInput = null;
        focus.clearFocus();
    }

    // 纸面卡片以浅底、细边线与行间分隔承载属性，沿用书本的棕金结构色。
    private void renderAttributeCard(GuiGraphics graphics) {
        UiRect first = attributeBounds(0);
        int left = first.x(), top = first.y(), right = first.right(), bottom = top + CARD_HEIGHT;
        graphics.fill(left, top, right, bottom, 0x107D6846);
        graphics.fill(left, top, right, top + ATTRIBUTE_ROW_HEIGHT, 0x187D6846);
        graphics.fill(left, top, right, top + 1, RULE);
        graphics.fill(left, bottom - 1, right, bottom, RULE);
        graphics.fill(left, top, left + 1, bottom, RULE);
        graphics.fill(right - 1, top, right, bottom, RULE);
        for (int row = 1; row < ATTRIBUTE_ROWS; row++) {
            int y = top + row * ATTRIBUTE_ROW_HEIGHT;
            graphics.fill(left + 8, y, right - 8, y + 1, RULE);
        }
    }

    // 属性标签靠左、读数靠右；工具占用右侧装备槽，不再与标题重复绘制。
    private void renderAttributes(GuiGraphics graphics, Font font) {
        int labelX = panelX + PAD + MASCOT_COLUMN + 10;
        int right = panelX + WIDTH - PAD - 10;
        int configured = 0;
        for (int level : levels.values()) if (level > 0) configured++;

        // 幸运的读数由常驻输入框承担。
        attributeRow(graphics, font, 0, text("params.luck"), Component.literal(draftLuck),
                labelX, right, NO_VALUE);
        attributeRow(graphics, font, 1, text("params.tool"), Component.empty(),
                labelX, right, UiTextPalette.Parchment.NAME);
        // 附魔读数带正负语义：已配置为绿，可配但未选为中性，整表不读取为弱化。
        attributeRow(graphics, font, 2, text("params.attribute.enchantments"),
                enchantments.isEmpty() ? text("params.attribute.unavailable")
                        : text("params.attribute.enchantment_count", configured),
                labelX, right, enchantments.isEmpty() ? UiTextPalette.Parchment.HINT
                        : configured > 0 ? UiTextPalette.Parchment.POSITIVE : UiTextPalette.Parchment.LABEL);
        // 抽样次数恒为签发档位、不可调整，读数压成次要层级。
        attributeRow(graphics, font, 3, text("params.attribute.samples"),
                text("params.attribute.samples_value"), labelX, right, UiTextPalette.Parchment.LABEL);
        int slotX = right - 26, slotY = panelY + CARD_TOP + ATTRIBUTE_ROW_HEIGHT + 4;
        graphics.fill(slotX, slotY, slotX + 20, slotY + 20, RULE);
        graphics.fill(slotX + 1, slotY + 1, slotX + 19, slotY + 19, 0xFFF0E3C4);
        if (!previewTool.isEmpty()) graphics.renderItem(previewTool, slotX + 2, slotY + 2);
        else graphics.drawString(font, "—", slotX + 6, slotY + 6, UiTextPalette.Parchment.HINT, false);
        if (options.toolSelectionAllowed())
            graphics.drawString(font, "▾", right - 4, attributeTextY(font, 1), UiTextPalette.Parchment.LABEL, false);
    }

    private void attributeRow(GuiGraphics graphics, Font font, int index, Component label, Component value,
                              int labelX, int right, int valueColor) {
        int y = index == 0 ? panelY + CARD_TOP + 5 : attributeTextY(font, index);
        graphics.drawString(font, label, labelX, y, UiTextPalette.Parchment.LABEL, false);
        int valueLeft = right - font.width(value);
        if (valueColor != NO_VALUE) {
            graphics.drawString(font, value, valueLeft, y, valueColor, false);
        }
    }

    private int attributeTextY(Font font, int index) {
        return panelY + CARD_TOP + index * ATTRIBUTE_ROW_HEIGHT + (ATTRIBUTE_ROW_HEIGHT - font.lineHeight) / 2;
    }

    private UiRect attributeBounds(int index) {
        return new UiRect(panelX + PAD + MASCOT_COLUMN, panelY + CARD_TOP + index * ATTRIBUTE_ROW_HEIGHT,
                WIDTH - 2 * PAD - MASCOT_COLUMN, ATTRIBUTE_ROW_HEIGHT);
    }

    // 附魔行的入口：把焦点交给下方清单的搜索框，不新开一层界面。
    private void focusEnchantmentSearch() {
        luckBox.setFocused(false);
        searchBox.setFocused(true);
        focus.focusOn(searchFocus);
    }

    // 未聚焦的输入框只留一条淡下划线，读数才不会压过同列的自绘数值。
    private static void renderInput(GuiGraphics graphics, EditBox box, int mouseX, int mouseY, float partialTick) {
        boolean active = box.isFocused() || box.isMouseOver(mouseX, mouseY);
        if (active) {
            graphics.fill(box.getX() - 3, box.getY() - 3, box.getX() + box.getWidth() + 3, box.getY() + 17, 0x30B99A60);
        }
        graphics.fill(box.getX() - 3, box.getY() + 16, box.getX() + box.getWidth() + 3, box.getY() + 17,
                box.isFocused() ? ScenarioUi.BRANCH : active ? 0x60977F54 : 0x28977F54);
        box.render(graphics, mouseX, mouseY, partialTick);
    }

    // 幸运是唯一可正可负的读数，颜色直接表达增益与减益。
    private static int luckColor(String draft) {
        try {
            float luck = Float.parseFloat(draft.trim().replace(',', '.'));
            if (luck > 0) return UiTextPalette.Parchment.POSITIVE;
            if (luck < 0) return UiTextPalette.Parchment.NEGATIVE;
        } catch (NumberFormatException ignored) {
            // 输入中途的半成品不改变配色。
        }
        return UiTextPalette.Parchment.NAME;
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
        dragInput = null;
        if (sliderBounds().contains(mouseX, mouseY)) {
            draggingLuck = true;
            focus.focusOn(luckSlider);
            dragLuck(mouseX);
            return true;
        }
        if (mascot != null && mascot.pet(mouseX, mouseY)) return true;
        controls.mousePressed(mouseX, mouseY, button);
        return true;
    }
    @Override public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        if (toolDropdown != null) return true;
        if (button == 0 && draggingLuck) { dragLuck(mouseX); return true; }
        if (button == 0 && dragInput != null) { dragInput.onClick(mouseX, mouseY); dragInput.setHighlightPos(dragAnchor); }
        return true;
    }
    @Override public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0) { dragInput = null; draggingLuck = false; }
        controls.mouseReleased(button); return true;
    }
    @Override public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        if (toolDropdown != null) { toolDropdown.mouseScrolled(amount); return true; }
        if (listBounds.contains(mouseX, mouseY)) { firstRow -= (int) Math.signum(amount); dirty = true; }
        return true;
    }
    @Override public boolean keyPressed(int key, int scan, int modifiers) {
        layout(Minecraft.getInstance().font);
        if (toolDropdown != null) { toolDropdown.keyPressed(key, modifiers); return true; }
        if (luckSlider.isFocused()) {
            float step = (modifiers & GLFW.GLFW_MOD_SHIFT) != 0 ? 1 : 0.01F;
            if (key == GLFW.GLFW_KEY_LEFT || key == GLFW.GLFW_KEY_RIGHT) {
                setLuck(sliderLuck() + (key == GLFW.GLFW_KEY_RIGHT ? step : -step)); return true;
            }
            if (key == GLFW.GLFW_KEY_HOME || key == GLFW.GLFW_KEY_END) {
                setLuck(key == GLFW.GLFW_KEY_HOME ? MIN_LUCK : MAX_LUCK); return true;
            }
        }
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
    private static Component text(String key, Object... args) { return ScenarioSimulationClientState.text(key, args); }
}
