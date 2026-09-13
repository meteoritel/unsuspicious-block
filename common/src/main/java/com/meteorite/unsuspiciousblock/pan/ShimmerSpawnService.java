package com.meteorite.unsuspiciousblock.pan;

import com.meteorite.unsuspiciousblock.entity.ModEntities;
import com.meteorite.unsuspiciousblock.entity.ShimmerEntity;
import com.meteorite.unsuspiciousblock.platform.Services;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/***
 * 闪烁的光生成服务——同时驱动“自然生成”与“世界生成”两条来源。
 * <p>
 * 自然生成：按固定节拍在玩家已加载区块中挑选河流区块，以世界水平面高度做水平游走，
 * 命中开阔水域且与现存淘洗点保持足够间距时生成。每维度数量达到上限后不再尝试，
 * 直到有实体消散（账本条目注销）才恢复尝试。
 * <p>
 * 世界生成：区块首次加载时做一次低概率判定，命中则在区块内寻找落点并进入待办队列，
 * 等区块完全加载后的服务器 tick 再真正生成实体——世界生成来源不消散也不计入上限。
 */
public final class ShimmerSpawnService {
    // 生成节拍的检查间隔（真正的尝试间隔由配置的 spawn interval 决定）
    private static final int TICK_CHECK_INTERVAL = 20;
    // 单次尝试在区块内游走的最大落点数
    private static final int WANDER_ATTEMPTS = 12;
    // 挑选玩家附近加载区块时的最大重试次数
    private static final int CHUNK_PICK_ATTEMPTS = 8;
    private static final int SPAWN_RADIUS_CHUNKS = 8;
    private static final List<ChunkPos> SPAWN_OFFSETS = createSpawnOffsets();
    // 每次服务器 tick 处理的世界生成待办上限，避免区块批量加载时集中生成
    private static final int PENDING_SPAWN_BUDGET = 4;
    // 世界生成确定性判定的混淆盐值，避免与世界种子的其它用途产生相关性
    private static final long WORLDGEN_SALT = 0x5DEECE66DL;

    // 世界生成待办：区块加载时完成概率判定，延迟到区块稳定后再实体化
    private static final Map<ResourceKey<Level>, Map<ChunkPos, BlockPos>> PENDING_WORLDGEN = new HashMap<>();

    private static MinecraftServer pendingServer;

    // 队列只属于当前服务器；切换存档时不能复用旧坐标。
    public static void stop(MinecraftServer server) {
        if (pendingServer == server) {
            PENDING_WORLDGEN.clear();
            pendingServer = null;
        }
    }

    private static void bindServer(MinecraftServer server) {
        if (pendingServer != server) {
            PENDING_WORLDGEN.clear();
            pendingServer = server;
        }
    }

    private ShimmerSpawnService() {
    }

    // 服务端 tick 入口：每 tick 消化世界生成待办，并按节拍尝试自然生成
    public static void tick(MinecraftServer server) {
        bindServer(server);
        drainPendingWorldgen(server);
        if (server.getTickCount() % TICK_CHECK_INTERVAL != 0) {
            return;
        }
        for (ServerLevel level : server.getAllLevels()) {
            tickLevel(level);
        }
    }

    // ========== 自然生成 ==========

    private static void tickLevel(ServerLevel level) {
        ShimmerLedger ledger = ShimmerLedger.of(level);
        long gameTime = level.getGameTime();
        int interval = Services.PANNING_CONFIG.getSpawnIntervalTicks();
        if (ledger.getNextAttempt() == 0L) {
            ledger.scheduleNextAttempt(gameTime, interval);
            return;
        }
        if (gameTime < ledger.getNextAttempt()) {
            return;
        }
        ledger.scheduleNextAttempt(gameTime, interval);

        // 到达上限后不再尝试生成
        if (ledger.countNatural() >= Services.PANNING_CONFIG.getMaxNaturalPerDimension()) {
            return;
        }

        List<ServerPlayer> players = level.players();
        if (players.isEmpty()) {
            return;
        }
        ServerPlayer target = players.get(level.getRandom().nextInt(players.size()));
        ChunkPos chunkPos = pickLoadedChunk(level, target);
        if (chunkPos == null) {
            return;
        }
        trySpawnInChunk(level, ledger, chunkPos, true, false);
    }

