package com.meteorite.unsuspiciousblock.world;

import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.journal.migration.JournalDataVersion;
import com.meteorite.unsuspiciousblock.journal.state.ArchaeologyJournalLogState;
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
import java.util.ArrayList;
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

    // 标记玩家当前所有表为 dirty，供旧玩家 NBT 合并迁移使用
    public static void markAllDirty(MinecraftServer server, UUID playerId) {
        JournalLogStorage storage = require(server);
        ArchaeologyJournalLogState state = storage.loadedStates.get(playerId);
        if (state == null || state.getTables().isEmpty()) {
            return;
        }
        storage.dirtyTables.computeIfAbsent(playerId, ignored -> new LinkedHashSet<>())
                .addAll(state.getTables().keySet());
    }

    // 删除后立即重写单表，避免旧 dirty 写入在重启后恢复已删除条目
    public static boolean persistTableNow(MinecraftServer server, UUID playerId, ResourceLocation tableId) {
        JournalLogStorage storage = require(server);
        storage.removeDirty(playerId, tableId);
        ArchaeologyJournalLogState state = storage.loadedStates.get(playerId);
        ArchaeologyJournalLogState.TableLogHistory history = state != null ? state.getTable(tableId) : null;
        try {
            if (history == null) {
                Files.deleteIfExists(storage.tableFile(playerId, tableId));
            } else {
                storage.writeTable(playerId, tableId, history);
            }
            storage.writeIndex(playerId, state != null ? state : new ArchaeologyJournalLogState());
            storage.completePendingUnloadIfClean(playerId);
            return true;
        } catch (IOException exception) {
            Constants.LOG.error("立即保存玩家 {} 的考古日志表 {} 失败", playerId, tableId, exception);
            markTableDirty(server, playerId, tableId);
            return false;
        }
    }

    // 一次性保存多张表并在最后只提交一次 index；用于源数据确认清理前的迁移提交
    public static boolean persistTablesNow(MinecraftServer server, UUID playerId,
                                           Iterable<ResourceLocation> tableIds) {
        JournalLogStorage storage = require(server);
        LinkedHashSet<ResourceLocation> pendingTables = new LinkedHashSet<>();
        tableIds.forEach(pendingTables::add);
        ArchaeologyJournalLogState state = storage.loadedStates.get(playerId);
        if (state == null) {
            Constants.LOG.error("立即保存玩家 {} 的迁移日志失败：玩家缓存不存在", playerId);
            return false;
        }

        pendingTables.forEach(tableId -> storage.removeDirty(playerId, tableId));
        try {
            for (ResourceLocation tableId : pendingTables) {
                ArchaeologyJournalLogState.TableLogHistory history = state.getTable(tableId);
                if (history != null) {
                    storage.writeTable(playerId, tableId, history);
                }
            }
            storage.writeIndex(playerId, state);
            storage.completePendingUnloadIfClean(playerId);
            return true;
        } catch (IOException exception) {
            Constants.LOG.error("立即保存玩家 {} 的迁移日志失败", playerId, exception);
            pendingTables.forEach(tableId -> storage.requeueDirty(playerId, tableId));
            return false;
        }
    }

    // 清空全部日志后立即删除已知分片并写入空索引
    public static boolean persistClearAllNow(MinecraftServer server, UUID playerId,
                                             Iterable<ResourceLocation> removedTables) {
        JournalLogStorage storage = require(server);
        storage.dirtyTables.remove(playerId);
        boolean success = true;
        for (ResourceLocation tableId : removedTables) {
            try {
                Files.deleteIfExists(storage.tableFile(playerId, tableId));
            } catch (IOException exception) {
                success = false;
                Constants.LOG.error("删除玩家 {} 的考古日志表 {} 失败", playerId, tableId, exception);
            }
        }
        try {
            ArchaeologyJournalLogState state = storage.loadedStates.get(playerId);
            storage.writeIndex(playerId, state != null ? state : new ArchaeologyJournalLogState());
        } catch (IOException exception) {
            success = false;
            Constants.LOG.error("立即清空玩家 {} 的考古日志分片失败", playerId, exception);
        }
        if (success) {
            storage.completePendingUnloadIfClean(playerId);
        }
        return success;
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
            PendingMigration pending = hasPendingFrom
                    ? new PendingMigration(tag.getInt(TAG_PENDING_FROM_VERSION), tag.getInt(TAG_PENDING_TO_VERSION))
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
                    new PendingMigration(
                            JournalDataVersion.STORAGE_VERSION_SINGLE_FILE,
                            JournalDataVersion.STORAGE_VERSION_SHARDED),
                    MigrationResult.EMPTY,
                    true);
        }
        throw new IllegalStateException("无法确定旧格式 manifest 的已提交存储版本: " + legacyVersion);
    }

    // committedVersion 始终表示最后完整提交版本；pending 只标识确切的待恢复步骤
    private void migrateStorage(StorageManifest manifest) throws IOException {
        validateStorageManifest(manifest);
        int committedVersion = manifest.committedVersion();
        PendingMigration pending = manifest.pending();
        MigrationResult lastResult = manifest.lastResult();
        boolean manifestNeedsRewrite = manifest.legacyFormat();

        // 恢复“已提交但清理未完成”的迁移；清理动作必须可重复执行
        cleanupCommittedMigrations(committedVersion);
        while (committedVersion < JournalDataVersion.CURRENT_STORAGE_VERSION) {
            StorageMigrationStep step = storageMigrationStepFrom(committedVersion);
            boolean recovering = pending != null;
            if (recovering) {
                validatePendingStep(pending, step);
                if (step.recoveryPolicy() == RecoveryPolicy.ABORT_IF_INTERRUPTED) {
                    throw new IllegalStateException("考古日志存储迁移 v" + step.fromVersion()
                            + " -> v" + step.toVersion() + " 不支持自动重放，需要人工恢复");
                }
                Constants.LOG.warn("恢复中断的考古日志存储迁移: v{} -> v{}，策略={}",
                        step.fromVersion(), step.toVersion(), step.recoveryPolicy());
            } else {
                pending = new PendingMigration(step.fromVersion(), step.toVersion());
                writePendingManifest(committedVersion, pending);
            }

            lastResult = step.action().run();
            committedVersion = step.toVersion();
            pending = null;
            writeCommittedManifest(committedVersion, lastResult);
            step.cleanup().run();
            manifestNeedsRewrite = false;
            Constants.LOG.info("考古日志存储迁移完成: v{} -> v{}, {} 名玩家, {} 张表, {} 条记录",
                    step.fromVersion(), step.toVersion(), lastResult.players(),
                    lastResult.tables(), lastResult.entries());
        }

        if (manifestNeedsRewrite) {
            writeCommittedManifest(committedVersion, lastResult);
        }
    }

    private void validateStorageManifest(StorageManifest manifest) {
        int committedVersion = manifest.committedVersion();
        if (committedVersion < JournalDataVersion.LEGACY_STORAGE_VERSION) {
            throw new IllegalStateException("考古日志存储已提交版本无效: " + committedVersion);
        }
        if (committedVersion > JournalDataVersion.CURRENT_STORAGE_VERSION) {
            throw new IllegalStateException("考古日志存储版本过新: " + committedVersion);
        }
        PendingMigration pending = manifest.pending();
        if (pending == null) {
            return;
        }
        if (pending.fromVersion() != committedVersion) {
            throw new IllegalStateException("考古日志 pending 迁移起点与已提交版本不一致");
        }
        if (pending.toVersion() != pending.fromVersion() + 1) {
            throw new IllegalStateException("考古日志 pending 迁移必须是连续版本: v"
                    + pending.fromVersion() + " -> v" + pending.toVersion());
        }
        if (pending.toVersion() > JournalDataVersion.CURRENT_STORAGE_VERSION) {
            throw new IllegalStateException("考古日志 pending 迁移目标版本过新: " + pending.toVersion());
        }
    }

    private static void validatePendingStep(PendingMigration pending, StorageMigrationStep step) {
        if (pending.fromVersion() != step.fromVersion() || pending.toVersion() != step.toVersion()) {
            throw new IllegalStateException("考古日志 pending 迁移与已注册步骤不匹配: v"
                    + pending.fromVersion() + " -> v" + pending.toVersion());
        }
    }

    // 新增版本时必须显式登记来源、目标、恢复策略、迁移动作和提交后清理动作
    private StorageMigrationStep storageMigrationStepFrom(int version) {
        return switch (version) {
            case JournalDataVersion.STORAGE_VERSION_SINGLE_FILE -> new StorageMigrationStep(
                    JournalDataVersion.STORAGE_VERSION_SINGLE_FILE,
                    JournalDataVersion.STORAGE_VERSION_SHARDED,
                    RecoveryPolicy.RESTART_FROM_SOURCE,
                    this::copyStorageV1ToV2,
                    this::archiveStorageV1AfterV2Commit);
            default -> throw new IllegalStateException(
                    "考古日志存储缺少 v" + version + " -> v" + (version + 1) + " 迁移步骤");
        };
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

    // 提交成功但归档中断时，下次启动继续执行幂等清理
    private void cleanupCommittedMigrations(int committedVersion) throws IOException {
        int fromVersion = JournalDataVersion.LEGACY_STORAGE_VERSION;
        while (fromVersion < committedVersion) {
            StorageMigrationStep step = storageMigrationStepFrom(fromVersion);
            if (step.toVersion() > committedVersion) {
                throw new IllegalStateException("考古日志存储清理步骤超过已提交版本: v"
                        + step.fromVersion() + " -> v" + step.toVersion());
            }
            step.cleanup().run();
            fromVersion = step.toVersion();
        }
    }

    private void writePendingManifest(int committedVersion, PendingMigration pending) throws IOException {
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
        if (limit <= 0 || this.dirtyTables.isEmpty()) {
            return;
        }
        flushBatch(drainDirtyBatch(limit));
    }

    private void flushAll() {
        // 失败分片保留 dirty 并记录日志，但停服时不无限重试阻塞关服
        flushSome(Integer.MAX_VALUE);
    }

    private List<ShardKey> drainDirtyBatch(int limit) {
        List<ShardKey> batch = new ArrayList<>(Math.min(limit, 64));
        while (batch.size() < limit && !this.dirtyTables.isEmpty()) {
            Iterator<Map.Entry<UUID, Set<ResourceLocation>>> players = this.dirtyTables.entrySet().iterator();
            Map.Entry<UUID, Set<ResourceLocation>> player = players.next();
            players.remove();

            Iterator<ResourceLocation> tables = player.getValue().iterator();
            if (!tables.hasNext()) {
                continue;
            }
            ResourceLocation tableId = tables.next();
            tables.remove();
            batch.add(new ShardKey(player.getKey(), tableId));
            if (!player.getValue().isEmpty()) {
                this.dirtyTables.put(player.getKey(), player.getValue());
            }
        }
        return batch;
    }

    private void flushBatch(List<ShardKey> batch) {
        Map<UUID, List<ShardKey>> writtenByPlayer = new LinkedHashMap<>();
        for (ShardKey key : batch) {
            ArchaeologyJournalLogState state = this.loadedStates.get(key.playerId());
            if (state == null) {
                Constants.LOG.error("保存玩家 {} 的考古日志表 {} 失败：玩家缓存不存在",
                        key.playerId(), key.tableId());
                requeueDirty(key.playerId(), key.tableId());
                continue;
            }
            ArchaeologyJournalLogState.TableLogHistory history = state.getTable(key.tableId());
            try {
                if (history == null) {
                    Files.deleteIfExists(tableFile(key.playerId(), key.tableId()));
                } else {
                    writeTable(key.playerId(), key.tableId(), history);
                }
                writtenByPlayer.computeIfAbsent(key.playerId(), ignored -> new ArrayList<>()).add(key);
            } catch (IOException exception) {
                Constants.LOG.error("保存玩家 {} 的考古日志表 {} 失败",
                        key.playerId(), key.tableId(), exception);
                requeueDirty(key.playerId(), key.tableId());
            }
        }

        for (Map.Entry<UUID, List<ShardKey>> player : writtenByPlayer.entrySet()) {
            ArchaeologyJournalLogState state = this.loadedStates.get(player.getKey());
            if (state == null) {
                player.getValue().forEach(key -> requeueDirty(key.playerId(), key.tableId()));
                continue;
            }
            try {
                writeIndex(player.getKey(), state);
                completePendingUnloadIfClean(player.getKey());
            } catch (IOException exception) {
                Constants.LOG.error("保存玩家 {} 的考古日志索引失败", player.getKey(), exception);
                player.getValue().forEach(key -> requeueDirty(key.playerId(), key.tableId()));
            }
        }
    }

    private void flushAndUnloadPlayer(UUID playerId) {
        this.pendingUnloads.add(playerId);
        Set<ResourceLocation> tables = this.dirtyTables.remove(playerId);
        if (tables == null || tables.isEmpty()) {
            completePendingUnloadIfClean(playerId);
            return;
        }
        List<ShardKey> batch = new ArrayList<>(tables.size());
        for (ResourceLocation tableId : tables) {
            batch.add(new ShardKey(playerId, tableId));
        }
        flushBatch(batch);
    }

    private void completePendingUnloadIfClean(UUID playerId) {
        if (this.pendingUnloads.contains(playerId) && !this.dirtyTables.containsKey(playerId)) {
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
     * 存储迁移中断后的恢复策略。
     */
    private enum RecoveryPolicy {
        RESTART_FROM_SOURCE,
        ABORT_IF_INTERRUPTED
    }

    /**
     * 执行一个存储布局迁移步骤。
     */
    @FunctionalInterface
    private interface MigrationAction {
        MigrationResult run() throws IOException;
    }

    /**
     * 清理已经提交的迁移源数据；实现必须可重复执行。
     */
    @FunctionalInterface
    private interface MigrationCleanup {
        void run() throws IOException;
    }

    /**
     * 一个显式登记的连续存储迁移步骤。
     */
    private record StorageMigrationStep(
            int fromVersion,
            int toVersion,
            RecoveryPolicy recoveryPolicy,
            MigrationAction action,
            MigrationCleanup cleanup) {
    }

    /**
     * manifest 中尚未提交的确切迁移步骤。
     */
    private record PendingMigration(int fromVersion, int toVersion) {
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
            @Nullable PendingMigration pending,
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

    /**
     * 待刷新的玩家日志分片标识。
     */
    private record ShardKey(UUID playerId, ResourceLocation tableId) {
    }
}
