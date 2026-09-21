package com.meteorite.unsuspiciousblock.client.ui.panel;

import com.meteorite.unsuspiciousblock.client.state.*;
import com.meteorite.unsuspiciousblock.client.ui.JournalBookBackground;
import com.meteorite.unsuspiciousblock.loottable.simulation.*;
import com.meteorite.unsuspiciousblock.loottable.catalog.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.*;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import java.util.*;
import static com.meteorite.unsuspiciousblock.client.state.ScenarioSimulationClientState.text;

/** 场景页参数、分页条件卡片与网格快捷下拉；控件坐标均为书本逻辑坐标。 */
public final class ScenarioPanel implements PagePanel {
    private final int x, y, width;
    private final Runnable showGrid;
    private ResourceLocation table;
    private int page, dropdownPage;
    private boolean dropdown, invalidLuck;
    private EditBox luckBox;
    private String observedInput = "";
    private long changedAt;
    private boolean sent;
    private List<Row> rows = List.of();
    private long rowsRevision = -1;
    private List<Component> lastNotes;
    private static final int ROWS = 8, STEP = 18;
    private List<Component> tooltip = List.of();

    /** 一行可点击的参数或场景；详细内容悬停查看。 */
    private record Row(Component label, List<Component> details, Runnable action, boolean luck) {}

    public ScenarioPanel(JournalBookBackground.BookLayout layout, Runnable showGrid) {
        x = layout.rightPageX() + 4; y = layout.rightPageY() + 6;
        width = layout.rightPageWidth() - 8;
        this.showGrid = showGrid;
    }

    public void setTable(ResourceLocation value) {
        if (Objects.equals(table, value)) return;
        table = value; page = 0; dropdown = false; luckBox = null; observedInput = "";
        invalidLuck = false; rowsRevision = -1;
    }

    public boolean dropdownOpen() { return dropdown; }

    public boolean focused() { return luckBox != null && luckBox.isFocused(); }
    public boolean keyPressed(int key, int scan, int modifiers) {
        return focused() && luckBox.keyPressed(key, scan, modifiers);
    }
    public boolean charTyped(char value, int modifiers) {
        return focused() && luckBox.charTyped(value, modifiers);
    }

    public void tick() {
        var choice = ScenarioSimulationClientState.selection(table);
        if (choice == null) return;
        String key = ScenarioSimulationClientState.inputKey(choice);
        if (!key.equals(observedInput)) {
            observedInput = key; changedAt = System.currentTimeMillis(); sent = false;
            if (luckBox != null && !luckBox.isFocused()) luckBox.setValue(format(choice.params().luck()));
        }
        if (!sent && !invalidLuck && System.currentTimeMillis() - changedAt >= 500) {
            sent = true; ScenarioSimulationClientState.request(table, false);
        }
    }

    public void renderHeader(GuiGraphics graphics, Font font, int mouseX, int mouseY) {
        tooltip = List.of();
        tick();
        var choice = ScenarioSimulationClientState.selection(table);
        if (choice == null) return;
        String input = ScenarioSimulationClientState.inputKey(choice);
        String status = ScenarioSimulationClientState.status(table, input);
        Component summary = text("summary", sceneName(choice.scene()), format(choice.params().luck()),
                choice.params().sampleCount(), text(status));
        drawLabel(graphics, font, summary, x, y, width);
        if (inside(mouseX, mouseY, x, y, width, 10)) {
            List<Component> lines = new ArrayList<>();
            lines.add(summary);
            lines.add(text("tool", toolName(choice.params().toolId())));
            choice.params().toolEnchantments().forEach((id, level) -> lines.add(text("level", enchantName(id), level)));
            if (status.equals("failed")) lines.add(text("failure." + ScenarioSimulationClientState.failure(table, input)));
            lines.addAll(ScenarioSimulationClientState.notes());
            tooltip = lines;
        }
        drawButton(graphics, font, text("switch", sceneName(choice.scene())), x, y + 12, width - 40,
                inside(mouseX, mouseY, x, y + 12, width - 40, 16));
        drawButton(graphics, font, text("calculate"), x + width - 38, y + 12, 38,
                inside(mouseX, mouseY, x + width - 38, y + 12, 38, 16));
    }

