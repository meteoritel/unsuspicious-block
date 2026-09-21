package com.meteorite.unsuspiciousblock.client.ui.panel;

import com.meteorite.unsuspiciousblock.client.state.ScenarioSimulationClientState;
import com.meteorite.unsuspiciousblock.client.state.SimulationPreferenceStore;
import com.meteorite.unsuspiciousblock.client.ui.JournalBookBackground.BookLayout;
import com.meteorite.unsuspiciousblock.client.ui.layout.LayoutAware;
import com.meteorite.unsuspiciousblock.client.ui.support.UiStateful;
import com.meteorite.unsuspiciousblock.loottable.catalog.CatalogTableDto;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/** 场景详情页：目前只接管场景 tab 的路由、分页与命中，内容绘制待接入 kit。 */
public final class ScenarioDetailPanel implements PagePanel, LayoutAware, UiStateful {
    private BookLayout layout;
    @Nullable private ResourceLocation table;

    public ScenarioDetailPanel(BookLayout layout) {
        this.layout = layout;
    }

    @Override public void applyLayout(BookLayout layout) { this.layout = layout; }

    public void setTable(@Nullable ResourceLocation value) {
        if (Objects.equals(table, value)) return;
        table = value;
    }

    @Override public void render(GuiGraphics graphics, Font font, int mouseX, int mouseY) {
    }

    public void renderTooltip(GuiGraphics graphics, Font font, int x, int y) {
    }

    public boolean mouseClicked(double x, double y, int button) { return false; }

    public boolean mouseDragged(double x, double y, int button) { return false; }

    public boolean mouseReleased(int button) { return false; }

    @Override public boolean containsMouse(double x, double y) {
        return x >= layout.rightPageX() && x < layout.rightPageRight()
                && y >= layout.rightPageY() && y < layout.rightPageBottom();
    }

    List<CatalogTableDto.ScenarioAssumptions> scenes() {
        CatalogTableDto dto = structure();
        return dto == null || dto.options() == null ? List.of() : dto.options().scenes();
    }

    @Nullable CatalogTableDto structure() { return table == null ? null : ScenarioSimulationClientState.table(table); }

    @Nullable SimulationPreferenceStore.Selection selection() {
        return table == null ? null : ScenarioSimulationClientState.selection(table);
    }

    @Override public int pageCount() { return Math.max(1, scenes().size()); }

    @Override public int getPage() {
        var selected = selection();
        List<CatalogTableDto.ScenarioAssumptions> scenes = scenes();
        if (selected != null) for (int i = 0; i < scenes.size(); i++) {
            if (scenes.get(i).scenarioKey().equals(selected.scene())) return i;
        }
        return 0;
    }

    @Override public void setPage(int page) {
        var selected = selection();
        var scenes = scenes();
        if (table == null || selected == null || scenes.isEmpty()) return;
        ScenarioSimulationClientState.select(table, scenes.get(Math.clamp(page, 0, scenes.size() - 1)).scenarioKey(), selected.params());
    }

    @Override public void changePage(int delta) { setPage(getPage() + delta); }

    @Override public void saveUiState(CompoundTag tag) {
    }

    @Override public void loadUiState(CompoundTag tag) {
    }
}
