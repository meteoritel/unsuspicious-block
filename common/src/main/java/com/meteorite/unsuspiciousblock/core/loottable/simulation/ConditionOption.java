package com.meteorite.unsuspiciousblock.core.loottable.simulation;

import com.meteorite.unsuspiciousblock.core.loottable.analysis.LootConditionInfo;

import java.util.Objects;

/**
 * 战利品表中可由玩家自由勾选的单个原子场景条件。
 *
 * @param id        条件的确定性语义身份键（{@code ConditionIdentity.key()}）
 * @param condition 条件静态分析描述（含本地化文本与保真度元数据）
 */
public record ConditionOption(String id, LootConditionInfo condition) {
    public ConditionOption {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("条件选项 id 不可为空");
        }
        Objects.requireNonNull(condition, "condition");
    }
}
