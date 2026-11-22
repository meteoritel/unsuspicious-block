package com.meteorite.unsuspiciousblock.core.loottable;

import com.google.gson.JsonParseException;
import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.core.journal.catalog.ArchaeologyJournalServerCatalog;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.LootTableNames;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.LootTableCatalog.TableDefinition;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.LootTableTranslationStore;
import com.meteorite.unsuspiciousblock.core.loottable.signature.SignatureExcludedComponents;
import com.meteorite.unsuspiciousblock.core.network.journal.LootTableManagementHandler;
import com.meteorite.unsuspiciousblock.platform.Services;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ReloadableServerRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 服务端世界配置的生命周期、持久化提交和目录刷新。 */
public final class ServerLootTableConfigManager {
    private static ReloadableServerRegistries.Holder observedRegistries;
    private static volatile Snapshot current = new Snapshot(0, LootTrackingSettings.DEFAULT, false);
    private static boolean hasWorldFile;
    private static boolean refreshRequested;
    private static long resourceRevision;
    private static Path configFile;

    /** 世界会话中的不可变配置与编译后的规则。 */
    public record Snapshot(long revision, LootTrackingSettings settings, LootTrackingRules rules, boolean canSave) {
        private Snapshot(long revision, LootTrackingSettings settings, boolean canSave) {
            this(revision, settings, new LootTrackingRules(settings), canSave);
        }
    }

    /** 服务端编辑的可展示结果。 */
    public enum EditResult { SAVED, UNCHANGED, CONFLICT, INVALID, READ_ONLY, WRITE_FAILED, PREVIEWED, PREVIEW_FAILED, APPLIED, APPLY_FAILED, SUPERSEDED }

    /** 配置应用终态与目录构建期间的等待状态。 */
    public enum Application { PENDING, APPLIED, FAILED }

    /** 基于当前目录的规则影响，预览与提交共用配置和资源版本。 */
    public record Preview(List<ResourceLocation> added, List<ResourceLocation> removed,
                          List<ResourceLocation> changedPositions, List<String> unmatchedSelectors,
                          List<ResourceLocation> mergedVariants, List<ResourceLocation> parents) {}

    private ServerLootTableConfigManager() {}

    public static Snapshot snapshot() {
        return current;
    }

    public static Application application() {
        if (refreshRequested) return Application.PENDING;
        return ArchaeologyJournalServerCatalog.hasRebuildFailed() ? Application.FAILED : Application.APPLIED;
    }

    /** 运行时只读取与已发布目录相匹配的设置。 */
    public static LootTrackingSettings effectiveSettings() {
        Snapshot applied = ArchaeologyJournalServerCatalog.appliedConfiguration();
        return applied == null ? LootTrackingSettings.DEFAULT : applied.settings();
    }

    public static long appliedRevision() {
        Snapshot applied = ArchaeologyJournalServerCatalog.appliedConfiguration();
        return applied == null ? 0 : applied.revision();
    }

    public static long resourceRevision() {
        return resourceRevision;
    }

    public static boolean matchesDraft(MinecraftServer server, long revision, long resources) {
        return revision == current.revision() && resources == resourceRevision
                && server.reloadableRegistries() == observedRegistries;
    }

    /** 计算草稿的实际目录影响，调用方已校验编辑权限和版本。 */
    public static Preview preview(MinecraftServer server, LootTrackingSettings settings, ResourceLocation selected) {
        Snapshot candidate = new Snapshot(current.revision(), settings, current.canSave());
        var result = ArchaeologyJournalServerCatalog.preview(server, candidate);
        var currentTables = ArchaeologyJournalServerCatalog.staticTables();
        var nextTables = result.staticTables();
        List<ResourceLocation> changedPositions = nextTables.keySet().stream()
                .filter(currentTables::containsKey)
                .filter(id -> !ArchaeologyJournalServerCatalog.parents(id)
                        .equals(result.parents().getOrDefault(id, List.of()))
                        || !Objects.equals(ArchaeologyJournalServerCatalog.catalogCategory(id),
                        result.structure().rootCategories().get(id)))
                .sorted().toList();
        Set<ResourceLocation> available = result.session().referenceGraph().nodes();
        List<String> unmatched = candidate.rules().unmatchedSelectors(available);
        List<ResourceLocation> merged = settings.signatureExclusions().equals(effectiveSettings().signatureExclusions())
                ? List.of() : mergedVariants(settings, currentTables, nextTables, result.foldedItems());
        return new Preview(difference(nextTables.keySet(), currentTables.keySet()),
                difference(currentTables.keySet(), nextTables.keySet()), changedPositions, unmatched, merged,
                selected == null ? List.of() : result.parents().getOrDefault(selected, List.of()));
    }