    // 预计算圆形范围，确保八次机会都抽取范围内的区块，不浪费在方形四角。
    private static List<ChunkPos> createSpawnOffsets() {
        List<ChunkPos> offsets = new ArrayList<>();
        for (int x = -SPAWN_RADIUS_CHUNKS; x <= SPAWN_RADIUS_CHUNKS; x++) {
            for (int z = -SPAWN_RADIUS_CHUNKS; z <= SPAWN_RADIUS_CHUNKS; z++) {
                if (x * x + z * z <= SPAWN_RADIUS_CHUNKS * SPAWN_RADIUS_CHUNKS) {
                    offsets.add(new ChunkPos(x, z));
                }
            }
        }
        return List.copyOf(offsets);
    }

    // 保留按玩家抽样的聚集加权；最多八次筛选半径八区块内已加载的河流区块。
    @Nullable
    private static ChunkPos pickLoadedChunk(ServerLevel level, ServerPlayer player) {
        ChunkPos center = player.chunkPosition();
        ShimmerLedger ledger = ShimmerLedger.of(level);
        for (int attempt = 0; attempt < CHUNK_PICK_ATTEMPTS; attempt++) {
            ChunkPos offset = SPAWN_OFFSETS.get(level.getRandom().nextInt(SPAWN_OFFSETS.size()));
            ChunkPos candidate = new ChunkPos(center.x + offset.x, center.z + offset.z);
            if (level.isLoaded(candidate.getWorldPosition())
                    && !ledger.isCoolingDown(candidate, level.getGameTime())
                    && ShimmerPlacement.hasRiverBiome(level, candidate)) {
                return candidate;
            }
        }
        return null;
    }

    // 在区块内做水平游走，命中河流群系中的开阔水域且间距足够时生成
    private static boolean trySpawnInChunk(ServerLevel level, ShimmerLedger ledger, ChunkPos chunkPos, boolean natural, boolean bypassCooldown) {
        if (!bypassCooldown && ledger.isCoolingDown(chunkPos, level.getGameTime())) return false;
        // 随机起始分区后逐区探查 4×3 个分区，命中即停，避免重复检查同一列。
        int start = level.getRandom().nextInt(WANDER_ATTEMPTS);
        for (int attempt = 0; attempt < WANDER_ATTEMPTS; attempt++) {
            int cell = (start + attempt) % WANDER_ATTEMPTS;
            int minX = 1 + (cell % 4) * 14 / 4;
            int maxX = 1 + (cell % 4 + 1) * 14 / 4;
            int minZ = 1 + (cell / 4) * 14 / 3;
            int maxZ = 1 + (cell / 4 + 1) * 14 / 3;
            BlockPos waterPos = ShimmerPlacement.findOpenWaterSurface(level,
                    chunkPos.getMinBlockX() + minX + level.getRandom().nextInt(maxX - minX),
                    chunkPos.getMinBlockZ() + minZ + level.getRandom().nextInt(maxZ - minZ));
            if (waterPos == null || !ShimmerPlacement.isRiverBiome(level, waterPos)) {
                continue;
            }
            if (ledger.isTooClose(waterPos, Services.PANNING_CONFIG.getSpacingBlocks())) {
                continue;
            }
            return spawnShimmer(level, waterPos, natural) != null;
        }
        return false;
    }

