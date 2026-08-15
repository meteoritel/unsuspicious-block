package com.meteorite.unsuspiciousblock.client.ui.support;

import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.client.ui.panel.RightPageContainer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.storage.LevelResource;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/**
 * 考古笔记 UI 偏好的 per-world 客户端持久化。
 * <p>
 * 与 {@link ArchaeologyJournalLogLocalStore} 共用存储目录，但使用独立文件，
 * 保存玩家在该存档下的 UI 偏好：收藏集合、上次选中条目、目录/日志排序状态、搜索文本等。
 * 服务端权威数据（解锁进度、日志）不在此处保存。
 */
public final class JournalUiPreferencesStore {

    private static final String STORAGE_DIR = "unsuspiciousblock_journal_logs";
    private static final String STORAGE_FILE = "journal_ui_preferences.dat";

    @Nullable
    private static volatile Path loadedPath;
    @Nullable
    private static ClientPacketListener trackedConnection;
    private static volatile boolean dirty;
    private static volatile boolean loaded;

    private JournalUiPreferencesStore() {
    }

    // 每个客户端 tick 调用：刷新连接、确保加载、必要时刷盘
    public static synchronized void tick() {
        refreshConnection();
        ensureLoaded();
        if (dirty && loadedPath != null) {
            save();
            dirty = false;
        }
    }

    // 标记偏好已修改，下次 tick 时刷盘
    public static void markDirty() {
        dirty = true;
    }

    // 断线/退世界前主动刷盘，避免丢失最近一次修改
    public static synchronized void flushIfDirty() {
        if (!dirty) {
            return;
        }
        refreshConnection();
        ensureLoaded();
        if (loadedPath != null) {
            save();
            dirty = false;
        }
    }

    private static void refreshConnection() {
        Minecraft minecraft = Minecraft.getInstance();
        ClientPacketListener connection = minecraft.getConnection();
        if (connection == trackedConnection) {
            return;
        }
        trackedConnection = connection;
        // 连接切换：清空已加载状态，下次 tick 重新从新世界的文件加载
        loadedPath = null;
        loaded = false;
    }

    private static void ensureLoaded() {
        Path currentPath = resolveCurrentPath();
        if (currentPath == null) {
            return;
        }
        if (currentPath.equals(loadedPath) && loaded) {
            return;
        }
        loadedPath = currentPath;
        load(currentPath);
        loaded = true;
    }

    // 从 NBT 读取偏好并写回 ClientState 的 static 字段
    private static void load(Path path) {
        if (!Files.exists(path)) {
            return;
        }
        CompoundTag tag;
        try (InputStream inputStream = Files.newInputStream(path)) {
            tag = NbtIo.readCompressed(inputStream, NbtAccounter.unlimitedHeap());
        } catch (IOException e) {
            Constants.LOG.warn("读取考古笔记 UI 偏好失败: {}", path, e);
            return;
        }
        applyToClientState(tag);
    }

    // 将 ClientState 当前内存中的偏好序列化到文件
    private static void save() {
        Path path = loadedPath;
        if (path == null) {
            return;
        }
        try {
            Files.createDirectories(path.getParent());
            try (OutputStream outputStream = Files.newOutputStream(path)) {
                NbtIo.writeCompressed(snapshotFromClientState(), outputStream);
            }
        } catch (IOException e) {
            Constants.LOG.warn("保存考古笔记 UI 偏好失败: {}", path, e);
        }
    }

