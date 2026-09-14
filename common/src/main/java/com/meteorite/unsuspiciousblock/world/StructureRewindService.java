package com.meteorite.unsuspiciousblock.world;

import com.meteorite.unsuspiciousblock.Constants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 回溯结构的服务端任务：保留原布局，以世界生成随机种子逐区块重新放置。
 * 每台服务器只运行一个任务，避免重叠写入；任务随服务器关闭清理。
 */
public final class StructureRewindService {
    private static final Map<MinecraftServer, RewindTask> TASKS = new HashMap<>();

    private StructureRewindService() {
    }

    // 仅查询点击区块引用的结构，不扫描整张注册表或附近地形。
    public static boolean start(ServerLevel level, BlockPos pos, Player player) {
        if (TASKS.containsKey(level.getServer())) {
            tell(player, "busy");
            return false;
        }
        StructureStart original = level.structureManager().getStructureWithPieceAt(pos, holder -> true);
        if (!original.isValid()) {
            tell(player, "not_found");
            return false;
        }
        BoundingBox bounds = original.getBoundingBox();
        if (!level.getWorldBorder().isWithinBounds(new BlockPos(bounds.minX(), pos.getY(), bounds.minZ()))
                || !level.getWorldBorder().isWithinBounds(new BlockPos(bounds.maxX(), pos.getY(), bounds.maxZ()))) {
            tell(player, "outside_border");
            return false;
        }
        try {
            StructurePieceSerializationContext context = StructurePieceSerializationContext.fromLevel(level);
            CompoundTag tag = original.createTag(context, original.getChunkPos());
            resetPlacementState(tag.getList("Children", Tag.TAG_COMPOUND));
            StructureStart replay = StructureStart.loadStaticStart(context, tag, level.getSeed());
            // 部分第三方部件反序列化失败时原版可能静默跳过，禁止执行残缺的任务。
            if (replay == null || !replay.isValid() || replay.getPieces().size() != original.getPieces().size()) {
                tell(player, "unsupported");
                return false;
            }
            int index = decorationIndex(level, original.getStructure());
            TASKS.put(level.getServer(), new RewindTask(level, replay, bounds, index, player.getUUID(), pos.immutable()));
            level.playSound(null, pos, SoundEvents.RESPAWN_ANCHOR_CHARGE, SoundSource.PLAYERS, 0.8F, 1.3F);
            tell(player, "started");
            return true;
        } catch (RuntimeException exception) {
            Constants.LOG.error("Failed to prepare structure rewind at {}", pos, exception);
            tell(player, "unsupported");
            return false;
        }
    }

    // 原版按注册顺序在同一个 Decoration 阶段内单独计数，不使用全局 registry ID。
    private static int decorationIndex(ServerLevel level, Structure target) {
        int index = 0;
        for (Structure structure : level.registryAccess().registryOrThrow(Registries.STRUCTURE)) {
            if (structure == target) {
                return index;
            }
            if (structure.step() == target.step()) {
                index++;
            }
        }
        throw new IllegalStateException("Structure is absent from the active registry");
    }

    // 仅重置已核实的原版一次性状态；保留坐标、朝向、高度缓存和第三方自定义 NBT。
    private static void resetPlacementState(ListTag pieces) {
        for (int i = 0; i < pieces.size(); i++) {
            CompoundTag piece = pieces.getCompound(i);
            switch (piece.getString("id")) {
                case "minecraft:tedp" -> {
                    for (int chest = 0; chest < 4; chest++) {
                        piece.putBoolean("hasPlacedChest" + chest, false);
                    }
                }
                case "minecraft:tejp" -> {
                    piece.putBoolean("placedMainChest", false);
                    piece.putBoolean("placedHiddenChest", false);
                    piece.putBoolean("placedTrap1", false);
                    piece.putBoolean("placedTrap2", false);
                }
                case "minecraft:shcc" -> piece.putBoolean("Chest", false);
                case "minecraft:shpr", "minecraft:nemt" -> piece.putBoolean("Mob", false);
                case "minecraft:mscorridor" -> piece.putBoolean("hps", false);
                case "minecraft:tesh" -> {
                    piece.putBoolean("Witch", false);
                    piece.putBoolean("Cat", false);
                }
                default -> {
                    // 下界要塞的 Chest 表示尚需放置且可能原本没有箱子，不能盲目设为 true。
                }
            }
        }
    }