    // 在区块范围内随机取一个世界水平面附近的开阔水域点
    // 采样点与区块边界保持 1 格间距：开阔水域判定需要查询水平相邻方块，
    // 贴边采样会落到邻区块上，而世界生成阶段是在区块加载回调中执行的，
    // 此时邻区块未必已加载，避免由此触发级联的同步区块加载
    @Nullable
    private static BlockPos randomWaterSurfaceIn(ServerLevel level, ChunkPos chunkPos) {
        int x = chunkPos.getMinBlockX() + 1 + level.getRandom().nextInt(14);
        int z = chunkPos.getMinBlockZ() + 1 + level.getRandom().nextInt(14);
        return ShimmerPlacement.findOpenWaterSurface(level, x, z);
    }

    /***
     * 在指定水方块上生成一个闪烁的光，并登记到账本。
     *
     * @param natural true 为自然生成（受上限约束且有寿命），false 为世界生成（不消散且不计上限）
     */
    @Nullable
    public static ShimmerEntity spawnShimmer(ServerLevel level, BlockPos waterPos, boolean natural) {
        ShimmerEntity shimmer = ModEntities.SHIMMER.get().create(level);
        if (shimmer == null) {
            return null;
        }
        shimmer.initializeAt(waterPos, natural, natural ? randomLifetimeTicks(level) : 0);
        if (!level.addFreshEntity(shimmer)) {
            return null;
        }
        ShimmerLedger.of(level).register(shimmer.getUUID(), waterPos,
                natural ? ShimmerLedger.Source.NATURAL : ShimmerLedger.Source.WORLDGEN, shimmer.getExpiresAt());
        shimmer.playSpawnEffects(level);
        return shimmer;
    }

    // 供未来工具在采空结算后调用；仅绕过区域冷却，仍受自然点上限、间距和水域约束。
    // 不清除冷却，不额外产出战利品；概率由工具决定，失败不补偿或重试。
    public static boolean tryRegenerateAfterHarvest(ServerLevel level, BlockPos harvestedPos, double chance) {
        if (!Double.isFinite(chance) || chance <= 0.0D || chance > 1.0D) return false;
        ShimmerLedger ledger = ShimmerLedger.of(level);
        ChunkPos chunk = new ChunkPos(harvestedPos);
        if (ledger.countNatural() >= Services.PANNING_CONFIG.getMaxNaturalPerDimension()
                || !level.isLoaded(chunk.getWorldPosition())
                || level.getRandom().nextDouble() >= chance
                || !ShimmerPlacement.hasRiverBiome(level, chunk)) return false;
        return trySpawnInChunk(level, ledger, chunk, true, true);
    }

    // 调试指定区块的自然生成尝试，复用正式落点流程，不改变自动尝试节拍。
    public static String debugAttemptInChunk(ServerLevel level, ChunkPos chunk) {
        ShimmerLedger ledger = ShimmerLedger.of(level);
        if (!level.isLoaded(chunk.getWorldPosition())) return "unloaded";
        if (ledger.countNatural() >= Services.PANNING_CONFIG.getMaxNaturalPerDimension()) return "cap";
        if (ledger.isCoolingDown(chunk, level.getGameTime())) return "cooldown";
        if (!ShimmerPlacement.hasRiverBiome(level, chunk)) return "not_river";
        return trySpawnInChunk(level, ledger, chunk, true, false) ? "success" : "no_position";
    }

    // 生成时即固定的随机寿命，落在配置的寿命区间内
    private static int randomLifetimeTicks(ServerLevel level) {
        int min = Math.max(1, Services.PANNING_CONFIG.getMinLifetimeTicks());
        int max = Math.max(min, Services.PANNING_CONFIG.getMaxLifetimeTicks());
        return Mth.nextInt(level.getRandom(), min, max);
    }

    // ========== 世界生成 ==========

