package com.meteorite.unsuspiciousblock.client.ui.support;

import com.meteorite.meteosuikit.api.render.UiIcon;
import com.meteorite.unsuspiciousblock.client.state.ScenarioSimulationClientState;
import com.meteorite.unsuspiciousblock.client.ui.theme.UiAtlas;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.CatalogTableDto;
import com.meteorite.unsuspiciousblock.core.loottable.simulation.ScenarioParams;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/** 场景页和下拉共享的显示口径：同参数的明确场景引用可以复用，禁止借用其它场景的总概率。 */
public record ScenarioPresentation(@Nullable CatalogTableDto source, boolean exactInput, String status) {
    /** 解析指定表与输入对应的场景展示快照。 */
    public static ScenarioPresentation resolve(ResourceLocation table, String scene, ScenarioParams params) {
        var resolved = ScenarioSimulationClientState.sceneSource(table, scene, params);
        return new ScenarioPresentation(resolved.source(), resolved.exactInput(), resolved.status());
    }

    /** 返回当前场景状态对应的 12×12 图集角标。 */
    public UiIcon badge() {
        return badge(status);
    }

    /** 根据状态字符串返回统一图集中的 12×12 状态角标。 */
    public static UiIcon badge(String status) {
        return UiAtlas.statusBadge(status);
    }
}
