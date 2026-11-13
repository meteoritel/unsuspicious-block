package com.meteorite.unsuspiciousblock.client.state;

import com.meteorite.unsuspiciousblock.core.loottable.catalog.CatalogTableDto;
import com.meteorite.unsuspiciousblock.core.network.payload.ScenarioSelection;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;

/**
 * 单表阅读所需的完整值快照；目标展示和交互状态都有所属表的变化身份。
 */
public record ScenarioTableSnapshot(CatalogTableDto structure, SimulationPreferenceStore.Selection choice,
                                    @Nullable CatalogTableDto measurement, String inputKey, String status,
                                    String failure, List<Component> notes, boolean assistPending,
                                    @Nullable ScenarioSelection recommendation, Map<String, Target> targets,
                                    long revision) {
    public ScenarioTableSnapshot {
        notes = List.copyOf(notes);
        targets = Map.copyOf(targets);
    }

    /**
     * 未发现目标的快照不携带已揭示名称。
     */
    public record Target(boolean visible, Component name) {
    }

    /**
     * 读取目标展示；缺少展示资料时按未发现处理。
     */
    public Target target(String kind, String value) {
        return targets.getOrDefault(kind + "#" + value, new Target(false, Component.empty()));
    }
}
