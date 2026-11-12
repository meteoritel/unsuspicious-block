package com.meteorite.unsuspiciousblock.client.ui.support;

import com.meteorite.meteosuikit.api.render.UiIcon;
import com.meteorite.unsuspiciousblock.client.state.ScenarioSimulationClientState;
import com.meteorite.unsuspiciousblock.client.ui.theme.UiAtlas;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.CatalogTableDto;
import com.meteorite.unsuspiciousblock.core.loottable.simulation.ScenarioParams;
import com.meteorite.unsuspiciousblock.core.loottable.simulation.SimulationInputKey;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** 场景页和网格顶栏共享的显示口径：精确解析当前条件勾选集合与参数对应的本地结果与状态。 */
public record ScenarioPresentation(@Nullable CatalogTableDto source, boolean exactInput, String status) {
    /** 解析指定表、勾选条件集合与参数对应的场景展示快照。 */
    public static ScenarioPresentation resolve(ResourceLocation table, List<String> conditions, ScenarioParams params) {
        String inputKey = SimulationInputKey.of(conditions, params);
        CatalogTableDto direct = ScenarioSimulationClientState.result(table, inputKey);
        if (direct != null) {
            return new ScenarioPresentation(direct, true, "cached");
        }
        return new ScenarioPresentation(null, false, ScenarioSimulationClientState.status(table, inputKey));
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
