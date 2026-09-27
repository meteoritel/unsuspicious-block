package com.meteorite.unsuspiciousblock.client.ui.support;

import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.client.state.ScenarioSimulationClientState;
import com.meteorite.unsuspiciousblock.client.ui.kit.UiIcon;
import com.meteorite.unsuspiciousblock.loottable.catalog.CatalogTableDto;
import com.meteorite.unsuspiciousblock.loottable.simulation.ScenarioParams;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;


/** 场景页和下拉共享的显示口径：同参数的明确场景引用可以复用，禁止借用其它场景的总概率。 */
public record ScenarioPresentation(@Nullable CatalogTableDto source, boolean exactInput, String status) {
    private static final ResourceLocation BADGES = ResourceLocation.fromNamespaceAndPath(
            Constants.MOD_ID, "textures/gui/scenario_status.png");

    public static ScenarioPresentation resolve(ResourceLocation table, String scene, ScenarioParams params) {
        var resolved = ScenarioSimulationClientState.sceneSource(table, scene, params);
        return new ScenarioPresentation(resolved.source(), resolved.exactInput(), resolved.status());
    }

    public UiIcon badge() { return badge(status); }

    public static UiIcon badge(String status) {
        int slot = switch (status) { case "pending" -> 1; case "cached" -> 2; case "failed" -> 3; default -> 0; };
        return new UiIcon.Sprite(BADGES, slot * 12, 0, 12, 12, 48, 12);
    }
}