    // 由两端现有的服务端 tick 事件调用，始终在服务端主线程修改世界。
    public static void tick(MinecraftServer server) {
        RewindTask task = TASKS.get(server);
        if (task == null) {
            return;
        }
        try {
            if (task.placeNextChunk()) {
                TASKS.remove(server);
                Player player = server.getPlayerList().getPlayer(task.playerId);
                if (player != null) {
                    tell(player, "completed");
                }
                task.level.playSound(null, task.origin, SoundEvents.RESPAWN_ANCHOR_SET_SPAWN,
                        SoundSource.PLAYERS, 0.8F, 1.5F);
            }
        } catch (RuntimeException exception) {
            TASKS.remove(server);
            Constants.LOG.error("Structure rewind failed at {} in {}", task.origin, task.level.dimension().location(), exception);
            Player player = server.getPlayerList().getPlayer(task.playerId);
            if (player != null) {
                tell(player, "failed");
            }
        }
    }

    public static void stop(MinecraftServer server) {
        TASKS.remove(server);
    }

    private static void tell(Player player, String message) {
        player.displayClientMessage(Component.translatable("message.unsuspiciousblock.rewind_dust." + message), true);
    }

    /** 单个回溯任务持有独立部件副本，不改动存档中的结构起点及引用计数。 */
    private static final class RewindTask {
        private final ServerLevel level;
        private final StructureStart start;
        private final BoundingBox bounds;
        private final int decorationIndex;
        private final UUID playerId;
        private final BlockPos origin;
        private final int minChunkX;
        private final int maxChunkX;
        private final int maxChunkZ;
        private int chunkX;
        private int chunkZ;

        private RewindTask(ServerLevel level, StructureStart start, BoundingBox bounds,
                           int decorationIndex, UUID playerId, BlockPos origin) {
            this.level = level;
            this.start = start;
            this.bounds = bounds;
            this.decorationIndex = decorationIndex;
            this.playerId = playerId;
            this.origin = origin;
            minChunkX = bounds.minX() >> 4;
            maxChunkX = bounds.maxX() >> 4;
            maxChunkZ = bounds.maxZ() >> 4;
            chunkX = minChunkX;
            chunkZ = bounds.minZ() >> 4;
        }

        private boolean placeNextChunk() {
            ChunkPos chunk = new ChunkPos(chunkX, chunkZ);
            // 先完成区块原有生成，避免在半生成区块里重放后又被正常生成覆盖。
            level.getChunk(chunkX, chunkZ);
            WorldgenRandom random = new WorldgenRandom(new XoroshiroRandomSource(0L));
            long seed = random.setDecorationSeed(level.getSeed(), chunk.getMinBlockX(), chunk.getMinBlockZ());
            random.setFeatureSeed(seed, decorationIndex, start.getStructure().step().ordinal());
            BoundingBox writableArea = new BoundingBox(chunk.getMinBlockX(), level.getMinBuildHeight() + 1,
                    chunk.getMinBlockZ(), chunk.getMaxBlockX(), level.getMaxBuildHeight() - 1, chunk.getMaxBlockZ());
            start.placeInChunk(level, level.structureManager(), level.getChunkSource().getGenerator(),
                    random, writableArea, chunk);
            showParticles(chunk);
            if (++chunkX > maxChunkX) {
                chunkX = minChunkX;
                chunkZ++;
            }
            return chunkZ > maxChunkZ;
        }

        // 粒子预算固定，表现为随区块重建推进的紫色回溯光幕。
        private void showParticles(ChunkPos chunk) {
            double minX = Math.max(bounds.minX(), chunk.getMinBlockX());
            double maxX = Math.min(bounds.maxX(), chunk.getMaxBlockX()) + 1.0;
            double minZ = Math.max(bounds.minZ(), chunk.getMinBlockZ());
            double maxZ = Math.min(bounds.maxZ(), chunk.getMaxBlockZ()) + 1.0;
            double minY = Math.max(bounds.minY(), level.getMinBuildHeight());
            double maxY = Math.min(bounds.maxY() + 1.0, level.getMaxBuildHeight());
            level.sendParticles(ParticleTypes.REVERSE_PORTAL, (minX + maxX) / 2,
                    (minY + maxY) / 2, (minZ + maxZ) / 2, 96,
                    (maxX - minX) / 2, (maxY - minY) / 2, (maxZ - minZ) / 2, 0.04);
        }
    }
}
