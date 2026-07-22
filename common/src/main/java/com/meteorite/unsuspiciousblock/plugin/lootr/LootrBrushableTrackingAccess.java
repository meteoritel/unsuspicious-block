package com.meteorite.unsuspiciousblock.plugin.lootr;

import net.minecraft.resources.ResourceLocation;

/**
 * DefaultBrushableLootFiller 向 Lootr 可疑方块同步已解析 loot table 的内部桥接接口。
 */
public interface LootrBrushableTrackingAccess {
    void unsuspiciousblock$recordResolvedLootTable(ResourceLocation tableId);
}
