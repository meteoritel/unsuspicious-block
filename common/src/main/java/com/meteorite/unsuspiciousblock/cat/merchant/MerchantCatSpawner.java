package com.meteorite.unsuspiciousblock.cat.merchant;

import com.meteorite.unsuspiciousblock.cat.CatFavorManager;
import com.meteorite.unsuspiciousblock.entity.MerchantCat;
import com.meteorite.unsuspiciousblock.entity.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 猫猫商人独立生成器——按每世界 25%/50%/75% 渐进概率在合格玩家附近村庄生成。
 */
public final class MerchantCatSpawner {
    private static final int ATTEMPT_INTERVAL = 24000;
    private static final int LIFETIME = 48000;
    private static final int PLAYER_RADIUS = 48;
    private static final int POSITION_ATTEMPTS = 10;

    private MerchantCatSpawner() {
    }

    public static void tick(MinecraftServer server) {
        if (server.getTickCount() % 20 != 0) {
            return;
        }
        for (ServerLevel level : server.getAllLevels()) {
            tickLevel(level);
        }
    }

    private static void tickLevel(ServerLevel level) {
        MerchantCatSpawnData data = MerchantCatSpawnData.get(level);
        long gameTime = level.getGameTime();
        if (data.getNextAttempt() == 0L) {
            data.scheduleNext(gameTime, ATTEMPT_INTERVAL);
            return;
        }

        UUID activeUuid = data.getActiveMerchantUuid();
        if (activeUuid != null && (!(level.getEntity(activeUuid) instanceof MerchantCat merchant)
                || merchant.isAlive())) {
            if (gameTime >= data.getNextAttempt()) {
                data.scheduleNext(gameTime, ATTEMPT_INTERVAL);
            }
            return;
        }
        if (activeUuid != null) {
            data.clearActive(activeUuid);
        }
        if (gameTime < data.getNextAttempt()) {
            return;
        }

        List<ServerPlayer> candidates = level.players().stream()
                .filter(ServerPlayer::isAlive)
                .filter(player -> CatFavorManager.getCatBond(player) >= 100)
                .filter(CatFavorManager::hasOwnedHandOfCat)
                .toList();
        if (candidates.isEmpty()) {
            data.scheduleNext(gameTime, ATTEMPT_INTERVAL);
            return;
        }

        data.scheduleNext(gameTime, ATTEMPT_INTERVAL);
        if (level.getRandom().nextFloat() >= data.getChance()) {
            data.recordFailure();
            return;
        }
        ServerPlayer target = candidates.get(level.getRandom().nextInt(candidates.size()));
        BlockPos spawnPos = findSpawnPosition(level, target);
        if (spawnPos == null) {
            data.recordFailure();
            return;
        }
        MerchantCat merchant = ModEntities.MERCHANT_CAT.get().create(level);
        if (merchant == null) {
            data.recordFailure();
            return;
        }
        merchant.moveTo(spawnPos.getX() + 0.5, spawnPos.getY(), spawnPos.getZ() + 0.5,
                level.getRandom().nextFloat() * 360.0F, 0.0F);
        merchant.freezeSpawnY();
        merchant.setSpiritLifetime(LIFETIME);
        merchant.initializeTrades(level, CatFavorManager.getCatBond(target));
        if (level.addFreshEntity(merchant)) {
            data.recordSuccess(merchant.getUUID());
        } else {
            data.recordFailure();
        }
    }

    @Nullable
    private static BlockPos findSpawnPosition(ServerLevel level, ServerPlayer player) {
        List<BlockPos> attempts = new ArrayList<>();
        Optional<BlockPos> meetingPoint = level.getPoiManager().findClosest(
                holder -> holder.is(PoiTypes.MEETING),
                player.blockPosition(), PLAYER_RADIUS, PoiManager.Occupancy.ANY);
        meetingPoint.ifPresent(attempts::add);
        while (attempts.size() < POSITION_ATTEMPTS) {
            int x = player.getBlockX() + level.getRandom().nextIntBetweenInclusive(-PLAYER_RADIUS, PLAYER_RADIUS);
            int z = player.getBlockZ() + level.getRandom().nextIntBetweenInclusive(-PLAYER_RADIUS, PLAYER_RADIUS);
            int y = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
            attempts.add(new BlockPos(x, y, z));
        }
        for (BlockPos candidate : attempts) {
            int y = level.getHeight(Heightmap.Types.WORLD_SURFACE, candidate.getX(), candidate.getZ());
            BlockPos pos = new BlockPos(candidate.getX(), y, candidate.getZ());
            if (level.isVillage(pos) && isOpen(level, pos)) {
                return pos;
            }
        }
        return null;
    }

    private static boolean isOpen(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()
                && level.getBlockState(pos.above()).getCollisionShape(level, pos.above()).isEmpty()
                && !level.getBlockState(pos.below()).getCollisionShape(level, pos.below()).isEmpty();
    }
}
