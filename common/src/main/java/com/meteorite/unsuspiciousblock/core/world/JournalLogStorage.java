package com.meteorite.unsuspiciousblock.core.world;

import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.core.journal.migration.JournalDataVersion;
import com.meteorite.unsuspiciousblock.core.journal.state.ArchaeologyJournalLogState;
import com.meteorite.unsuspiciousblock.core.persistence.migration.MigrationVersions;
import com.meteorite.unsuspiciousblock.core.persistence.migration.StorageMigration;
import com.meteorite.unsuspiciousblock.core.persistence.migration.StorageMigration.Pending;
import com.meteorite.unsuspiciousblock.core.persistence.migration.StorageMigration.RecoveryPolicy;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 考古日志 v2 分片存储——按玩家 UUID 与战利品表拆分压缩 NBT 文件。
 * 所有状态访问和写盘调度都在服务端主线程执行，普通变更延迟合并写入，删除操作立即落盘。
 */
public final class JournalLogStorage {
    private static final int FLUSH_INTERVAL_TICKS = 200;
    private static final int STOP_FLUSH_ATTEMPTS = 2;
    private static final int MAX_SHARDS_PER_FLUSH = 4;
    private static final long MAX_MANIFEST_NBT_BYTES = (long) 1024 * 1024;
    private static final long MAX_INDEX_NBT_BYTES = 8L * 1024 * 1024;
    private static final long MAX_TABLE_NBT_BYTES = 64L * 1024 * 1024;
    private static final String MANIFEST_FILE = "storage.dat";
    private static final String INDEX_FILE = "index.dat";
    private static final String PLAYERS_DIR = "players";
    private static final String TABLES_DIR = "tables";
    private static final String MIGRATION_DIR = "migration";
    private static final String LEGACY_FILE = "unsuspiciousblock_journal_logs.dat";

    private static final String TAG_MIGRATION_COMPLETE = "migration_complete";
    private static final String TAG_COMMITTED_STORAGE_VERSION = "committed_storage_version";
    private static final String TAG_PENDING_FROM_VERSION = "pending_from_version";
    private static final String TAG_PENDING_TO_VERSION = "pending_to_version";
    private static final String TAG_MIGRATED_PLAYERS = "migrated_players";
    private static final String TAG_MIGRATED_TABLES = "migrated_tables";
    private static final String TAG_MIGRATED_ENTRIES = "migrated_entries";
    private static final String TAG_PLAYER_UUID = "player_uuid";
    private static final String TAG_TABLE_ID = "table_id";
    private static final String TAG_HISTORY = "history";
    private static final String TAG_TABLES = "tables";
    private static final String TAG_FILE = "file";

    @Nullable
    private static JournalLogStorage active;

    private final MinecraftServer server;
    private final Path root;
    private final Map<UUID, ArchaeologyJournalLogState> loadedStates = new HashMap<>();
    private final Map<UUID, Set<ResourceLocation>> dirtyTables = new LinkedHashMap<>();
    private final Set<UUID> dirtyIndexes = new LinkedHashSet<>();
    private final Set<UUID> pendingUnloads = new HashSet<>();
    private int tickCounter;

    private JournalLogStorage(MinecraftServer server) {
        this.server = server;
        this.root = server.getWorldPath(LevelResource.ROOT)
                .resolve("data")
                .resolve(Constants.MOD_ID)
                .resolve("journal_logs")
                .toAbsolutePath()
                .normalize();
    }

    // 启动存储并在需要时执行旧单文件迁移
    public static synchronized void start(MinecraftServer server) {
        if (active != null && active.server == server) {
            return;
        }
        if (active != null) {
            active.flushAll();
        }
        JournalLogStorage storage = new JournalLogStorage(server);
        storage.initialize();
        active = storage;
    }

