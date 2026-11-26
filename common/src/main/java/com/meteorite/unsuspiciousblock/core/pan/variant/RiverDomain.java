package com.meteorite.unsuspiciousblock.core.pan.variant;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.LevelReader;

/** 河流生成域按落点所在群系判定。 */
public final class RiverDomain implements SpawnDomain {
    @Override
    public boolean acceptsBiome(LevelReader level, BlockPos pos) {
        return level.getBiome(pos).is(BiomeTags.IS_RIVER);
    }
}
