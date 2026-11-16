package com.meteorite.unsuspiciousblock.core.world.aura;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;

import java.util.BitSet;
import java.util.Set;
import java.util.function.LongConsumer;

/** 单个世界的瞬态光环场；调用与源变更均由服务端主线程串行执行。 */
public final class BlockAuraField {
    private static final int SECTION_BITS = 4;
    private static final int SECTION_SIZE = 1 << SECTION_BITS;
    private static final int SECTION_MASK = SECTION_SIZE - 1;
    private static final int SECTION_VOLUME = SECTION_SIZE * SECTION_SIZE * SECTION_SIZE;

    private final int radius;
    private final Set<AuraEffect> effects;
    private final Long2ObjectMap<LongSet> sourcesByChunk = new Long2ObjectOpenHashMap<>();
    private final Long2ObjectMap<Coverage> coverageBySection = new Long2ObjectOpenHashMap<>();

    /** 同一光环场中的源共用范围与效果集合，覆盖按并集合并。 */
    public BlockAuraField(int radius, Set<AuraEffect> effects) {
        this.radius = radius;
        this.effects = Set.copyOf(effects);
    }

    /** 登记源；同一坐标重复登记保持幂等。 */
    public void addSource(BlockPos pos) {
        long chunk = ChunkPos.asLong(pos.getX() >> SECTION_BITS, pos.getZ() >> SECTION_BITS);
        if (sourcesByChunk.computeIfAbsent(chunk, ignored -> new LongOpenHashSet()).add(pos.asLong())) {
            long source = pos.asLong();
            forEachSourceSection(pos, key -> {
                Coverage coverage = coverageBySection.computeIfAbsent(key, Coverage::new);
                coverage.sources.add(source);
                coverage.cachedBits = null;
            });
        }
    }

    /** 移除源并失效其覆盖；重建时保留其他重叠源的效果。 */
    public void removeSource(BlockPos pos) {
        long chunk = ChunkPos.asLong(pos.getX() >> SECTION_BITS, pos.getZ() >> SECTION_BITS);
        LongSet sources = sourcesByChunk.get(chunk);
        if (sources == null || !sources.remove(pos.asLong())) {
            return;
        }
        if (sources.isEmpty()) {
            sourcesByChunk.remove(chunk);
        }
        removeCoverage(pos);
    }

    /** 区块卸载或重新扫描时移除其拥有的全部源，包括相邻 section 中的覆盖。 */
    public void removeChunk(ChunkPos chunk) {
        LongSet sources = sourcesByChunk.remove(chunk.toLong());
        if (sources != null) {
            LongIterator iterator = sources.iterator();
            while (iterator.hasNext()) {
                removeCoverage(BlockPos.of(iterator.nextLong()));
            }
        }
    }

    /** 缓存有效时仅进行 section 查找与位查询，不读取世界方块。 */
    public boolean hasEffect(BlockPos pos, AuraEffect effect) {
        return effects.contains(effect) && isCovered(pos.getX(), pos.getY(), pos.getZ());
    }

    /**
     * 判断线段是否从区外进入区域。区内起点可穿过起始连续覆盖并离开，离开后再次进入会被拒绝。
     * 格子采用半开区间，空 section 整段跳过；端点也参与判定。
     */
    public boolean blocksEntry(Vec3 from, Vec3 to, AuraEffect effect) {
        if (!effects.contains(effect) || coverageBySection.isEmpty()) {
            return false;
        }
        int x = floor(from.x);
        int y = floor(from.y);
        int z = floor(from.z);
        int endX = floor(to.x);
        int endY = floor(to.y);
        int endZ = floor(to.z);
        double dx = to.x - from.x;
        double dy = to.y - from.y;
        double dz = to.z - from.z;
        boolean hasLeftCoverage = false;

        while (true) {
            Coverage section = coverageBySection.get(SectionPos.asLong(x >> SECTION_BITS, y >> SECTION_BITS, z >> SECTION_BITS));
            boolean isCovered = section != null && section.bits().get(localIndex(x, y, z));
            if (isCovered && hasLeftCoverage) {
                return true;
            }
            hasLeftCoverage |= !isCovered;
            if (x == endX && y == endY && z == endZ) {
                return false;
            }

            int size = section == null ? SECTION_SIZE : 1;
            int boundaryX = nextBoundary(x, dx, size);
            int boundaryY = nextBoundary(y, dy, size);
            int boundaryZ = nextBoundary(z, dz, size);
            double tx = dx == 0.0 ? Double.POSITIVE_INFINITY : (boundaryX - from.x) / dx;
            double ty = dy == 0.0 ? Double.POSITIVE_INFINITY : (boundaryY - from.y) / dy;
            double tz = dz == 0.0 ? Double.POSITIVE_INFINITY : (boundaryZ - from.z) / dz;
            double next = Math.min(tx, Math.min(ty, tz));
            if (next >= 1.0) {
                return hasLeftCoverage && isCovered(endX, endY, endZ);
            }
            x = tx == next ? boundaryX - (dx < 0.0 ? 1 : 0) : stayWithinCell(from.x + dx * next, x, size);
            y = ty == next ? boundaryY - (dy < 0.0 ? 1 : 0) : stayWithinCell(from.y + dy * next, y, size);
            z = tz == next ? boundaryZ - (dz < 0.0 ? 1 : 0) : stayWithinCell(from.z + dz * next, z, size);
        }
    }