    @Override public void render(GuiGraphics graphics, Font font, int mouseX, int mouseY) {
        rebuildRows();
        if (rows.isEmpty()) { drawLabel(graphics, font, text("unavailable"), x, y + 34, width); return; }
        page = Math.min(page, pageCount() - 1);
        for (int n = 0; n < ROWS; n++) {
            int index = page * ROWS + n;
            if (index >= rows.size()) break;
            Row row = rows.get(index); int rowY = y + 34 + n * STEP;
            boolean hover = inside(mouseX, mouseY, x, rowY, width, 16);
            if (row.luck()) {
                drawLabel(graphics, font, text(invalidLuck ? "luck_invalid" : "luck"), x, rowY + 4, 86);
                if (luckBox == null) {
                    luckBox = new EditBox(font, x + 88, rowY, width - 88, 16, text("luck"));
                    luckBox.setMaxLength(7);
                    luckBox.setValue(format(ScenarioSimulationClientState.selection(table).params().luck()));
                    luckBox.setResponder(this::setLuck);
                }
                luckBox.setY(rowY);
                luckBox.render(graphics, mouseX, mouseY, 0);
            } else drawButton(graphics, font, row.label(), x, rowY, width, hover);
            if (hover) tooltip = row.details().isEmpty() ? List.of(row.label()) : row.details();
        }
    }

    private void rebuildRows() {
        long revision = com.meteorite.unsuspiciousblock.client.ui.support.ArchaeologyJournalClientState.getCatalogRevision();
        if (rowsRevision == revision && lastNotes == ScenarioSimulationClientState.notes()) return;
        rowsRevision = revision;
        lastNotes = ScenarioSimulationClientState.notes();
        var dto = ScenarioSimulationClientState.table(table);
        var choice = ScenarioSimulationClientState.selection(table);
        if (dto == null || dto.options() == null || choice == null) { rows = List.of(); return; }
        var options = dto.options(); var p = choice.params();
        List<Row> list = new ArrayList<>();
        list.add(new Row(text("luck"), List.of(text("luck_help")), null, true));
        List<Float> suggestions = new ArrayList<>(List.of(0F, 1F, 3F, 5F, 10F));
        for (var item : dto.items()) for (var path : item.acquisitionPaths())
            if (path.luckGate() != null && path.luckGate().minLuck().isPresent()) {
                float gate = (float) path.luckGate().minLuck().getAsDouble();
                if (!suggestions.contains(gate)) suggestions.add(gate);
            }
        suggestions.sort(Float::compare);
        list.add(row(text("suggestion", suggestions.toString()), () -> {
            float next = suggestions.stream().filter(v -> v > p.luck()).findFirst().orElse(0F);
            setLuck(format(next)); if (luckBox != null) luckBox.setValue(format(next));
        }));
        var currentTool = options.tools().stream().filter(t -> t.id().equals(p.toolId())).findFirst().orElse(options.tools().getFirst());
        List<Component> toolDetails = new ArrayList<>(List.of(text("tool_help"), currentTool.displayName()));
        if (currentTool.predicateText() != null) toolDetails.add(currentTool.predicateText());
        list.add(new Row(text("tool", currentTool.displayName()), toolDetails, () -> {
            int index = options.tools().indexOf(currentTool);
            change(new ScenarioParams(p.luck(), options.tools().get((index + 1) % options.tools().size()).id(),
                    p.toolEnchantments(), p.sampleCount()));
        }, false));
        for (var id : options.enchantments().keySet().stream().sorted(Comparator.comparing(ResourceLocation::toString)).toList()) {
            int level = p.toolEnchantments().getOrDefault(id, 0), max = options.enchantments().get(id);
            list.add(row(text("level", enchantName(id), level), () -> {
                Map<ResourceLocation, Integer> levels = new LinkedHashMap<>(p.toolEnchantments());
                levels.put(id, (level + 1) % (max + 1));
                change(new ScenarioParams(p.luck(), p.toolId(), levels, p.sampleCount()));
            }));
        }
        list.add(row(text("samples", p.sampleCount()), () -> change(new ScenarioParams(p.luck(), p.toolId(),
                p.toolEnchantments(), options.samples().get((options.samples().indexOf(p.sampleCount()) + 1) % options.samples().size())))));
        list.add(row(text("probe"), () -> ScenarioSimulationClientState.requestAssist(table, "")));
        if (!ScenarioSimulationClientState.notes().isEmpty())
            list.add(new Row(text("notes", ScenarioSimulationClientState.notes().size()),
                    ScenarioSimulationClientState.notes(), null, false));

        if (options.truncated() > 0 || options.budgetExhausted())
            list.add(new Row(text("degraded", options.truncated()), List.of(text("degraded_help")), null, false));
        for (var scene : options.scenes()) {
            String key = new SimulationInput(scene.scenarioKey(), Map.of(), p).key();
            List<Component> details = new ArrayList<>();
            details.add(text("assumptions"));
            if (scene.assumptions().isEmpty()) details.add(text("no_assumptions"));
            else scene.assumptions().forEach(c -> details.add(c.description()));
            list.add(new Row(text("scene_status", sceneName(scene.scenarioKey()),
                    text(ScenarioSimulationClientState.status(table, key))), details, () -> choose(scene.scenarioKey()), false));
        }
        for (var item : dto.items()) {
            var progress = com.meteorite.unsuspiciousblock.client.ui.support.ArchaeologyJournalClientState.getState().getTable(table);
            var itemProgress = progress == null ? null : progress.getItemProgress(item.signature());
            if (item.acquisitionPaths().isEmpty() || itemProgress == null || !itemProgress.isUnlocked()) continue;
            List<Component> details = new ArrayList<>(List.of(text("find_help"), item.displayName()));
            for (var path : item.acquisitionPaths()) {
                path.allConditions().forEach(c -> details.add(c.description()));
                if (path.luckGate() != null && path.luckGate().minLuck().isPresent())
                    details.add(text("luck_gate", format((float) path.luckGate().minLuck().getAsDouble())));
            }
            list.add(new Row(text("find", item.displayName()), details,
                    () -> ScenarioSimulationClientState.requestAssist(table, item.signature().toStoredKey()), false));
        }
        rows = List.copyOf(list);
    }