    // 停服前同步刷新，避免 dirty 分片丢失
    public static synchronized void stop(MinecraftServer server) {
        if (active == null || active.server != server) {
            return;
        }
        active.flushAll();
        active.loadedStates.clear();
        active.dirtyTables.clear();
        active.dirtyIndexes.clear();
        active.pendingUnloads.clear();
        active = null;
    }

    // 服务端 tick 驱动少量分片落盘，摊平压缩与 IO 开销
    public static void tick(MinecraftServer server) {
        JournalLogStorage storage = require(server);
        storage.tickCounter++;
        if (storage.tickCounter >= FLUSH_INTERVAL_TICKS) {
            storage.tickCounter = 0;
            storage.flushSome(MAX_SHARDS_PER_FLUSH);
        }
    }

    // 加载玩家的全部表分片；同一会话内复用状态引用
    public static ArchaeologyJournalLogState getPlayerState(MinecraftServer server, UUID playerId) {
        JournalLogStorage storage = require(server);
        storage.pendingUnloads.remove(playerId);
        return storage.loadedStates.computeIfAbsent(playerId, storage::loadPlayer);
    }

    // 玩家退出时刷新其全部 dirty 分片；失败则保留缓存并交给后续 tick 重试
    public static synchronized void unloadPlayer(MinecraftServer server, UUID playerId) {
        if (active == null || active.server != server) {
            return;
        }
        active.flushAndUnloadPlayer(playerId);
    }

    // 标记单张表为 dirty，稍后合并写入
    public static void markTableDirty(MinecraftServer server, UUID playerId, ResourceLocation tableId) {
        JournalLogStorage storage = require(server);
        storage.requeueDirty(playerId, tableId);
    }

    // 立即入口与 tick 共用同一提交过程，失败修复完全归存储调度。
    public static boolean persistTablesNow(MinecraftServer server, UUID playerId,
                                           Iterable<ResourceLocation> tableIds) {
        return commitTablesNow(server, playerId, tableIds).successful();
    }

    /** 同步提交指定表及玩家索引；失败分片和索引状态分别返回，整个范围排队修复。 */
    public static CommitResult commitTablesNow(MinecraftServer server, UUID playerId,
                                               Iterable<ResourceLocation> tableIds) {
        Set<ResourceLocation> tables = new LinkedHashSet<>();
        tableIds.forEach(tables::add);
        return require(server).commitBatch(Map.of(playerId, tables)).get(playerId);
    }

    /** 单玩家批次结果；indexCommitted 为 false 时整个批次仍留在修复队列。 */
    public record CommitResult(Set<ResourceLocation> failedTables, boolean indexCommitted) {
        public CommitResult {
            failedTables = Set.copyOf(failedTables);
        }

        public boolean successful() {
            return failedTables.isEmpty() && indexCommitted;
        }
    }

    private static synchronized JournalLogStorage require(MinecraftServer server) {
        if (active == null || active.server != server) {
            start(server);
        }
        return active;
    }

    private void initialize() {
        try {
            Files.createDirectories(this.root);
            migrateStorage(loadStorageManifest());
        } catch (IOException exception) {
            throw new IllegalStateException("初始化考古日志分片存储失败", exception);
        }
    }

    private StorageManifest loadStorageManifest() throws IOException {
        Path manifestFile = this.root.resolve(MANIFEST_FILE);
        if (!Files.exists(manifestFile)) {
            return StorageManifest.initial();
        }
        CompoundTag manifestTag;
        try {
            manifestTag = readCompressed(manifestFile, MAX_MANIFEST_NBT_BYTES);
        } catch (IOException | RuntimeException exception) {
            Constants.LOG.warn("考古日志存储 manifest 损坏，将从已知源数据重建: {}", manifestFile, exception);
            return StorageManifest.initial();
        }
        return parseStorageManifest(manifestTag);
    }

