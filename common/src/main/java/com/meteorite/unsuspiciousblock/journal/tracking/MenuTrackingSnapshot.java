package com.meteorite.unsuspiciousblock.journal.tracking;

import com.meteorite.unsuspiciousblock.blockentity.TrackedContainerLootState;

import java.util.List;
import java.util.Map;

/**
 * 容器菜单打开前后的物品追踪快照。
 * 记录快照涉及的追踪容器、玩家物品栏与光标中相关物品的计数，
 * 用于在菜单操作后通过增量结算真正进入背包的战利品。
 */
public record MenuTrackingSnapshot(List<TrackedContainerLootState> trackedContainers,
                                   Map<String, Integer> beforeInventoryCounts,
                                   Map<String, Integer> beforeCarriedCounts) {
    public MenuTrackingSnapshot {
        trackedContainers = List.copyOf(trackedContainers);
        beforeInventoryCounts = Map.copyOf(beforeInventoryCounts);
        beforeCarriedCounts = Map.copyOf(beforeCarriedCounts);
    }
}