    private Row row(Component label, Runnable action) { return new Row(label, List.of(), action, false); }
    private void setLuck(String value) {
        try {
            float luck = Float.parseFloat(value);
            var p = ScenarioSimulationClientState.selection(table).params();
            change(new ScenarioParams(luck, p.toolId(), p.toolEnchantments(), p.sampleCount()));
            invalidLuck = false;
        } catch (RuntimeException e) { invalidLuck = true; }
    }
    private void change(ScenarioParams params) {
        ScenarioSimulationClientState.select(table, ScenarioSimulationClientState.selection(table).scene(), params);
    }
    private void choose(String scene) {
        var p = ScenarioSimulationClientState.selection(table).params();
        ScenarioSimulationClientState.select(table, scene, p);
        dropdown = false; showGrid.run();
    }

    public boolean click(double mx, double my, int button, boolean scenarioTab) {
        var dto = ScenarioSimulationClientState.table(table);
        if (dto == null || dto.options() == null) return false;
        if (ScenarioSimulationClientState.hasRecommendation(table) && inside(mx, my, x, y + 192, width, 16)) {
            ScenarioSimulationClientState.applyRecommendation(table); luckBox = null; showGrid.run(); return true;
        }
        if (dropdown) {
            var scenes = dto.options().scenes();
            for (int n = 0; n < 7; n++) {
                int index = dropdownPage * 7 + n;
                if (index < scenes.size() && inside(mx, my, x, y + 30 + n * 18, width, 18)) {
                    choose(scenes.get(index).scenarioKey()); return true;
                }
            }
            if (inside(mx, my, x, y + 156, width, 18)) {
                dropdownPage = (dropdownPage + 1) % Math.max(1, (scenes.size() + 6) / 7); return true;
            }
            dropdown = false; return true;
        }
        if (inside(mx, my, x, y + 12, width - 40, 16)) { dropdown = true; dropdownPage = 0; return true; }
        if (inside(mx, my, x + width - 38, y + 12, 38, 16)) {
            if (!invalidLuck) ScenarioSimulationClientState.request(table, true); return true;
        }
        if (!scenarioTab) return false;
        if (luckBox != null) {
            luckBox.setFocused(luckBox.isMouseOver(mx, my) && page == 0);
            if (luckBox.isFocused()) return luckBox.mouseClicked(mx, my, button);
        }
        int row = (int) ((my - y - 34) / STEP);
        int index = page * ROWS + row;
        if (my >= y + 34 && row < ROWS && inside(mx, my, x, y + 34, width, ROWS * STEP) && index < rows.size()) {
            Runnable action = rows.get(index).action();
            if (action != null) action.run();
            return true;
        }
        return false;
    }