    private StorageManifest parseStorageManifest(CompoundTag tag) {
        if (tag.contains(TAG_COMMITTED_STORAGE_VERSION, Tag.TAG_INT)) {
            int committedVersion = tag.getInt(TAG_COMMITTED_STORAGE_VERSION);
            boolean hasPendingFrom = tag.contains(TAG_PENDING_FROM_VERSION, Tag.TAG_INT);
            boolean hasPendingTo = tag.contains(TAG_PENDING_TO_VERSION, Tag.TAG_INT);
            if (hasPendingFrom != hasPendingTo) {
                throw new IllegalStateException("考古日志存储 manifest 的 pending 迁移字段不完整");
            }
            Pending pending = hasPendingFrom
                    ? new Pending(tag.getInt(TAG_PENDING_FROM_VERSION), tag.getInt(TAG_PENDING_TO_VERSION))
                    : null;
            return new StorageManifest(committedVersion, pending, readMigrationResult(tag), false);
        }

        int legacyVersion = JournalDataVersion.readStorageVersion(tag);
        if (tag.getBoolean(TAG_MIGRATION_COMPLETE)) {
            return new StorageManifest(legacyVersion, null, readMigrationResult(tag), true);
        }
        if (legacyVersion == JournalDataVersion.STORAGE_VERSION_SHARDED) {
            Constants.LOG.warn("检测到旧格式未完成的 v1 -> v2 迁移标记，将按显式步骤从源数据重跑");
            return new StorageManifest(
                    JournalDataVersion.STORAGE_VERSION_SINGLE_FILE,
                    new Pending(
                            JournalDataVersion.STORAGE_VERSION_SINGLE_FILE,
                            JournalDataVersion.STORAGE_VERSION_SHARDED),
                    MigrationResult.EMPTY,
                    true);
        }
        throw new IllegalStateException("无法确定旧格式 manifest 的已提交存储版本: " + legacyVersion);
    }

    private void migrateStorage(StorageManifest manifest) throws IOException {
        StorageMigration<MigrationResult> migration = new StorageMigration<>(
                new MigrationVersions("考古日志存储", JournalDataVersion.LEGACY_STORAGE_VERSION,
                        JournalDataVersion.CURRENT_STORAGE_VERSION),
                List.of(new StorageMigration.Step<>(
                        JournalDataVersion.STORAGE_VERSION_SINGLE_FILE,
                        JournalDataVersion.STORAGE_VERSION_SHARDED,
                        RecoveryPolicy.RESTART_FROM_SOURCE,
                        this::copyStorageV1ToV2,
                        this::archiveStorageV1AfterV2Commit)));
        StorageMigration.State<MigrationResult> migrated = migration.migrate(
                new StorageMigration.State<>(manifest.committedVersion(), manifest.pending(), manifest.lastResult()),
                new StorageMigration.Checkpoint<>() {
                    @Override
                    public void writePending(Pending pending) throws IOException {
                        writePendingManifest(pending.fromVersion(), pending);
                    }

                    @Override
                    public void writeCommitted(int version, MigrationResult result) throws IOException {
                        writeCommittedManifest(version, result);
                    }
                });
        if (manifest.legacyFormat() && manifest.committedVersion() == migrated.committedVersion()) {
            writeCommittedManifest(migrated.committedVersion(), migrated.result());
        }
    }

    // v1 源文件在提交前保持不变，目标文件名确定，因此中断后可以从源数据重新生成
    private MigrationResult copyStorageV1ToV2() throws IOException {
        Path legacyFile = legacyFile();
        int players = 0;
        int tables = 0;
        int entries = 0;
        if (Files.exists(legacyFile)) {
            JournalLogSavedData legacy = JournalLogSavedData.get(this.server.overworld());
            for (Map.Entry<UUID, ArchaeologyJournalLogState> player : legacy.snapshotStatesForMigration().entrySet()) {
                players++;
                ArchaeologyJournalLogState state = player.getValue();
                for (Map.Entry<ResourceLocation, ArchaeologyJournalLogState.TableLogHistory> table
                        : state.getTables().entrySet()) {
                    writeTable(player.getKey(), table.getKey(), table.getValue());
                    tables++;
                    entries += table.getValue().getTotalEntryCount();
                }
                writeIndex(player.getKey(), state);
            }
        }
        return new MigrationResult(players, tables, entries);
    }