    private static List<ResourceLocation> mergedVariants(LootTrackingSettings settings,
            Map<ResourceLocation, TableDefinition> current, Map<ResourceLocation, TableDefinition> next,
            Map<ResourceLocation, Set<ResourceLocation>> foldedItems) {
        SignatureExcludedComponents exclusions = SignatureExcludedComponents.resolve(settings.signatureExclusions());
        return next.keySet().stream()
                .filter(foldedItems::containsKey)
                .filter(id -> ArchaeologyJournalServerCatalog.hasMergedVariants(
                        current.get(id), next.get(id), foldedItems.get(id), exclusions))
                .sorted().toList();
    }

    private static List<ResourceLocation> difference(Set<ResourceLocation> first, Set<ResourceLocation> second) {
        return first.stream().filter(id -> !second.contains(id)).sorted().toList();
    }

    /** 首次构建目录前解析并提交可能需要迁移的世界设置。 */
    public static void start(MinecraftServer server) {
        observedRegistries = server.reloadableRegistries();
        resourceRevision++;
        configFile = server.getWorldPath(LevelResource.ROOT).resolve(LootTrackingConfigFile.DIRECTORY)
                .resolve(LootTrackingConfigFile.NAME);
        current = new Snapshot(0, LootTrackingSettings.EMPTY, false);
        load();
        LootTableTranslationStore.load(server);
    }

    /** 新版本发布以磁盘提交为准，避免 GUI 与目录读取到只存在于内存的更改。 */
    public static EditResult save(MinecraftServer server, long revision, long resources, LootTrackingSettings settings) {
        if (!current.canSave()) return EditResult.READ_ONLY;
        if (!matchesDraft(server, revision, resources)) return EditResult.CONFLICT;
        if (settings.equals(current.settings())) return EditResult.UNCHANGED;
        try {
            LootTrackingConfigFile.save(configFile, settings);
        } catch (IOException exception) {
            Constants.LOG.error("{}Cannot save loot tracking config {}", Constants.LOG_TAG, configFile, exception);
            return EditResult.WRITE_FAILED;
        }
        Snapshot next = new Snapshot(current.revision() + 1, settings, true);
        Snapshot applied = ArchaeologyJournalServerCatalog.appliedConfiguration();
        if (applied == null || next.rules().affectsCatalogComparedTo(applied.rules())
                || !ArchaeologyJournalServerCatalog.canExecute(server)) {
            refreshRequested = true;
        } else {
            refreshRequested = false;
            ArchaeologyJournalServerCatalog.applyWithoutRebuild(next);
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
            resourceRevision++;
            load();
            LootTableTranslationStore.load(server);
            LootTableManagementHandler.refreshNames(server);
        }
        if (!resourceReload && !refreshRequested) return;
        apply(server, false);
    }

    /** 使用与配置保存和重载相同的发布点手动重建测量。 */
    public static boolean rebuild(MinecraftServer server) {
        if (server.reloadableRegistries() != observedRegistries) {
            observedRegistries = server.reloadableRegistries();
            resourceRevision++;
            load();
            LootTableTranslationStore.load(server);
            LootTableManagementHandler.refreshNames(server);
        }
        return apply(server, true);
    }

    private static boolean apply(MinecraftServer server, boolean discardMeasurements) {
        refreshRequested = false;
        boolean applied = ArchaeologyJournalServerCatalog.rebuild(server, current, discardMeasurements);
        LootTableManagementHandler.broadcast(server);
        return applied;
    }

    public static void stop() {
        configFile = null;
        hasWorldFile = false;
        refreshRequested = false;
        observedRegistries = null;
        current = new Snapshot(0, LootTrackingSettings.DEFAULT, false);
        LootTableTranslationStore.unload();
        LootTableManagementHandler.stop();
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
