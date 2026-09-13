package com.meteorite.unsuspiciousblock.pan;

import com.meteorite.unsuspiciousblock.platform.Services;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/***
 * 每维度的闪烁的光账本——记录现存淘洗点的位置与来源，并保存已经完成世界生成判定的区块。
 * <p>
 * 由于存盘持久的实体散落在各个区块中，无法廉价地直接统计全维度数量，因此上限计数、间距校验
 * 与“区块是否已判定过”都由账本集中维护。账本与实体实际状态不一致时以实体为准：实体消散时
 * 主动注销自身，实体恢复 tick 时补登记；不以方块区块加载状态推断实体是否存在。
 */
public final class ShimmerLedger extends SavedData {
    /***
     * 闪烁的光来源——决定是否受数量上限与寿命约束。
     */
    public enum Source {
        // 自然生成：受每维度上限约束，拥有随机寿命
        NATURAL,
        // 世界生成：供玩家探索发现，不消散也不计入上限
        WORLDGEN
    }

    /***
     * 账本条目——一个现存闪烁的光的位置与来源。
     */
    public record Entry(BlockPos pos, Source source) {
    }

    private static final String FILE_NAME = "unsuspiciousblock_shimmer";
    private static final String ENTRIES_TAG = "entries";
    private static final String UUID_TAG = "uuid";
    private static final String POS_TAG = "pos";
    private static final String SOURCE_TAG = "source";
    private static final String ROLLED_CHUNKS_TAG = "rolled_chunks";
    private static final String NEXT_ATTEMPT_TAG = "next_attempt";

    private static final SavedData.Factory<ShimmerLedger> FACTORY = new SavedData.Factory<>(
            ShimmerLedger::new,
            ShimmerLedger::load,
            DataFixTypes.LEVEL);

    private final Map<UUID, Entry> entries = new LinkedHashMap<>();
    private final Map<Long, Set<UUID>> entriesByChunk = new HashMap<>();
    private int naturalCount;
    private final Set<Long> rolledChunks = new HashSet<>();
    private final Map<UUID, Long> expirations = new HashMap<>();
    private final Set<UUID> expired = new HashSet<>();
    private final Map<Long, Long> cooldowns = new HashMap<>();
    private long nextAttempt;
    private long lastMaintenanceTime = Long.MIN_VALUE;

    private ShimmerLedger() {
    }

    // 取得该维度的账本，不存在时创建
    public static ShimmerLedger of(ServerLevel level) {
        ShimmerLedger ledger = level.getDataStorage().computeIfAbsent(FACTORY, FILE_NAME);
        if (ledger.lastMaintenanceTime == level.getGameTime()) return ledger;
        ledger.lastMaintenanceTime = level.getGameTime();
        // 旧账本没有截止时间，迁移时最多保留一个配置寿命上限。
        ledger.expirations.replaceAll((uuid, time) -> {
            if (time < 0) {
                ledger.setDirty();
                return level.getGameTime() + Services.PANNING_CONFIG.getMaxLifetimeTicks();
            }
            return time;
        });
        ledger.expireDue(level.getGameTime());
        return ledger;
    }

    private static ShimmerLedger load(CompoundTag tag, HolderLookup.Provider registries) {
        ShimmerLedger ledger = new ShimmerLedger();
        ListTag list = tag.getList(ENTRIES_TAG, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entryTag = list.getCompound(i);
            if (!entryTag.hasUUID(UUID_TAG) || !entryTag.contains(POS_TAG)) {
                continue;
            }
            Source source = entryTag.getBoolean(SOURCE_TAG) ? Source.WORLDGEN : Source.NATURAL;
            ledger.register(entryTag.getUUID(UUID_TAG), BlockPos.of(entryTag.getLong(POS_TAG)), source);
            if (source == Source.NATURAL) {
                ledger.expirations.put(entryTag.getUUID(UUID_TAG),
                        entryTag.contains("expires_at") ? entryTag.getLong("expires_at") : -1L);
            }
        }
        ListTag expiredTags = tag.getList("expired", Tag.TAG_COMPOUND);
        for (int i = 0; i < expiredTags.size(); i++) {
            CompoundTag expiredTag = expiredTags.getCompound(i);
            if (expiredTag.hasUUID(UUID_TAG)) ledger.expired.add(expiredTag.getUUID(UUID_TAG));
        }
        ListTag cooldownTags = tag.getList("cooldowns", Tag.TAG_COMPOUND);
        for (int i = 0; i < cooldownTags.size(); i++) {
            CompoundTag cooldown = cooldownTags.getCompound(i);
            ledger.cooldowns.put(cooldown.getLong("chunk"), cooldown.getLong("until"));
        }
        for (long chunkKey : tag.getLongArray(ROLLED_CHUNKS_TAG)) {
            ledger.rolledChunks.add(chunkKey);
        }
        ledger.nextAttempt = tag.getLong(NEXT_ATTEMPT_TAG);
        ledger.setDirty(false);
        return ledger;
    }