    private void archiveStorageV1AfterV2Commit() throws IOException {
        Path legacyFile = legacyFile();
        if (!Files.exists(legacyFile)) {
            return;
        }
        Path backupDir = checkedResolve(this.root, MIGRATION_DIR);
        Files.createDirectories(backupDir);
        Path backup = checkedResolve(backupDir, "unsuspiciousblock_journal_logs-v1.dat.bak");
        Files.move(legacyFile, backup, StandardCopyOption.REPLACE_EXISTING);
    }

    private void writePendingManifest(int committedVersion, Pending pending) throws IOException {
        CompoundTag manifest = new CompoundTag();
        putCommittedVersion(manifest, committedVersion);
        manifest.putInt(TAG_PENDING_FROM_VERSION, pending.fromVersion());
        manifest.putInt(TAG_PENDING_TO_VERSION, pending.toVersion());
        writeCompressedAtomic(this.root.resolve(MANIFEST_FILE), manifest);
    }

    private void writeCommittedManifest(int committedVersion, MigrationResult result) throws IOException {
        CompoundTag manifest = new CompoundTag();
        putCommittedVersion(manifest, committedVersion);
        manifest.putInt(TAG_MIGRATED_PLAYERS, result.players());
        manifest.putInt(TAG_MIGRATED_TABLES, result.tables());
        manifest.putInt(TAG_MIGRATED_ENTRIES, result.entries());
        writeCompressedAtomic(this.root.resolve(MANIFEST_FILE), manifest);
    }

    // 同步保留旧字段，让旧版读取时只看到最后完整提交版本
    private static void putCommittedVersion(CompoundTag manifest, int committedVersion) {
        manifest.putInt(TAG_COMMITTED_STORAGE_VERSION, committedVersion);
        manifest.putInt(JournalDataVersion.STORAGE_VERSION_TAG, committedVersion);
        manifest.putBoolean(TAG_MIGRATION_COMPLETE, true);
    }

    private static MigrationResult readMigrationResult(CompoundTag tag) {
        return new MigrationResult(
                Math.max(0, tag.getInt(TAG_MIGRATED_PLAYERS)),
                Math.max(0, tag.getInt(TAG_MIGRATED_TABLES)),
                Math.max(0, tag.getInt(TAG_MIGRATED_ENTRIES)));
    }

    private ArchaeologyJournalLogState loadPlayer(UUID playerId) {
        ArchaeologyJournalLogState state = new ArchaeologyJournalLogState();
        Path tablesDir = tablesDir(playerId);
        if (!Files.isDirectory(tablesDir)) {
            return state;
        }
        Path indexFile = playerDir(playerId).resolve(INDEX_FILE);
        if (Files.exists(indexFile)) {
            try {
                CompoundTag index = readCompressed(indexFile, MAX_INDEX_NBT_BYTES);
                if (index.getInt(JournalDataVersion.STORAGE_VERSION_TAG)
                        != JournalDataVersion.CURRENT_STORAGE_VERSION) {
                    throw new IOException("玩家日志索引版本不匹配");
                }
                ListTag tables = index.getList(TAG_TABLES, Tag.TAG_COMPOUND);
                for (int i = 0; i < tables.size(); i++) {
                    ResourceLocation tableId = ResourceLocation.tryParse(
                            tables.getCompound(i).getString(TAG_TABLE_ID));
                    if (tableId != null) {
                        loadTableInto(state, playerId, tableFile(playerId, tableId));
                    }
                }
                return state;
            } catch (IOException | RuntimeException exception) {
                Constants.LOG.error("读取玩家 {} 的考古日志索引失败，回退扫描分片", playerId, exception);
            }
        }

        try (DirectoryStream<Path> files = Files.newDirectoryStream(tablesDir, "*.dat")) {
            for (Path file : files) {
                loadTableInto(state, playerId, file);
            }
        } catch (IOException exception) {
            Constants.LOG.error("扫描玩家 {} 的考古日志分片失败", playerId, exception);
        }
        try {
            writeIndex(playerId, state);
        } catch (IOException exception) {
            Constants.LOG.error("重建玩家 {} 的考古日志索引失败", playerId, exception);
        }
        return state;
    }

