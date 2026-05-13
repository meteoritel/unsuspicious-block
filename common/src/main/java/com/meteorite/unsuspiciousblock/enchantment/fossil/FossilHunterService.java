package com.meteorite.unsuspiciousblock.enchantment.fossil;

import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.enchantment.ModEnchantments;
import com.meteorite.unsuspiciousblock.world.PlacedBoneBlockSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 化石猎手附魔服务：负责骨块自然性判定与额外掉落生成。 */
public final class FossilHunterService {
    private static final double EXTRA_LOOT_CHANCE = 0.50D;
    private static final Set<PendingPlayerBreak> PENDING_PLAYER_BREAKS = new HashSet<>();
    public static final ResourceKey<LootTable> OVERWORLD_BONE_BLOCK_LOOT_TABLE =
            lootTableKey("gameplay/fossil_hunter/overworld_bone_block");
    public static final ResourceKey<LootTable> NETHER_BONE_BLOCK_LOOT_TABLE =
            lootTableKey("gameplay/fossil_hunter/nether_bone_block");

    private FossilHunterService() {
    }

    public static void markPlacedBoneBlock(ServerLevel level, BlockPos pos) {
        PlacedBoneBlockSavedData.get(level).markPlaced(pos);
    }

    public static void clearPlacedBoneBlock(ServerLevel level, BlockPos pos) {
        PlacedBoneBlockSavedData.get(level).clearPlaced(pos);
    }

    public static void tryMovePlacedBoneBlock(ServerLevel level, BlockPos pos) {
        PlacedBoneBlockSavedData data = PlacedBoneBlockSavedData.get(level);
        for (Direction direction : Direction.values()) {
            BlockPos fromPos = pos.relative(direction);
            if (level.getBlockState(fromPos).is(Blocks.BONE_BLOCK)) {
                continue;
            }
            if (data.movePlaced(fromPos, pos)) {
                return;
            }
        }
    }

    public static void markPlayerBreakingBoneBlock(ServerLevel level, BlockPos pos, BlockState state) {
        if (!state.is(Blocks.BONE_BLOCK)) {
            return;
        }
        PENDING_PLAYER_BREAKS.add(new PendingPlayerBreak(level.dimension(), pos.asLong()));
    }

    public static boolean isPlayerBreakingBoneBlock(ServerLevel level, BlockPos pos) {
        return PENDING_PLAYER_BREAKS.contains(new PendingPlayerBreak(level.dimension(), pos.asLong()));
    }

    public static void clearPlayerBreakingBoneBlock(ServerLevel level, BlockPos pos) {
        PENDING_PLAYER_BREAKS.remove(new PendingPlayerBreak(level.dimension(), pos.asLong()));
    }

    public static void clearPendingPlayerBreaks() {
        PENDING_PLAYER_BREAKS.clear();
    }

    public static List<ItemStack> rollExtraLoot(ServerPlayer player, ServerLevel level, BlockPos pos,
                                                BlockState state, ItemStack tool) {
        if (!state.is(Blocks.BONE_BLOCK)) {
            return List.of();
        }

        // 玩家放置的骨块只清除标记，不触发额外奖励。
        if (PlacedBoneBlockSavedData.get(level).consumePlaced(pos)
                || player.isCreative()
                || !level.getGameRules().getBoolean(net.minecraft.world.level.GameRules.RULE_DOBLOCKDROPS)) {
            return List.of();
        }

        if (ModEnchantments.getEnchantmentLevel(level.registryAccess(), tool, ModEnchantments.FOSSIL_HUNTER) <= 0
                || player.getRandom().nextDouble() >= EXTRA_LOOT_CHANCE) {
            return List.of();
        }

        LootParams lootParams = new LootParams.Builder(level)
                .withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(pos))
                .withParameter(LootContextParams.BLOCK_STATE, state)
                .withParameter(LootContextParams.TOOL, tool)
                .withOptionalParameter(LootContextParams.THIS_ENTITY, player)
                .withLuck(player.getLuck())
                .create(LootContextParamSets.BLOCK);
        return level.getServer().reloadableRegistries().getLootTable(resolveLootTable(level))
                .getRandomItems(lootParams, player.getRandom());
    }

    private static ResourceKey<LootTable> resolveLootTable(ServerLevel level) {
        return Level.NETHER.equals(level.dimension())
                ? NETHER_BONE_BLOCK_LOOT_TABLE
                : OVERWORLD_BONE_BLOCK_LOOT_TABLE;
    }

    private static ResourceKey<LootTable> lootTableKey(String path) {
        return ResourceKey.create(Registries.LOOT_TABLE,
                ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, path));
    }

    private record PendingPlayerBreak(ResourceKey<Level> dimension, long packedPos) {
    }
}