    // 从 NBT 还原到 ClientState 内存字段
    private static void applyToClientState(CompoundTag tag) {
        // 加载期间抑制 markDirty，避免加载即触发回写
        ArchaeologyJournalClientState.setPersistenceSuppress(true);
        try {
            if (tag.contains("lastSelectedTable", Tag.TAG_STRING)) {
                ResourceLocation id = ResourceLocation.tryParse(tag.getString("lastSelectedTable"));
                ArchaeologyJournalClientState.rememberLastSelectedTable(id);
            }
            if (tag.contains("lastCatalogSortOrder", Tag.TAG_STRING)) {
                try {
                    ArchaeologyJournalClientState.setLastCatalogSortOrder(
                            CatalogSorter.SortOrder.valueOf(tag.getString("lastCatalogSortOrder")));
                } catch (IllegalArgumentException ignored) {
                    // 枚举名变更时静默丢弃
                }
            }
            if (tag.contains("lastCatalogSortDescending", Tag.TAG_BYTE)) {
                ArchaeologyJournalClientState.setLastCatalogSortDescending(tag.getBoolean("lastCatalogSortDescending"));
            }
            if (tag.contains("lastCatalogHideLocked", Tag.TAG_BYTE)) {
                ArchaeologyJournalClientState.setLastCatalogHideLocked(tag.getBoolean("lastCatalogHideLocked"));
            }
            if (tag.contains("lastCatalogSearchText", Tag.TAG_STRING)) {
                ArchaeologyJournalClientState.setLastCatalogSearchText(tag.getString("lastCatalogSearchText"));
            }
            if (tag.contains("lastLogSortDescending", Tag.TAG_BYTE)) {
                ArchaeologyJournalClientState.setLastLogSortDescending(tag.getBoolean("lastLogSortDescending"));
            }
            if (tag.contains("lastLogGroupMode", Tag.TAG_STRING)) {
                try {
                    ArchaeologyJournalClientState.setLastLogGroupMode(
                            LogGrouper.GroupMode.valueOf(tag.getString("lastLogGroupMode")));
                } catch (IllegalArgumentException ignored) {
                }
            }
            if (tag.contains("lastRightPageTab", Tag.TAG_STRING)) {
                try {
                    ArchaeologyJournalClientState.setLastRightPageTab(
                            RightPageContainer.Tab.valueOf(tag.getString("lastRightPageTab")));
                } catch (IllegalArgumentException ignored) {
                }
            }
            // 收藏集合
            Set<ResourceLocation> favSet = new HashSet<>();
            if (tag.contains("favorites", Tag.TAG_LIST)) {
                for (Tag entry : tag.getList("favorites", Tag.TAG_STRING)) {
                    ResourceLocation id = ResourceLocation.tryParse(entry.getAsString());
                    if (id != null) {
                        favSet.add(id);
                    }
                }
            }
            ArchaeologyJournalClientState.replaceFavorites(favSet);
        } finally {
            ArchaeologyJournalClientState.setPersistenceSuppress(false);
        }
    }

    // 从 ClientState 内存字段抓取快照
    private static CompoundTag snapshotFromClientState() {
        CompoundTag tag = new CompoundTag();
        // 各 getter 背后是 volatile 字段，必须先用局部变量捕获单次快照再判空，
        // 避免「判空时读到非 null、取值时读到 null」的竞态 NPE
        ResourceLocation lastSelectedTableId = ArchaeologyJournalClientState.getLastSelectedTableId();
        if (lastSelectedTableId != null) {
            tag.putString("lastSelectedTable", lastSelectedTableId.toString());
        }
        CatalogSorter.SortOrder lastCatalogSortOrder = ArchaeologyJournalClientState.getLastCatalogSortOrder();
        if (lastCatalogSortOrder != null) {
            tag.putString("lastCatalogSortOrder", lastCatalogSortOrder.name());
        }
        tag.putBoolean("lastCatalogSortDescending", ArchaeologyJournalClientState.getLastCatalogSortDescending());
        tag.putBoolean("lastCatalogHideLocked", ArchaeologyJournalClientState.getLastCatalogHideLocked());
        String lastCatalogSearchText = ArchaeologyJournalClientState.getLastCatalogSearchText();
        if (lastCatalogSearchText != null) {
            tag.putString("lastCatalogSearchText", lastCatalogSearchText);
        }
        tag.putBoolean("lastLogSortDescending", ArchaeologyJournalClientState.getLastLogSortDescending());
        LogGrouper.GroupMode lastLogGroupMode = ArchaeologyJournalClientState.getLastLogGroupMode();
        if (lastLogGroupMode != null) {
            tag.putString("lastLogGroupMode", lastLogGroupMode.name());
        }
        RightPageContainer.Tab lastRightPageTab = ArchaeologyJournalClientState.getLastRightPageTab();
        if (lastRightPageTab != null) {
            tag.putString("lastRightPageTab", lastRightPageTab.name());
        }
        // 收藏集合
        var favoritesList = new net.minecraft.nbt.ListTag();
        for (ResourceLocation id : ArchaeologyJournalClientState.snapshotFavorites()) {
            if (id != null) {
                favoritesList.add(net.minecraft.nbt.StringTag.valueOf(id.toString()));
            }
        }
        tag.put("favorites", favoritesList);
        return tag;
    }

    @Nullable
    private static Path resolveCurrentPath() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return null;
        }
        if (minecraft.hasSingleplayerServer() && minecraft.getSingleplayerServer() != null) {
            return minecraft.getSingleplayerServer()
                    .getWorldPath(LevelResource.ROOT)
                    .resolve(STORAGE_DIR)
                    .resolve(minecraft.player.getUUID().toString())
                    .resolve(STORAGE_FILE);
        }

        ServerData serverData = minecraft.getCurrentServer();
        if (serverData == null) {
            return null;
        }

        String serverKey = sanitize(serverData.ip);
        return minecraft.gameDirectory.toPath()
                .resolve(STORAGE_DIR)
                .resolve(serverKey)
                .resolve(minecraft.player.getUUID().toString())
                .resolve(STORAGE_FILE);
    }

    private static String sanitize(String value) {
        if (value == null || value.isBlank()) {
            return "unknown_server";
        }
        return value.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