    private boolean isCovered(int x, int y, int z) {
        Coverage coverage = coverageBySection.get(SectionPos.asLong(x >> SECTION_BITS, y >> SECTION_BITS, z >> SECTION_BITS));
        return coverage != null && coverage.bits().get(localIndex(x, y, z));
    }

    private void removeCoverage(BlockPos pos) {
        long source = pos.asLong();
        forEachSourceSection(pos, key -> {
            Coverage coverage = coverageBySection.get(key);
            coverage.sources.remove(source);
            coverage.cachedBits = null;
            if (coverage.sources.isEmpty()) {
                coverageBySection.remove(key);
            }
        });
    }

    private void forEachSourceSection(BlockPos pos, LongConsumer action) {
        for (int x = (pos.getX() - radius) >> SECTION_BITS; x <= (pos.getX() + radius) >> SECTION_BITS; x++) {
            for (int y = (pos.getY() - radius) >> SECTION_BITS; y <= (pos.getY() + radius) >> SECTION_BITS; y++) {
                for (int z = (pos.getZ() - radius) >> SECTION_BITS; z <= (pos.getZ() + radius) >> SECTION_BITS; z++) {
                    action.accept(SectionPos.asLong(x, y, z));
                }
            }
        }
    }

    private static int floor(double coordinate) {
        return (int) Math.floor(coordinate);
    }

    private static int nextBoundary(int coordinate, double direction, int size) {
        int start = Math.floorDiv(coordinate, size) * size;
        return direction > 0.0 ? start + size : start;
    }

    // 未跨界轴仍属于当前格子或 section，舍入结果也必须留在其中，防止下一步回退。
    private static int stayWithinCell(double position, int coordinate, int size) {
        if (size == 1) {
            return coordinate;
        }
        int start = Math.floorDiv(coordinate, size) * size;
        return Math.clamp(floor(position), start, start + size - 1);
    }

    private static int localIndex(int x, int y, int z) {
        return ((y & SECTION_MASK) * SECTION_SIZE + (z & SECTION_MASK)) * SECTION_SIZE + (x & SECTION_MASK);
    }

    private final class Coverage {
        private final long sectionKey;
        private final LongSet sources = new LongOpenHashSet();
        private BitSet cachedBits;

        private Coverage(long sectionKey) {
            this.sectionKey = sectionKey;
        }

        private BitSet bits() {
            if (cachedBits == null) {
                cachedBits = buildCoverage();
            }
            return cachedBits;
        }

        // 差分使稠密源的重建成本随源数量与 section 体积增长，避免逐源反复展开覆盖体积。
        private BitSet buildCoverage() {
            int[] counts = new int[SECTION_VOLUME];
            int originX = SectionPos.x(sectionKey) * SECTION_SIZE;
            int originY = SectionPos.y(sectionKey) * SECTION_SIZE;
            int originZ = SectionPos.z(sectionKey) * SECTION_SIZE;
            LongIterator iterator = sources.iterator();
            while (iterator.hasNext()) {
                BlockPos source = BlockPos.of(iterator.nextLong());
                int minX = Math.max(0, source.getX() - radius - originX);
                int minY = Math.max(0, source.getY() - radius - originY);
                int minZ = Math.max(0, source.getZ() - radius - originZ);
                int maxX = Math.min(SECTION_SIZE, source.getX() + radius + 1 - originX);
                int maxY = Math.min(SECTION_SIZE, source.getY() + radius + 1 - originY);
                int maxZ = Math.min(SECTION_SIZE, source.getZ() + radius + 1 - originZ);
                addCorner(counts, minX, minY, minZ, 1);
                addCorner(counts, maxX, minY, minZ, -1);
                addCorner(counts, minX, maxY, minZ, -1);
                addCorner(counts, minX, minY, maxZ, -1);
                addCorner(counts, maxX, maxY, minZ, 1);
                addCorner(counts, maxX, minY, maxZ, 1);
                addCorner(counts, minX, maxY, maxZ, 1);
                addCorner(counts, maxX, maxY, maxZ, -1);
            }
            for (int y = 0; y < SECTION_SIZE; y++) {
                for (int z = 0; z < SECTION_SIZE; z++) {
                    for (int x = 1; x < SECTION_SIZE; x++) {
                        counts[localIndex(x, y, z)] += counts[localIndex(x - 1, y, z)];
                    }
                }
            }
            for (int y = 0; y < SECTION_SIZE; y++) {
                for (int z = 1; z < SECTION_SIZE; z++) {
                    for (int x = 0; x < SECTION_SIZE; x++) {
                        counts[localIndex(x, y, z)] += counts[localIndex(x, y, z - 1)];
                    }
                }
            }
            for (int y = 1; y < SECTION_SIZE; y++) {
                for (int z = 0; z < SECTION_SIZE; z++) {
                    for (int x = 0; x < SECTION_SIZE; x++) {
                        counts[localIndex(x, y, z)] += counts[localIndex(x, y - 1, z)];
                    }
                }
            }
            BitSet result = new BitSet(SECTION_VOLUME);
            for (int index = 0; index < SECTION_VOLUME; index++) {
                if (counts[index] > 0) {
                    result.set(index);
                }
            }
            return result;
        }

        private static void addCorner(int[] counts, int x, int y, int z, int amount) {
            if (x < SECTION_SIZE && y < SECTION_SIZE && z < SECTION_SIZE) {
                counts[localIndex(x, y, z)] += amount;
            }
        }
    }
}
