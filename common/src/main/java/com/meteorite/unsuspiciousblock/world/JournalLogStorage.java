package com.meteorite.unsuspiciousblock.world;

import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.journal.state.ArchaeologyJournalLogState;
import com.meteorite.unsuspiciousblock.journal.state.NbtDataVersion;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 考古日志 v2 分片存储——按玩家 UUID 与战利品表拆分压缩 NBT 文件。
 * 所有状态访问和写盘调度都在服务端主线程执行，普通变更延迟合并写入，删除操作立即落盘。
 */
public final class JournalLogStorage {
    public static final int STORAGE_VERSION = 2;

    private static final int FLUSH_INTERVAL_TICKS = 200;
    private static final int MAX_SHARDS_PER_FLUSH = 4;
    private static final String MANIFEST_FILE = "storage.dat";
    private static final String INDEX_FILE = "index.dat";
    private static final String PLAYERS_DIR = "players";
    private static final String TABLES_DIR = "tables";
    private static final String MIGRATION_DIR = "migration";
    private static final String LEGACY_FILE = "unsuspiciousblock_journal_logs.dat";

    private static final String TAG_STORAGE_VERSION = "storage_version";
    private static final String TAG_MIGRATION_COMPLETE = "migration_complete";
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
        return storage.loadedStates.computeIfAbsent(playerId, storage::loadPlayer);
    }

    // 标记单张表为 dirty，稍后合并写入
    public static void markTableDirty(MinecraftServer server, UUID playerId, ResourceLocation tableId) {
        JournalLogStorage storage = require(server);
        storage.dirtyTables.computeIfAbsent(playerId, ignored -> new HashSet<>()).add(tableId);
    }

    // 标记玩家当前所有表为 dirty，供旧玩家 NBT 合并迁移使用
    public static void markAllDirty(MinecraftServer server, UUID playerId) {
        JournalLogStorage storage = require(server);
        ArchaeologyJournalLogState state = storage.loadedStates.get(playerId);
        if (state == null || state.getTables().isEmpty()) {
            return;
        }
        storage.dirtyTables.computeIfAbsent(playerId, ignored -> new HashSet<>())
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
            return true;
        } catch (IOException exception) {
            Constants.LOG.error("立即保存玩家 {} 的考古日志表 {} 失败", playerId, tableId, exception);
            markTableDirty(server, playerId, tableId);
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
            Path manifest = this.root.resolve(MANIFEST_FILE);
            if (Files.exists(manifest)) {
                CompoundTag tag = readCompressed(manifest);
                int version = tag.getInt(TAG_STORAGE_VERSION);
                if (version > STORAGE_VERSION) {
                    throw new IllegalStateException("考古日志存储版本过新: " + version);
                }
                if (version == STORAGE_VERSION && tag.getBoolean(TAG_MIGRATION_COMPLETE)) {
                    return;
                }
            }
            migrateLegacyStorage();
        } catch (IOException exception) {
            throw new IllegalStateException("初始化考古日志分片存储失败", exception);
        }
    }

    // 迁移过程可重复执行：目标文件名确定，manifest 最后提交，旧文件最后归档
    private void migrateLegacyStorage() throws IOException {
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

        CompoundTag manifest = new CompoundTag();
        manifest.putInt(TAG_STORAGE_VERSION, STORAGE_VERSION);
        manifest.putBoolean(TAG_MIGRATION_COMPLETE, true);
        manifest.putInt("migrated_players", players);
        manifest.putInt("migrated_tables", tables);
        manifest.putInt("migrated_entries", entries);
        writeCompressedAtomic(this.root.resolve(MANIFEST_FILE), manifest);

        if (Files.exists(legacyFile)) {
            Path backupDir = checkedResolve(this.root, MIGRATION_DIR);
            Files.createDirectories(backupDir);
            Path backup = checkedResolve(backupDir, "unsuspiciousblock_journal_logs-v1.dat.bak");
            Files.move(legacyFile, backup, StandardCopyOption.REPLACE_EXISTING);
        }
        Constants.LOG.info("考古日志存储迁移完成: {} 名玩家, {} 张表, {} 条记录", players, tables, entries);
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
                CompoundTag index = readCompressed(indexFile);
                if (index.getInt(TAG_STORAGE_VERSION) != STORAGE_VERSION) {
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
            CompoundTag tag = readCompressed(file);
            if (tag.getInt(TAG_STORAGE_VERSION) != STORAGE_VERSION
                    || !playerId.toString().equals(tag.getString(TAG_PLAYER_UUID))) {
                Constants.LOG.warn("跳过版本或玩家不匹配的考古日志分片: {}", file);
                return;
            }
            ResourceLocation tableId = ResourceLocation.tryParse(tag.getString(TAG_TABLE_ID));
            if (tableId == null || !tag.contains(TAG_HISTORY, Tag.TAG_COMPOUND)) {
                Constants.LOG.warn("跳过缺少表 ID 或历史数据的考古日志分片: {}", file);
                return;
            }
            state.putTable(tableId,
                    ArchaeologyJournalLogState.TableLogHistory.fromTag(tag.getCompound(TAG_HISTORY)));
        } catch (IOException | RuntimeException exception) {
            Constants.LOG.error("读取考古日志分片失败，已隔离到单表范围: {}", file, exception);
        }
    }

    private void flushSome(int limit) {
        if (limit <= 0 || this.dirtyTables.isEmpty()) {
            return;
        }
        List<ShardKey> batch = new ArrayList<>(Math.min(limit, 64));
        outer:
        for (Map.Entry<UUID, Set<ResourceLocation>> player : this.dirtyTables.entrySet()) {
            for (ResourceLocation tableId : player.getValue()) {
                batch.add(new ShardKey(player.getKey(), tableId));
                if (batch.size() >= limit) {
                    break outer;
                }
            }
        }
        for (ShardKey key : batch) {
            flushShard(key.playerId(), key.tableId());
        }
    }

    private void flushAll() {
        // 失败分片保留 dirty 并记录日志，但停服时不无限重试阻塞关服
        flushSome(Integer.MAX_VALUE);
    }

    private void flushShard(UUID playerId, ResourceLocation tableId) {
        ArchaeologyJournalLogState state = this.loadedStates.get(playerId);
        ArchaeologyJournalLogState.TableLogHistory history = state != null ? state.getTable(tableId) : null;
        try {
            if (history == null) {
                Files.deleteIfExists(tableFile(playerId, tableId));
            } else {
                writeTable(playerId, tableId, history);
            }
            if (state != null) {
                writeIndex(playerId, state);
            }
            removeDirty(playerId, tableId);
        } catch (IOException exception) {
            Constants.LOG.error("保存玩家 {} 的考古日志表 {} 失败", playerId, tableId, exception);
        }
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
        tag.putInt(TAG_STORAGE_VERSION, STORAGE_VERSION);
        tag.putInt(NbtDataVersion.TAG, NbtDataVersion.CURRENT);
        tag.putString(TAG_PLAYER_UUID, playerId.toString());
        tag.putString(TAG_TABLE_ID, tableId.toString());
        tag.put(TAG_HISTORY, history.toTag());
        writeCompressedAtomic(tableFile(playerId, tableId), tag);
    }

    private void writeIndex(UUID playerId, ArchaeologyJournalLogState state) throws IOException {
        CompoundTag index = new CompoundTag();
        index.putInt(TAG_STORAGE_VERSION, STORAGE_VERSION);
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

    private static CompoundTag readCompressed(Path path) throws IOException {
        return NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap());
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

    private record ShardKey(UUID playerId, ResourceLocation tableId) {
    }
}