    @Override
    public @NotNull CompoundTag save(@NotNull CompoundTag tag, HolderLookup.@NotNull Provider registries) {
        ListTag list = new ListTag();
        this.entries.forEach((uuid, entry) -> {
            CompoundTag entryTag = new CompoundTag();
            entryTag.putUUID(UUID_TAG, uuid);
            entryTag.putLong(POS_TAG, BlockPos.asLong(entry.pos().getX(), entry.pos().getY(), entry.pos().getZ()));
            entryTag.putBoolean(SOURCE_TAG, entry.source() == Source.WORLDGEN);
            if (entry.source() == Source.NATURAL) {
                entryTag.putLong("expires_at", this.expirations.getOrDefault(uuid, -1L));
            }
            list.add(entryTag);
        });
        tag.put(ENTRIES_TAG, list);
        tag.putLongArray(ROLLED_CHUNKS_TAG, this.rolledChunks.stream().mapToLong(Long::longValue).toArray());
        tag.putLong(NEXT_ATTEMPT_TAG, this.nextAttempt);
        ListTag expiredTags = new ListTag();
        this.expired.forEach(uuid -> {
            CompoundTag value = new CompoundTag();
            value.putUUID(UUID_TAG, uuid);
            expiredTags.add(value);
        });
        tag.put("expired", expiredTags);
        ListTag cooldownTags = new ListTag();
        this.cooldowns.forEach((chunk, until) -> {
            CompoundTag value = new CompoundTag();
            value.putLong("chunk", chunk);
            value.putLong("until", until);
            cooldownTags.add(value);
        });
        tag.put("cooldowns", cooldownTags);
        return tag;
    }

    // ========== 条目维护 ==========

    // 登记或校正实体，并同步维护空间索引与自然生成计数。
    private void register(UUID uuid, BlockPos pos, Source source) {
        Entry updated = new Entry(pos.immutable(), source);
        if (updated.equals(this.entries.get(uuid))) {
            return;
        }
        this.unregister(uuid);
        this.entries.put(uuid, updated);
        this.entriesByChunk.computeIfAbsent(new ChunkPos(pos).toLong(), key -> new HashSet<>()).add(uuid);
        if (source == Source.NATURAL) {
            this.naturalCount++;
        }
        this.setDirty();
    }

    // 实体消散时注销自身；区块卸载不注销。
    public void unregister(UUID uuid) {
        boolean removedExpiry = this.expirations.remove(uuid) != null;
        if (this.expired.remove(uuid) || removedExpiry) this.setDirty();
        Entry removed = this.entries.remove(uuid);
        if (removed == null) {
            return;
        }
        long chunkKey = new ChunkPos(removed.pos()).toLong();
        Set<UUID> bucket = this.entriesByChunk.get(chunkKey);
        bucket.remove(uuid);
        if (bucket.isEmpty()) {
            this.entriesByChunk.remove(chunkKey);
        }
        if (removed.source() == Source.NATURAL) {
            this.naturalCount--;
        }
        this.setDirty();
    }

    // 自然生成数量为增量维护，不扫描世界生成记录。
    public int countNatural() {
        return this.naturalCount;
    }

    // 截止时间与实体共用；过期标记保留到实体加载并注销，防止旧实体补登记复活。
    public void register(UUID uuid, BlockPos pos, Source source, long expiresAt) {
        if (this.expired.contains(uuid)) return;
        this.register(uuid, pos, source);
        if (source == Source.NATURAL && !Long.valueOf(expiresAt).equals(this.expirations.put(uuid, expiresAt))) {
            this.setDirty();
        }
    }

    public boolean isExpired(UUID uuid) {
        return this.expired.contains(uuid);
    }

    public long expirationOf(UUID uuid, long fallback) {
        return this.expirations.getOrDefault(uuid, fallback);
    }

    // 只扫描有寿命的点；卸载实体到期也立即释放数量和空间约束。
    private void expireDue(long gameTime) {
        var due = this.expirations.entrySet().stream()
                .filter(entry -> entry.getValue() <= gameTime).map(Map.Entry::getKey).toList();
        for (UUID uuid : due) {
            this.unregister(uuid);
            this.expired.add(uuid);
            this.setDirty();
        }
        if (this.cooldowns.entrySet().removeIf(entry -> entry.getValue() <= gameTime)) this.setDirty();
    }