    private void loadTableInto(ArchaeologyJournalLogState state, UUID playerId, Path file) {
        try {
            CompoundTag tag = readCompressed(file, MAX_TABLE_NBT_BYTES);
            if (tag.getInt(JournalDataVersion.STORAGE_VERSION_TAG)
                    != JournalDataVersion.CURRENT_STORAGE_VERSION
                    || !playerId.toString().equals(tag.getString(TAG_PLAYER_UUID))) {
                Constants.LOG.warn("跳过版本或玩家不匹配的考古日志分片: {}", file);
                return;
            }
            ResourceLocation tableId = ResourceLocation.tryParse(tag.getString(TAG_TABLE_ID));
            if (tableId == null || !tag.contains(TAG_HISTORY, Tag.TAG_COMPOUND)) {
                Constants.LOG.warn("跳过缺少表 ID 或历史数据的考古日志分片: {}", file);
                return;
            }
            int dataVersion = JournalDataVersion.readNbtVersion(tag);
            if (dataVersion > JournalDataVersion.CURRENT_NBT_VERSION) {
                Constants.LOG.warn("跳过数据版本过新的考古日志分片 {}: {}", file, dataVersion);
                return;
            }
            CompoundTag historyTag = tag.getCompound(TAG_HISTORY);
            if (!historyTag.contains(JournalDataVersion.NBT_VERSION_TAG, Tag.TAG_INT)) {
                historyTag.putInt(JournalDataVersion.NBT_VERSION_TAG, dataVersion);
            }
            state.putTable(tableId,
                    ArchaeologyJournalLogState.TableLogHistory.fromTag(historyTag));
        } catch (IOException | RuntimeException exception) {
            Constants.LOG.error("读取考古日志分片失败，已隔离到单表范围: {}", file, exception);
        }
    }

    private void flushSome(int limit) {
        if (limit <= 0 || this.dirtyTables.isEmpty() && this.dirtyIndexes.isEmpty()) {
            return;
        }
        commitBatch(drainDirtyBatch(limit));
    }

    private void flushAll() {
        for (int attempt = 0; attempt < STOP_FLUSH_ATTEMPTS
                && (!this.dirtyTables.isEmpty() || !this.dirtyIndexes.isEmpty()); attempt++) {
            flushSome(Integer.MAX_VALUE);
        }
        if (!this.dirtyTables.isEmpty() || !this.dirtyIndexes.isEmpty()) {
            Constants.LOG.error(Constants.LOG_TAG + "日志刷新失败，停止重试；分片: {}，索引: {}",
                    this.dirtyTables, this.dirtyIndexes);
        }
    }

    private Map<UUID, Set<ResourceLocation>> drainDirtyBatch(int limit) {
        Map<UUID, Set<ResourceLocation>> batch = new LinkedHashMap<>();
        int selected = 0;
        while (selected < limit && !this.dirtyTables.isEmpty()) {
            Iterator<Map.Entry<UUID, Set<ResourceLocation>>> players = this.dirtyTables.entrySet().iterator();
            Map.Entry<UUID, Set<ResourceLocation>> player = players.next();
            players.remove();
            Iterator<ResourceLocation> tables = player.getValue().iterator();
            if (!tables.hasNext()) continue;
            ResourceLocation tableId = tables.next();
            tables.remove();
            batch.computeIfAbsent(player.getKey(), ignored -> new LinkedHashSet<>()).add(tableId);
            selected++;
            if (!player.getValue().isEmpty()) this.dirtyTables.put(player.getKey(), player.getValue());
        }
        Iterator<UUID> indexes = this.dirtyIndexes.iterator();
        while (selected < limit && indexes.hasNext()) {
            UUID playerId = indexes.next();
            indexes.remove();
            batch.computeIfAbsent(playerId, ignored -> new LinkedHashSet<>());
            selected++;
        }
        return batch;
    }