    public void renderOverlay(GuiGraphics graphics, Font font, int mx, int my) {
        if (ScenarioSimulationClientState.hasRecommendation(table))
            drawButton(graphics, font, text("apply"), x, y + 192, width, inside(mx, my, x, y + 192, width, 16));
        if (dropdown) {
            var dto = ScenarioSimulationClientState.table(table);
            if (dto == null || dto.options() == null) return;
            var p = ScenarioSimulationClientState.selection(table).params();
            var scenes = dto.options().scenes();
            graphics.pose().pushPose(); graphics.pose().translate(0, 0, 200);
            graphics.fill(x - 1, y + 29, x + width + 1, y + 176, 0xFFF2E5C6);
            for (int n = 0; n < 7; n++) {
                int index = dropdownPage * 7 + n;
                if (index >= scenes.size()) break;
                var scene = scenes.get(index);
                String key = new SimulationInput(scene.scenarioKey(), Map.of(), p).key();
                drawButton(graphics, font, text("scene_status", sceneName(scene.scenarioKey()),
                        text(ScenarioSimulationClientState.status(table, key))),
                        x, y + 30 + n * 18, width, inside(mx, my, x, y + 30 + n * 18, width, 18));
            }
            drawButton(graphics, font, text("more"), x, y + 156, width, false);
            graphics.pose().popPose();
        } else if (!tooltip.isEmpty())
            graphics.renderComponentTooltip(font, tooltip, mx, my);
    }
    private static Component toolName(ResourceLocation id) {
        return new net.minecraft.world.item.ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.get(id)).getHoverName();
    }
    private static Component enchantName(ResourceLocation id) {
        var level = Minecraft.getInstance().level;
        if (level != null) {
            var found = level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT)
                    .get(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.ENCHANTMENT, id));
            if (found.isPresent()) return found.get().value().description();
        }
        return Component.literal(id.toString());
    }
    private static Component sceneName(String scene) {
        return scene.equals("baseline") ? text("baseline") : text("scene", scene.substring(scene.lastIndexOf('-') + 1));
    }
    private static String format(float value) { return String.format(Locale.ROOT, "%.2f", value); }
    private static boolean inside(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }
    private static void drawLabel(GuiGraphics g, Font f, Component c, int x, int y, int width) {
        g.drawString(f, f.plainSubstrByWidth(c.getString(), width), x, y, 0xFF3A2A1A, false);
    }
    private static void drawButton(GuiGraphics g, Font f, Component c, int x, int y, int w, boolean hover) {
        g.fill(x, y, x + w, y + 16, hover ? 0x55A3875B : 0x22896C48);
        drawLabel(g, f, c, x + 3, y + 4, w - 6);
    }
    @Override public boolean containsMouse(double mx, double my) { return inside(mx, my, x, y, width, 198); }
    @Override public int pageCount() { rebuildRows(); return Math.max(1, (rows.size() + ROWS - 1) / ROWS); }
    @Override public int getPage() { return page; }
    @Override public void setPage(int value) { page = Math.clamp(value, 0, pageCount() - 1); if (luckBox != null) luckBox.setFocused(false); }
    @Override public void changePage(int delta) { setPage(page + delta); }
}

