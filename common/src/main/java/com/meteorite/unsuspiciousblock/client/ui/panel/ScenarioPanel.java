package com.meteorite.unsuspiciousblock.client.ui.panel;

import com.meteorite.unsuspiciousblock.client.state.ScenarioSimulationClientState;
import com.meteorite.unsuspiciousblock.client.ui.JournalBookBackground;
import com.meteorite.unsuspiciousblock.client.ui.kit.TextScroll;
import com.meteorite.unsuspiciousblock.client.ui.support.ArchaeologyJournalClientState;
import com.meteorite.unsuspiciousblock.client.ui.support.ScenarioLabel;
import com.meteorite.unsuspiciousblock.client.ui.support.ScenarioPresentation;
import com.meteorite.unsuspiciousblock.client.ui.support.UiTextPalette;
import com.meteorite.unsuspiciousblock.client.state.SimulationPreferenceStore;
import com.meteorite.unsuspiciousblock.loottable.catalog.SimulationOptions;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import static com.meteorite.unsuspiciousblock.client.state.ScenarioSimulationClientState.text;

/**
 * 网格页头部：读数行（场景 · 参数 · 状态）与「切换场景 / 计算」两个动作。
 *
 * <p>场景列表不再由本类自绘：切换按钮打开与场景页共用的 {@link ScenarioSelectionOverlay}，
 * 因此两页使用同一套选择交互与同一份文案。
 * 头部文字超宽时悬停滚动，不做静默截断。</p>
 */
public final class ScenarioPanel {
    /** 动作行相对头部起点的偏移：读数行占 12 像素。 */
    private static final int ACTION_TOP = 12;
    private static final int ACTION_HEIGHT = 16;
    /** 读数行与动作行之间的悬停判定高度，比行高略宽以便命中。 */
    private static final int SUMMARY_HEIGHT = 12;

    private final int x, y, width;
    @Nullable private ResourceLocation table;
    @Nullable private Runnable openScenes;
    private List<Component> tooltip = List.of();
    private int summaryTicks, switchTicks;
    /** 场景显示名的缓存键与值：头部每帧绘制，而标签要遍历假设树，不能逐帧重建。 */
    private String labelKey = "";
    private Component label = Component.empty();
    /** 读数行与「切换场景」按钮文案的缓存键与值（键 = 表 + 选择输入 + 状态 + 目录版本 + 语言）。 */
    private String headerKey = "";
    private Component summary = Component.empty();
    private Component switchLabel = Component.empty();
    // 已测宽度缓存（P-10）：代 = 语言 + Font 实例，与文案缓存同一失效事件（见 PanelTextMetrics）
    private final PanelTextMetrics metrics = new PanelTextMetrics();

    public ScenarioPanel(JournalBookBackground.BookLayout layout) {
        x = layout.rightPageX() + 4;
        y = layout.rightPageY() + 6;
        width = layout.rightPageWidth() - 8;
    }

    public void setTable(ResourceLocation value) {
        if (Objects.equals(table, value)) return;
        table = value;
        summaryTicks = 0;
        switchTicks = 0;
    }

    /** 由容器注入：打开共享的场景列表浮层。 */
    public void setOpenScenes(@Nullable Runnable value) { this.openScenes = value; }

    private int switchWidth() { return width - 42; }

    int calculateX() { return x + width - 38; }

    public void renderHeader(GuiGraphics graphics, Font font, int mouseX, int mouseY) {
        this.metrics.beginFrame(font);
        tooltip = List.of();
        var choice = ScenarioSimulationClientState.selection(table);
        if (choice == null) return;
        var dto = ScenarioSimulationClientState.table(table);
        String input = ScenarioSimulationClientState.inputKey(choice);
        String status = ScenarioPresentation.resolve(table, choice.scene(), choice.params()).status();
        Component scene = dto == null || dto.options() == null
                ? Component.literal(choice.scene())
                : sceneLabel(dto.options(), choice.scene());
        refreshHeaderText(input, choice, status, scene);
        Component summary = this.summary;
        boolean summaryHover = inside(mouseX, mouseY, x, y, width, SUMMARY_HEIGHT);
        summaryTicks = summaryHover ? summaryTicks + 1 : 0;
        int summaryWidth = this.metrics.measure(summary.getString(), font).width();
        drawScrolling(graphics, font, summary, summaryWidth, x, y, width, summaryHover, summaryTicks);
        if (summaryHover) {
            List<Component> lines = new ArrayList<>();
            lines.add(summary);
            lines.add(text("tool", toolName(choice.params().toolId())));
            choice.params().toolEnchantments().forEach((id, level) -> lines.add(text("level", enchantName(id), level)));
            if (status.equals("failed")) lines.add(text("failure." + ScenarioSimulationClientState.failure(table, input)));
            lines.addAll(ScenarioSimulationClientState.notes());
            tooltip = lines;
        }
        boolean switchHover = inside(mouseX, mouseY, x, y + ACTION_TOP, switchWidth(), ACTION_HEIGHT);
        switchTicks = switchHover ? switchTicks + 1 : 0;
        drawButton(graphics, font, this.switchLabel, x, y + ACTION_TOP, switchWidth(), switchHover, switchTicks);
        boolean calculateHover = inside(mouseX, mouseY, calculateX(), y + ACTION_TOP, 38, ACTION_HEIGHT);
        drawButton(graphics, font, text("calculate"), calculateX(), y + ACTION_TOP, 38, calculateHover, 0);
    }