    private Map<UUID, CommitResult> commitBatch(Map<UUID, Set<ResourceLocation>> batch) {
        Map<UUID, CommitResult> results = new LinkedHashMap<>();
        for (Map.Entry<UUID, Set<ResourceLocation>> player : batch.entrySet()) {
            UUID playerId = player.getKey();
            Set<ResourceLocation> tables = player.getValue();
            this.dirtyIndexes.remove(playerId);
            tables.forEach(tableId -> removeDirty(playerId, tableId));
            ArchaeologyJournalLogState state = this.loadedStates.get(playerId);
            Set<ResourceLocation> failedTables = new LinkedHashSet<>();
            boolean indexCommitted = false;
            if (state == null) {
                Constants.LOG.error(Constants.LOG_TAG + "保存玩家 {} 的日志失败：玩家缓存不存在", playerId);
                failedTables.addAll(tables);
            } else {
                for (ResourceLocation tableId : tables) {
                    try {
                        ArchaeologyJournalLogState.TableLogHistory history = state.getTable(tableId);
                        if (history == null) Files.deleteIfExists(tableFile(playerId, tableId));
                        else writeTable(playerId, tableId, history);
                    } catch (IOException exception) {
                        failedTables.add(tableId);
                        Constants.LOG.error(Constants.LOG_TAG + "保存玩家 {} 的日志表 {} 失败", playerId, tableId, exception);
                    }
                }
                // 分片失败时保留旧索引，整个批次以同一内存状态重试。
                if (failedTables.isEmpty()) {
                    try {
                        writeIndex(playerId, state);
                        indexCommitted = true;
                    } catch (IOException exception) {
                        Constants.LOG.error(Constants.LOG_TAG + "保存玩家 {} 的日志索引失败", playerId, exception);
                    }
                }
            }
            CommitResult result = new CommitResult(failedTables, indexCommitted);
            results.put(playerId, result);
            if (result.successful()) completePendingUnloadIfClean(playerId);
            else {
                tables.forEach(tableId -> requeueDirty(playerId, tableId));
                if (tables.isEmpty()) this.dirtyIndexes.add(playerId);
            }
        }
        return results;
    }

    private void flushAndUnloadPlayer(UUID playerId) {
        this.pendingUnloads.add(playerId);
        Set<ResourceLocation> tables = this.dirtyTables.remove(playerId);
        if ((tables == null || tables.isEmpty()) && !this.dirtyIndexes.contains(playerId)) {
            completePendingUnloadIfClean(playerId);
            return;
        }
        commitBatch(Map.of(playerId, tables == null ? Set.of() : tables));
    }

    private void completePendingUnloadIfClean(UUID playerId) {
        if (this.pendingUnloads.contains(playerId) && !this.dirtyTables.containsKey(playerId)
                && !this.dirtyIndexes.contains(playerId)) {
            this.pendingUnloads.remove(playerId);
            this.loadedStates.remove(playerId);
        }
    }

    private void requeueDirty(UUID playerId, ResourceLocation tableId) {
        this.dirtyTables.computeIfAbsent(playerId, ignored -> new LinkedHashSet<>()).add(tableId);
    }

    private void removeDirty(UUID playerId, ResourceLocation tableId) {
        Set<ResourceLocation> tables = this.dirtyTables.get(playerId);
        if (tables == null) {
            return;
        }
        tables.remove(tableId);
        if (tables.isEmpty()) {
            this.dirtyTables.remove(playerId);
        }
    }

