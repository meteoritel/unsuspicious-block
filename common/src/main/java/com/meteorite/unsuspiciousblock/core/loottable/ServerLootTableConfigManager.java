package com.meteorite.unsuspiciousblock.core.loottable;

import com.google.gson.JsonParseException;
import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.core.journal.catalog.ArchaeologyJournalServerCatalog;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.LootTableNames;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.LootTableTranslationStore;
import com.meteorite.unsuspiciousblock.core.network.journal.LootTableManagementHandler;
import com.meteorite.unsuspiciousblock.platform.Services;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ReloadableServerRegistries;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

/** 服务端世界配置的生命周期、持久化提交和目录刷新。 */
public final class ServerLootTableConfigManager {
    private static ReloadableServerRegistries.Holder observedRegistries;
    private static volatile Snapshot current = new Snapshot(0, LootTrackingSettings.DEFAULT, false);
    private static boolean hasWorldFile;
    private static boolean refreshRequested;
    private static Path configFile;

    /** 世界会话中的不可变配置与编译后的规则。 */
    public record Snapshot(long revision, LootTrackingSettings settings, LootTrackingRules rules, boolean canSave) {
        private Snapshot(long revision, LootTrackingSettings settings, boolean canSave) {
            this(revision, settings, new LootTrackingRules(settings), canSave);
        }
    }

    /** 服务端编辑的可展示结果。 */
    public enum EditResult { SAVED, UNCHANGED, CONFLICT, INVALID, READ_ONLY, WRITE_FAILED }

    private ServerLootTableConfigManager() {}

    public static Snapshot snapshot() {
        return current;
    }

    public static boolean isCatalogPending() {
        return refreshRequested || ArchaeologyJournalServerCatalog.hasRebuildFailed();
    }

    /** 首次构建目录前解析并提交可能需要迁移的世界设置。 */
    public static void start(MinecraftServer server) {
        observedRegistries = server.reloadableRegistries();
        configFile = server.getWorldPath(LevelResource.ROOT).resolve(LootTrackingConfigFile.DIRECTORY)
                .resolve(LootTrackingConfigFile.NAME);
        current = new Snapshot(0, LootTrackingSettings.EMPTY, false);
        load();
        LootTableTranslationStore.load(server);
    }

    /** 新版本发布以磁盘提交为准，避免 GUI 与目录读取到只存在于内存的更改。 */
    public static EditResult save(MinecraftServer server, long revision, LootTrackingSettings settings) {
        if (!current.canSave()) return EditResult.READ_ONLY;
        if (revision != current.revision() || server.reloadableRegistries() != observedRegistries) {
            return EditResult.CONFLICT;
        }
        if (settings.equals(current.settings())) return EditResult.UNCHANGED;
        try {
            LootTrackingConfigFile.save(configFile, settings);
        } catch (IOException exception) {
            Constants.LOG.error("{}Cannot save loot tracking config {}", Constants.LOG_TAG, configFile, exception);
            return EditResult.WRITE_FAILED;
        }
        Snapshot next = new Snapshot(current.revision() + 1, settings, true);
        if (next.rules().affectsCatalogComparedTo(current.rules())) {
            refreshRequested = true;
        }
        current = next;
        return EditResult.SAVED;
    }

    /** 合并同一周期的配置提交和数据包重载；构建失败可由下一次重载重试。 */
    public static void tick(MinecraftServer server) {
        ReloadableServerRegistries.Holder registries = server.reloadableRegistries();
        boolean resourceReload = registries != observedRegistries;
        if (resourceReload) {
            observedRegistries = registries;
            load();
            LootTableTranslationStore.load(server);
        }
        if (!resourceReload && !refreshRequested) return;
        refreshRequested = false;
        ArchaeologyJournalServerCatalog.rebuild(server);
        LootTableManagementHandler.broadcast(server);
    }

    public static void stop() {
        configFile = null;
        hasWorldFile = false;
        refreshRequested = false;
        observedRegistries = null;
        current = new Snapshot(0, LootTrackingSettings.DEFAULT, false);
        LootTableTranslationStore.unload();
        LootTableNames.clear();
    }

    private static void load() {
        try {
            try {
                publish(LootTrackingConfigFile.load(configFile), true);
                hasWorldFile = true;
                return;
            } catch (NoSuchFileException exception) {
                if (hasWorldFile) throw exception;
            }

            LegacyLootTrackingConfig source = Services.LEGACY_LOOT_TRACKING_CONFIG.readLegacy(configFile.getParent());
            LootTrackingSettings settings = source == null ? LootTrackingSettings.DEFAULT : source.migrate();
            try {
                LootTrackingConfigFile.save(configFile, settings);
            } catch (IOException exception) {
                if (source != null) publish(source.asReadOnlySettings(), false);
                throw exception;
            }
            hasWorldFile = true;
            publish(settings, true);
        } catch (IOException | JsonParseException | IllegalArgumentException exception) {
            Constants.LOG.error("{}Cannot activate loot tracking config {}", Constants.LOG_TAG, configFile, exception);
            publish(current.settings(), false);
        }
    }

    private static void publish(LootTrackingSettings settings, boolean canSave) {
        if (!settings.equals(current.settings()) || canSave != current.canSave()) {
            current = new Snapshot(current.revision() + 1, settings, canSave);
        }
    }
}