    // 仅采空触发；重叠区域取较晚的截止时间。
    public void startHarvestCooldown(BlockPos pos, long gameTime, int duration) {
        ChunkPos center = new ChunkPos(pos);
        for (int x = center.x - 1; x <= center.x + 1; x++) {
            for (int z = center.z - 1; z <= center.z + 1; z++) {
                this.cooldowns.merge(ChunkPos.asLong(x, z), gameTime + duration, Math::max);
            }
        }
        this.setDirty();
    }

    public boolean isCoolingDown(ChunkPos chunk, long gameTime) {
        return this.cooldowns.getOrDefault(chunk.toLong(), 0L) > gameTime;
    }

    // 调试统计只读取账本和已加载实体，不加载区块或实体文件。
    public int countWorldgen() {
        return this.entries.size() - this.naturalCount;
    }

    public Set<UUID> expiredSnapshot() {
        return Set.copyOf(this.expired);
    }

    public Map<UUID, Entry> entriesInChunk(ChunkPos chunk) {
        Map<UUID, Entry> result = new LinkedHashMap<>();
        for (UUID uuid : this.entriesByChunk.getOrDefault(chunk.toLong(), Set.of())) {
            result.put(uuid, this.entries.get(uuid));
        }
        return Map.copyOf(result);
    }

    public long cooldownRemaining(ChunkPos chunk, long gameTime) {
        return Math.max(0L, this.cooldowns.getOrDefault(chunk.toLong(), 0L) - gameTime);
    }

    // 调试清除与采空相同的 3×3 区域，不影响其它区域。
    public int clearHarvestCooldown(BlockPos pos) {
        ChunkPos center = new ChunkPos(pos);
        int removed = 0;
        for (int x = center.x - 1; x <= center.x + 1; x++) {
            for (int z = center.z - 1; z <= center.z + 1; z++) {
                if (this.cooldowns.remove(ChunkPos.asLong(x, z)) != null) removed++;
            }
        }
        if (removed > 0) this.setDirty();
        return removed;
    }

    // 调试强制过期也保留标记，使尚未加载的实体按正式路径消散。
    public boolean expireNatural(UUID uuid) {
        Entry entry = this.entries.get(uuid);
        if (entry == null || entry.source() != Source.NATURAL) return false;
        this.unregister(uuid);
        this.expired.add(uuid);
        this.setDirty();
        return true;
    }

    // 仅查询间距范围覆盖的区块，不扫描整个维度的记录。
    public boolean isTooClose(BlockPos pos, int minSpacingBlocks) {
        if (minSpacingBlocks <= 0) {
            return false;
        }
        long minSpacingSquared = (long) minSpacingBlocks * minSpacingBlocks;
        int minX = (pos.getX() - minSpacingBlocks) >> 4;
        int maxX = (pos.getX() + minSpacingBlocks) >> 4;
        int minZ = (pos.getZ() - minSpacingBlocks) >> 4;
        int maxZ = (pos.getZ() + minSpacingBlocks) >> 4;
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                Set<UUID> bucket = this.entriesByChunk.get(ChunkPos.asLong(x, z));
                if (bucket == null) {
                    continue;
                }
                for (UUID uuid : bucket) {
                    BlockPos other = this.entries.get(uuid).pos();
                    long deltaX = (long) other.getX() - pos.getX();
                    long deltaZ = (long) other.getZ() - pos.getZ();
                    if (deltaX * deltaX + deltaZ * deltaZ < minSpacingSquared) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    // ========== 世界生成判定 ==========

    // 该区块是否已经完成过世界生成判定；判定过的区块不会再次生成
    public boolean isChunkRolled(ChunkPos chunkPos) {
        return this.rolledChunks.contains(chunkPos.toLong());
    }

    // 标记区块已完成世界生成判定
    public void markChunkRolled(ChunkPos chunkPos) {
        if (this.rolledChunks.add(chunkPos.toLong())) {
            this.setDirty();
        }
    }

    // ========== 生成节拍 ==========

    public long getNextAttempt() {
        return this.nextAttempt;
    }

    // 安排下一次自然生成尝试
    public void scheduleNextAttempt(long gameTime, int intervalTicks) {
        this.nextAttempt = gameTime + Math.max(1, intervalTicks);
        this.setDirty();
    }

    @Nullable
    public Entry entryOf(UUID uuid) {
        return this.entries.get(uuid);
    }
}
