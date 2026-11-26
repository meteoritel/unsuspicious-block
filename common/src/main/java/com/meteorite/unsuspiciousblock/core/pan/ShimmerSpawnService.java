package com.meteorite.unsuspiciousblock.core.pan;

import com.meteorite.unsuspiciousblock.core.entity.ShimmerEntity;
import com.meteorite.unsuspiciousblock.core.pan.variant.ShimmerVariant;
import com.meteorite.unsuspiciousblock.core.pan.variant.AnchorRule;
import com.meteorite.unsuspiciousblock.core.pan.variant.ShimmerVariants;
import com.meteorite.unsuspiciousblock.platform.Services;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/***
 * 闪烁的光运行时生成服务——驱动自然生成、特殊再生与手动生成。
 * <p>
 * 自然生成：按固定节拍在玩家已加载区块中挑选生成域内的区块，以该维度的海平面做水平游走，
 * 命中开阔液面且与现存淘洗点保持足够间距时生成。每维度数量达到上限后不再尝试，
 * 直到有实体消散（账本条目注销）才恢复尝试。自然生成只投放水域变体，幽微的光纯由世界生成产出。
 * <p>
 * 世界生成由 ShimmerFeature 在新区块生成期间写入实体 NBT，不经过本服务的运行时调度。
 */
public final class ShimmerSpawnService {
    /*** 区分生成触发方式；特殊生成使用独立分类，手动自然样本归自然类型。 */
    public enum SpawnTrigger {
        NATURAL, SPECIAL, MANUAL, WORLDGEN
    }

    // 生成节拍的检查间隔（真正的尝试间隔由配置的 spawn interval 决定）
    private static final int TICK_CHECK_INTERVAL = 20;
    // 挑选玩家附近加载区块时的最大重试次数
    private static final int CHUNK_PICK_ATTEMPTS = 8;
    private static final int SPAWN_RADIUS_CHUNKS = 8;
    private static final int REGENERATION_RADIUS = 5;
    private static final List<ChunkPos> SPAWN_OFFSETS = createSpawnOffsets();
    private static MinecraftServer boundServer;

    // 速率统计只属于当前服务器，切换存档时清空。
    public static void stop(MinecraftServer server) {
        if (boundServer == server) {
            ShimmerSpawnStatistics.clear();
            boundServer = null;
        }
    }

    private static void bindServer(MinecraftServer server) {
        if (boundServer != server) {
            ShimmerSpawnStatistics.clear();
            boundServer = server;
        }
    }

    private ShimmerSpawnService() {
    }

    /*** 清除结果区分立即删除和等待实体加载。 */
    public record ClearResult(int loaded, int deferred) {
    }

    // 按账本处理整个维度，同时补上已加载但尚未完成首 tick 登记的实体；两条过滤轴为 null 表示不过滤。
    public static ClearResult clear(ServerLevel level, @Nullable ShimmerLedger.Source source,
                                    @Nullable ResourceLocation variant) {
        ShimmerLedger ledger = ShimmerLedger.of(level);
        var candidates = ledger.matchingEntries(source, variant);
        Map<UUID, ShimmerEntity> loaded = new HashMap<>();
        for (var entity : level.getAllEntities()) {
            if (!(entity instanceof ShimmerEntity shimmer) || shimmer.isRemoved()) continue;
            UUID uuid = shimmer.getUUID();
            // 已加载实体的真实来源与变体优先于旧账本。
            boolean sourceMatches = source == null || shimmer.getSpawnSource() == source;
            boolean variantMatches = variant == null || shimmer.getVariant().id().equals(variant);
            if (sourceMatches && variantMatches) {
                candidates.add(uuid);
                loaded.put(uuid, shimmer);
            } else {
                candidates.remove(uuid);
            }
        }
        for (UUID uuid : candidates) {
            ledger.markForRemoval(uuid);
            ShimmerEntity shimmer = loaded.get(uuid);
            if (shimmer != null) shimmer.discard();
        }
        return new ClearResult(loaded.size(), candidates.size() - loaded.size());
    }