    public boolean click(double mx, double my, int button) {
        if (button != 0 || table == null) return false;
        var dto = ScenarioSimulationClientState.table(table);
        if (dto == null || dto.options() == null) return false;
        if (inside(mx, my, x, y + ACTION_TOP, switchWidth(), ACTION_HEIGHT)) {
            if (openScenes != null) openScenes.run();
            return true;
        }
        if (inside(mx, my, calculateX(), y + ACTION_TOP, 38, ACTION_HEIGHT)) {
            ScenarioSimulationClientState.request(table, true);
            return true;
        }
        return false;
    }

    /** 头部自己的悬停提示（读数行已展开工具/附魔/失败原因）。 */
    public List<Component> tooltip() { return tooltip; }

    // 场景显示名按「表 + 场景 + 目录版本 + 语言」缓存：头部每帧绘制，标签却要遍历假设树。
    private Component sceneLabel(SimulationOptions options, String sceneKey) {
        String key = table + "#" + sceneKey + "#" + ArchaeologyJournalClientState.getCatalogRevision()
                + "#" + language();
        if (!key.equals(labelKey)) {
            labelKey = key;
            label = ScenarioLabel.label(options, sceneKey);
        }
        return label;
    }

    // 读数行与「切换场景」按钮文案按 key 缓存（P-12）：头部每帧绘制，不能逐帧 format/translatable 解析。
    // 失效点 = 表 / 选择输入 / 状态 / 目录版本 / 语言 任一变（键覆盖全部输入）。
    private void refreshHeaderText(String input, SimulationPreferenceStore.Selection choice,
                                   String status, Component scene) {
        String key = table + "#" + input + "#" + status + "#"
                + ArchaeologyJournalClientState.getCatalogRevision() + "#" + language();
        if (key.equals(this.headerKey)) {
            return;
        }
        this.headerKey = key;
        var params = choice.params();
        this.summary = text("summary", scene, format(params.luck()), params.sampleCount(), text(status));
        this.switchLabel = text("switch", scene);
    }

    // 语言代码：翻译结果随它变化，是文本缓存的失效键之一。
    private static String language() {
        return Minecraft.getInstance().getLanguageManager().getSelected();
    }

    private void drawButton(GuiGraphics graphics, Font font, Component label, int bx, int by, int bw,
                            boolean hover, int ticks) {
        graphics.fill(bx, by, bx + bw, by + ACTION_HEIGHT, hover ? 0x55A3875B : 0x22896C48);
        // 按钮文字宽度按 P-10 缓存传入，绘制路径不再逐帧 font.width
        drawScrolling(graphics, font, label, this.metrics.measure(label.getString(), font).width(),
                bx + 3, by + 4, bw - 6, hover, ticks);
    }

    // 头部文字一律走悬停滚动：超宽时不再被静默截断；宽度由调用方按 P-10 缓存后传入。
    private static void drawScrolling(GuiGraphics graphics, Font font, Component label, int textWidth,
                                      int tx, int ty, int maxWidth, boolean hovered, int ticks) {
        TextScroll.draw(graphics, font, label.getVisualOrderText(), textWidth,
                tx, ty, maxWidth, UiTextPalette.Parchment.NAME, hovered, ticks);
    }

    private static boolean inside(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private static Component toolName(ResourceLocation id) {
        return new net.minecraft.world.item.ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.get(id))
                .getHoverName();
    }

    private static Component enchantName(ResourceLocation id) {
        var level = Minecraft.getInstance().level;
        if (level != null) {
            var found = level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT)
                    .get(net.minecraft.resources.ResourceKey.create(
                            net.minecraft.core.registries.Registries.ENCHANTMENT, id));
            if (found.isPresent()) return found.get().value().description();
        }
        return Component.literal(id.toString());
    }

    private static String format(float value) { return String.format(Locale.ROOT, "%.2f", value); }
}
