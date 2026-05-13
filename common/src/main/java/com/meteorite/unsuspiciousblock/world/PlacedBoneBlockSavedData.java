package com.meteorite.unsuspiciousblock.world;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.NotNull;

/** 玩家放置骨块标记：仅记录玩家放置位置，未记录的位置默认视为自然生成。 */
public final class PlacedBoneBlockSavedData extends SavedData {
    private static final String FILE_NAME = "unsuspiciousblock_placed_bone_blocks";
    private static final String TAG_POSITIONS = "positions";
    private static final SavedData.Factory<PlacedBoneBlockSavedData> FACTORY = new SavedData.Factory<>(
            PlacedBoneBlockSavedData::new,
            PlacedBoneBlockSavedData::load,
            DataFixTypes.LEVEL
    );

    private final LongSet positions = new LongOpenHashSet();

    public static PlacedBoneBlockSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, FILE_NAME);
    }

    private static PlacedBoneBlockSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        PlacedBoneBlockSavedData data = new PlacedBoneBlockSavedData();
        for (long packedPos : tag.getLongArray(TAG_POSITIONS)) {
            data.positions.add(packedPos);
        }
        return data;
    }

    @Override
    public @NotNull CompoundTag save(CompoundTag tag, HolderLookup.@NotNull Provider registries) {
        tag.putLongArray(TAG_POSITIONS, this.positions.toLongArray());
        return tag;
    }

    public void markPlaced(BlockPos pos) {
        if (this.positions.add(pos.asLong())) {
            this.setDirty();
        }
    }

    public boolean consumePlaced(BlockPos pos) {
        return this.clearPlaced(pos);
    }

    public boolean movePlaced(BlockPos from, BlockPos to) {
        if (!this.positions.remove(from.asLong())) {
            return false;
        }
        this.positions.add(to.asLong());
        this.setDirty();
        return true;
    }

    public boolean clearPlaced(BlockPos pos) {
        if (!this.positions.remove(pos.asLong())) {
            return false;
        }
        this.setDirty();
        return true;
    }
}