    /***
     * 区块首次加载时的世界生成判定，由平台侧的区块加载事件调用。
     * <p>
     * 概率判定使用「世界种子 + 区块坐标」推导的确定性随机：同一区块永远得到同一结论，
     * 因此无需为未命中的区块记录任何状态，账本只会记录真正生成过淘洗点的区块
     * （默认约占河流区块的 2%），不记录未命中的区块。
     * <p>
     * 命中后选取落点并进入待办队列，由服务端 tick 在区块稳定后实体化；实体化成功才标记
     * 该区块已生成，被淘空或破坏后不会再重新出现。
     */
    public static void onChunkLoaded(ServerLevel level, ChunkPos chunkPos) {
        bindServer(level.getServer());
        // 确定性判定代价极低且能过滤绝大多数区块，放在最前面避免多余的群系采样
        double chance = Services.PANNING_CONFIG.getWorldgenChance();
        if (chance <= 0.0D || !passesWorldgenRoll(level, chunkPos, chance)) {
            return;
        }
        ShimmerLedger ledger = ShimmerLedger.of(level);
        if (ledger.isChunkRolled(chunkPos) || ledger.isCoolingDown(chunkPos, level.getGameTime())) {
            return;
        }
        if (!ShimmerPlacement.hasRiverBiome(level, chunkPos)) {
            return;
        }
        for (int attempt = 0; attempt < WANDER_ATTEMPTS; attempt++) {
            BlockPos waterPos = randomWaterSurfaceIn(level, chunkPos);
            if (waterPos == null || !ShimmerPlacement.isRiverBiome(level, waterPos)) {
                continue;
            }
            if (ledger.isTooClose(waterPos, Services.PANNING_CONFIG.getSpacingBlocks())) {
                continue;
            }
            PENDING_WORLDGEN.computeIfAbsent(level.dimension(), key -> new LinkedHashMap<>())
                    .put(chunkPos, waterPos);
            return;
        }
    }

    // 由世界种子与区块坐标推导的确定性概率判定
    private static boolean passesWorldgenRoll(ServerLevel level, ChunkPos chunkPos, double chance) {
        RandomSource random = RandomSource.create(level.getSeed()
                ^ chunkPos.toLong() * WORLDGEN_SALT);
        return random.nextDouble() < chance;
    }

    // 消化世界生成待办：区块完全加载且落点仍然有效时才真正生成
    private static void drainPendingWorldgen(MinecraftServer server) {
        if (PENDING_WORLDGEN.isEmpty()) {
            return;
        }
        int budget = PENDING_SPAWN_BUDGET;
        Iterator<Map.Entry<ResourceKey<Level>, Map<ChunkPos, BlockPos>>> levelIterator =
                PENDING_WORLDGEN.entrySet().iterator();
        while (levelIterator.hasNext() && budget > 0) {
            Map.Entry<ResourceKey<Level>, Map<ChunkPos, BlockPos>> levelEntry = levelIterator.next();
            ServerLevel level = server.getLevel(levelEntry.getKey());
            Iterator<Map.Entry<ChunkPos, BlockPos>> iterator = levelEntry.getValue().entrySet().iterator();
            while (iterator.hasNext() && budget > 0) {
                BlockPos waterPos = iterator.next().getValue();
                iterator.remove();
                budget--;
                if (level == null || !level.isLoaded(waterPos)) {
                    continue;
                }
                if (!ShimmerPlacement.isBoundWaterIntact(level, waterPos)) {
                    continue;
                }
                ShimmerLedger ledger = ShimmerLedger.of(level);
                // 入队后其他候选可能已经生成，必须按最新账本复查。
                if (ledger.isChunkRolled(new ChunkPos(waterPos))
                        || ledger.isCoolingDown(new ChunkPos(waterPos), level.getGameTime())
                        || ledger.isTooClose(waterPos, Services.PANNING_CONFIG.getSpacingBlocks())) {
                    continue;
                }
                if (spawnShimmer(level, waterPos, false) != null) {
                    ledger.markChunkRolled(new ChunkPos(waterPos));
                }
            }
            if (levelEntry.getValue().isEmpty()) {
                levelIterator.remove();
            }
        }
    }
}
