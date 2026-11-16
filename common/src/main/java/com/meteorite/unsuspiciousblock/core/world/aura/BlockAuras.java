package com.meteorite.unsuspiciousblock.core.world.aura;

import com.meteorite.unsuspiciousblock.core.block.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.phys.Vec3;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

/** 服务端世界与光环场的生命周期接入，所有查询只消费内存索引。 */
public final class BlockAuras {
    private static final int PURRMINT_RADIUS = 8;
    private static final Set<AuraEffect> PURRMINT_EFFECTS = Set.of(AuraEffect.MONSTER_REPULSION);
    private static final Map<ServerLevel, BlockAuraField> FIELDS = new IdentityHashMap<>();

    private BlockAuras() {
    }

    /** 查询指定格子的区域能力；客户端和无源世界直接返回 false。 */
    public static boolean hasEffect(Level level, BlockPos pos, AuraEffect effect) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return false;
        }
        BlockAuraField field = FIELDS.get(serverLevel);
        return field != null && field.hasEffect(pos, effect);
    }

    /** 检查主动移动是否入区，同时允许从区内自行离开。 */
    public static boolean blocksEntry(Level level, Vec3 from, Vec3 to) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return false;
        }
        BlockAuraField field = FIELDS.get(serverLevel);
        return field != null && field.blocksEntry(from, to, AuraEffect.MONSTER_REPULSION);
    }

    /** 自有方块放置、移除或形态替换后，根据实际新状态登记源。 */
    public static void updateSource(Level level, BlockPos pos, BlockState state) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        if (isSource(state)) {
            field(serverLevel).addSource(pos);
        } else {
            BlockAuraField field = FIELDS.get(serverLevel);
            if (field != null) {
                field.removeSource(pos);
            }
        }
    }

    /** 使用事件提供的区块恢复源，覆盖旧存档与新生成区块。 */
    public static void loadChunk(ServerLevel level, ChunkAccess chunk) {
        unloadChunk(level, chunk);
        chunk.findBlocks(BlockAuras::isSource, (pos, state) -> field(level).addSource(pos));
    }

    /** 释放区块源及其跨区块覆盖。 */
    public static void unloadChunk(ServerLevel level, ChunkAccess chunk) {
        BlockAuraField field = FIELDS.get(level);
        if (field != null) {
            field.removeChunk(chunk.getPos());
        }
    }

    /** 世界卸载时释放其全部瞬态覆盖。 */
    public static void unloadLevel(ServerLevel level) {
        FIELDS.remove(level);
    }

    /** 服务器停止时释放属于该实例的世界引用。 */
    public static void stop(MinecraftServer server) {
        FIELDS.keySet().removeIf(level -> level.getServer() == server);
    }

    private static BlockAuraField field(ServerLevel level) {
        return FIELDS.computeIfAbsent(level, ignored -> new BlockAuraField(PURRMINT_RADIUS, PURRMINT_EFFECTS));
    }

    private static boolean isSource(BlockState state) {
        return state.is(ModBlocks.PURRMINT.get()) || state.is(ModBlocks.POTTED_PURRMINT.get());
    }
}