    private void writeTable(UUID playerId, ResourceLocation tableId,
                            ArchaeologyJournalLogState.TableLogHistory history) throws IOException {
        CompoundTag tag = new CompoundTag();
        tag.putInt(JournalDataVersion.STORAGE_VERSION_TAG, JournalDataVersion.CURRENT_STORAGE_VERSION);
        tag.putInt(JournalDataVersion.NBT_VERSION_TAG, JournalDataVersion.CURRENT_NBT_VERSION);
        tag.putString(TAG_PLAYER_UUID, playerId.toString());
        tag.putString(TAG_TABLE_ID, tableId.toString());
        tag.put(TAG_HISTORY, history.toTag());
        writeCompressedAtomic(tableFile(playerId, tableId), tag);
    }

    private void writeIndex(UUID playerId, ArchaeologyJournalLogState state) throws IOException {
        CompoundTag index = new CompoundTag();
        index.putInt(JournalDataVersion.STORAGE_VERSION_TAG, JournalDataVersion.CURRENT_STORAGE_VERSION);
        index.putString(TAG_PLAYER_UUID, playerId.toString());
        ListTag tables = new ListTag();
        for (ResourceLocation tableId : state.getTables().keySet()) {
            CompoundTag table = new CompoundTag();
            table.putString(TAG_TABLE_ID, tableId.toString());
            table.putString(TAG_FILE, shardFileName(tableId));
            tables.add(table);
        }
        index.put(TAG_TABLES, tables);
        writeCompressedAtomic(playerDir(playerId).resolve(INDEX_FILE), index);
    }

    private Path legacyFile() {
        return this.server.getWorldPath(LevelResource.ROOT)
                .resolve("data")
                .resolve(LEGACY_FILE)
                .toAbsolutePath()
                .normalize();
    }

    private Path playerDir(UUID playerId) {
        return checkedResolve(checkedResolve(this.root, PLAYERS_DIR), playerId.toString());
    }

    private Path tablesDir(UUID playerId) {
        return checkedResolve(playerDir(playerId), TABLES_DIR);
    }

    private Path tableFile(UUID playerId, ResourceLocation tableId) {
        return checkedResolve(tablesDir(playerId), shardFileName(tableId));
    }

    private static String shardFileName(ResourceLocation tableId) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(tableId.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(hash.length * 2 + 4);
            for (byte value : hash) {
                result.append(String.format("%02x", value & 0xff));
            }
            return result.append(".dat").toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JVM 缺少 SHA-256", exception);
        }
    }

    private static Path checkedResolve(Path parent, String child) {
        Path normalizedParent = parent.toAbsolutePath().normalize();
        Path resolved = normalizedParent.resolve(child).toAbsolutePath().normalize();
        if (!resolved.startsWith(normalizedParent)) {
            throw new IllegalArgumentException("考古日志路径越界: " + child);
        }
        return resolved;
    }

    private static CompoundTag readCompressed(Path path, long maxBytes) throws IOException {
        return NbtIo.readCompressed(path, NbtAccounter.create(maxBytes));
    }

    private static void writeCompressedAtomic(Path target, CompoundTag tag) throws IOException {
        Files.createDirectories(target.getParent());
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        NbtIo.writeCompressed(tag, temporary);
        try {
            Files.move(temporary, target,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * 最近一次迁移写入的数据统计。
     */
    private record MigrationResult(int players, int tables, int entries) {
        private static final MigrationResult EMPTY = new MigrationResult(0, 0, 0);
    }

    /**
     * 存储 manifest 的解析结果。
     */
    private record StorageManifest(
            int committedVersion,
            @Nullable Pending pending,
            MigrationResult lastResult,
            boolean legacyFormat) {
        private static StorageManifest initial() {
            return new StorageManifest(
                    JournalDataVersion.LEGACY_STORAGE_VERSION,
                    null,
                    MigrationResult.EMPTY,
                    false);
        }
    }

}