    // 服务端 tick 入口：驱动速率统计并按节拍尝试自然生成
    public static void tick(MinecraftServer server) {
        bindServer(server);
        ShimmerSpawnStatistics.tick(server);
        if (server.getTickCount() % TICK_CHECK_INTERVAL != 0) {
            return;
        }
        for (ServerLevel level : server.getAllLevels()) {
            if (!ShimmerSpawnStatistics.isRunning(level)) tickLevel(level);
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

        attemptNaturalSpawn(level, false);
    }

    // 自动节拍与速率统计共用完整尝试；受上限或无人阻止的轮次也视为失败。
    static boolean attemptNaturalSpawn(ServerLevel level, boolean bypassCap) {
        ShimmerLedger ledger = ShimmerLedger.of(level);

        // 到达上限后不再尝试生成；上限按变体分别计算
        ShimmerVariant variant = ShimmerVariants.WATER;
        if (!bypassCap && ledger.countNatural(variant.id()) >= Services.PANNING_CONFIG.getMaxNaturalPerDimension()) {
            return false;
        }

        List<ServerPlayer> players = level.players();
        if (players.isEmpty()) {
            return false;
        }
        ServerPlayer target = players.get(level.getRandom().nextInt(players.size()));
        ChunkPos center = target.chunkPosition();
        int seaLevel = surfaceSearchCenter(level, variant);
        int spacing = Services.PANNING_CONFIG.getSpacingBlocks();
        int[] tried = new int[CHUNK_PICK_ATTEMPTS];
        for (int attempt = 0; attempt < tried.length; attempt++) {
            int offsetIndex = level.getRandom().nextInt(SPAWN_OFFSETS.size() - attempt);
            // 将剩余区间的随机秩映射为未使用的下标。
            int insertion = 0;
            while (insertion < attempt && tried[insertion] <= offsetIndex) {
                offsetIndex++;
                insertion++;
            }
            System.arraycopy(tried, insertion, tried, insertion + 1, attempt - insertion);
            tried[insertion] = offsetIndex;
            ChunkPos offset = SPAWN_OFFSETS.get(offsetIndex);
            ChunkPos chunk = new ChunkPos(center.x + offset.x, center.z + offset.z);
            if (trySpawnInChunk(level, ledger, chunk, variant, SpawnTrigger.NATURAL, seaLevel, spacing)) {
                return true;
            }
        }
        return false;
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

    // 落点搜索的基准高度必须取自区块生成器：Level#getSeaLevel 是固定值 63，在下界会定位到错误的层高。
    private static int surfaceSearchCenter(ServerLevel level, ShimmerVariant variant) {
        return variant.spawnDomain().surfaceSearchCenter(level.getChunkSource().getGenerator());
    }

    private static boolean isChunkUnavailable(ServerLevel level, ChunkPos chunk) {
        return level.getChunkSource().getChunkNow(chunk.x, chunk.z) == null
                || !level.areEntitiesLoaded(chunk.toLong());
    }

    private static boolean trySpawnInChunk(ServerLevel level, ShimmerLedger ledger, ChunkPos chunk,
                                           ShimmerVariant variant, SpawnTrigger trigger, int seaLevel, int spacing) {
        if (isChunkUnavailable(level, chunk) || ledger.isCoolingDown(chunk, level.getGameTime())) {
            return false;
        }
        BlockPos surface = ShimmerPlacement.wanderForSurface(level, level.getRandom(), chunk, variant, seaLevel,
                pos -> !ledger.isOccupied(pos) && !ledger.isTooClose(pos, spacing));
        return surface != null && spawnShimmer(level, surface, variant, trigger) != null;
    }

    // 底层生成入口：按变体创建实体并完成初始化、账本登记与演出。
    // 该入口不校验上限、间距与落点；特殊生成可传 SPECIAL，共享有限寿命但不广播自然生成提示。
    @Nullable
    public static ShimmerEntity spawnShimmer(ServerLevel level, BlockPos waterPos,
                                             ShimmerVariant variant, SpawnTrigger trigger) {
        boolean natural = trigger != SpawnTrigger.WORLDGEN;
        ShimmerEntity shimmer = variant.entityType().get().create(level);
        if (shimmer == null) {
            return null;
        }
        shimmer.initializeAt(waterPos, natural, randomLifetimeTicks(level));
        shimmer.setSpecialSpawn(trigger == SpawnTrigger.SPECIAL);
        if (!level.addFreshEntity(shimmer)) {
            return null;
        }
        shimmer.playSpawnEffects(level);
        if (trigger == SpawnTrigger.NATURAL) shimmer.broadcastNaturalSpawn(level);
        return shimmer;
    }

    /** 每次成功淘洗自然点至多再生一个特殊点，最后一次淘洗同样参与。 */
    public static void tryRegenerateAfterHarvest(ServerLevel level, ShimmerEntity harvested, double chance) {
        if (harvested.getSpawnSource() != ShimmerLedger.Source.NATURAL || chance <= 0.0D
                || level.getRandom().nextDouble() >= chance) {
            return;
        }
        BlockPos center = harvested.getAnchorPos();
        ShimmerVariant variant = harvested.getVariant();
        AnchorRule anchor = variant.anchor();
        ShimmerLedger ledger = ShimmerLedger.of(level);
        int bestDistance = REGENERATION_RADIUS * REGENERATION_RADIUS;
        BlockPos selected = null;
        int ties = 0;
        for (int dx = -REGENERATION_RADIUS; dx <= REGENERATION_RADIUS; dx++) {
            for (int dz = -REGENERATION_RADIUS; dz <= REGENERATION_RADIUS; dz++) {
                int distance = dx * dx + dz * dz;
                if (distance == 0 || distance > bestDistance) {
                    continue;
                }
                BlockPos pos = center.offset(dx, 0, dz);
                if (isChunkUnavailable(level, new ChunkPos(pos)) || ledger.isOccupied(pos)
                        || !anchor.isValid(level, pos) || anchor.isFrozenAt(level, pos)) {
                    continue;
                }
                if (distance < bestDistance) {
                    bestDistance = distance;
                    ties = 0;
                }
                if (level.getRandom().nextInt(++ties) == 0) {
                    selected = pos;
                }
            }
        }
        if (selected != null) {
            spawnShimmer(level, selected, variant, SpawnTrigger.SPECIAL);
        }
    }

    // 调试指定区块的自然生成尝试，复用正式落点流程，不改变自动尝试节拍。
    // 该子指令只针对水域变体，幽微的光没有运行时自然生成入口。
    public static String debugAttemptInChunk(ServerLevel level, ChunkPos chunk) {
        ShimmerLedger ledger = ShimmerLedger.of(level);
        ShimmerVariant variant = ShimmerVariants.WATER;
        if (isChunkUnavailable(level, chunk)) return "unloaded";
        if (ledger.countNatural(variant.id()) >= Services.PANNING_CONFIG.getMaxNaturalPerDimension()) return "cap";
        if (ledger.isCoolingDown(chunk, level.getGameTime())) return "cooldown";
        return trySpawnInChunk(level, ledger, chunk, variant, SpawnTrigger.MANUAL,
                surfaceSearchCenter(level, variant), Services.PANNING_CONFIG.getSpacingBlocks())
                ? "success" : "no_position";
    }

    // 生成时即固定的随机寿命，落在配置的寿命区间内
    public static int randomLifetimeTicks(ServerLevel level) {
        int min = Math.max(1, Services.PANNING_CONFIG.getMinLifetimeTicks());
        int max = Math.max(min, Services.PANNING_CONFIG.getMaxLifetimeTicks());
        return Mth.nextInt(level.getRandom(), min, max);
    }

}
