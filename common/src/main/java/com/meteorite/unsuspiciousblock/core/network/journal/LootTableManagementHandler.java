package com.meteorite.unsuspiciousblock.core.network.journal;

import com.google.gson.JsonParseException;
import com.meteorite.unsuspiciousblock.core.journal.catalog.ArchaeologyJournalServerCatalog;
import com.meteorite.unsuspiciousblock.core.loottable.LootTrackingConfigFile;
import com.meteorite.unsuspiciousblock.core.loottable.LootTrackingSettings;
import com.meteorite.unsuspiciousblock.core.loottable.ServerLootTableConfigManager;
import com.meteorite.unsuspiciousblock.core.loottable.ServerLootTableConfigManager.EditResult;
import com.meteorite.unsuspiciousblock.core.loottable.ServerLootTableConfigManager.Snapshot;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.LootTableNames;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.LootTableTranslationStore;
import com.meteorite.unsuspiciousblock.core.journal.state.ArchaeologyJournalState;
import com.meteorite.unsuspiciousblock.core.journal.state.ArchaeologyJournalStateHolder;
import com.meteorite.unsuspiciousblock.core.journal.tracking.RecentLootTableService;
import com.meteorite.unsuspiciousblock.core.network.payload.c2s.UpdateTrackedLootTablePayload;
import com.meteorite.unsuspiciousblock.core.network.payload.c2s.UpdateLootTableTranslationsPayload;
import com.meteorite.unsuspiciousblock.core.network.payload.s2c.SyncLootTableManagementPayload;
import com.meteorite.unsuspiciousblock.core.network.payload.s2c.SyncLootTableManagementPayload.ConfigView;
import com.meteorite.unsuspiciousblock.core.network.payload.s2c.SyncLootTableManagementPayload.Receipt;
import com.meteorite.unsuspiciousblock.platform.Services;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 战利品表追踪管理处理器——以服务端 reloadable registry 为索引权威并校验管理员写操作。
 */
public final class LootTableManagementHandler {
    private static final int EDIT_PERMISSION_LEVEL = 2;

    private LootTableManagementHandler() {
    }

    public static void handleRequest(ServerPlayer player) {
        sync(player);
    }

    public static void handleUpdate(ServerPlayer player, UpdateTrackedLootTablePayload payload) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        EditResult result = editTracking(server, player, payload);
        sync(player, new Receipt(payload.requestId(), result));
        if (result == EditResult.SAVED && !ServerLootTableConfigManager.isCatalogPending()) {
            for (ServerPlayer other : server.getPlayerList().getPlayers()) {
                if (other != player) sync(other);
            }
        }
    }

    private static EditResult editTracking(MinecraftServer server, ServerPlayer player,
                                           UpdateTrackedLootTablePayload payload) {
        if (!canEditTracking(server, player)) return EditResult.READ_ONLY;
        Snapshot config = ServerLootTableConfigManager.snapshot();
        if (!config.canSave()) return EditResult.READ_ONLY;
        if (config.revision() != payload.revision()) return EditResult.CONFLICT;
        LootTrackingSettings settings;
        try {
            switch (payload.edit()) {
                case UpdateTrackedLootTablePayload.Single single -> {
                    if (!isManageable(server, single.tableId())) return EditResult.INVALID;
                    settings = config.rules().edit(single.tableId(), single.action());
                }
                case UpdateTrackedLootTablePayload.Replace replace ->
                        settings = LootTrackingConfigFile.decodeSettings(replace.settingsJson());
            }
        } catch (JsonParseException | IllegalArgumentException exception) {
            return EditResult.INVALID;
        }
        return ServerLootTableConfigManager.save(server, payload.revision(), settings);
    }

    private static boolean canEditTracking(MinecraftServer server, ServerPlayer player) {
        return player.hasPermissions(EDIT_PERMISSION_LEVEL) || !server.isDedicatedServer()
                && !server.isPublished() && server.isSingleplayerOwner(player.getGameProfile());
    }

    public static void handleTranslationUpdate(ServerPlayer player,
                                               UpdateLootTableTranslationsPayload payload) {
        MinecraftServer server = player.getServer();
        if (server == null || !player.hasPermissions(EDIT_PERMISSION_LEVEL) || payload.entries().isEmpty()) {
            sync(player);
            return;
        }
        Map<String, Map<String, String>> accepted = new LinkedHashMap<>();
        for (UpdateLootTableTranslationsPayload.Entry entry : payload.entries()) {
            String languageCode = entry.languageCode().trim().toLowerCase(Locale.ROOT);
            if (!languageCode.matches("[a-z0-9_-]{2,16}") || !isManageable(server, entry.tableId())) continue;
            String key = LootTableNames.createTranslationKey(entry.tableId());
            accepted.computeIfAbsent(languageCode, ignored -> new LinkedHashMap<>())
                    .put(key, entry.localizedName());
        }
        if (LootTableTranslationStore.putAll(accepted)) {
            broadcast(server);
        } else {
            sync(player);
        }
    }

    public static void broadcast(MinecraftServer server) {
        server.getPlayerList().getPlayers().forEach(LootTableManagementHandler::sync);
    }

    public static void sync(ServerPlayer player) {
        sync(player, null);
    }

    private static void sync(ServerPlayer player, @Nullable Receipt receipt) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        Snapshot config = ServerLootTableConfigManager.snapshot();
        Set<ResourceLocation> catalogRoots = ArchaeologyJournalServerCatalog.rootTableIds();
        Set<ResourceLocation> collected = ArchaeologyJournalServerCatalog.staticTables().keySet();
        Collection<ResourceLocation> registeredTables = server.reloadableRegistries()
                .getKeys(Registries.LOOT_TABLE);
        List<SyncLootTableManagementPayload.Entry> entries = registeredTables.stream()
                .filter(LootTableManagementHandler::isManageablePath)
                .sorted(Comparator.comparing(ResourceLocation::toString))
                .map(id -> new SyncLootTableManagementPayload.Entry(id, config.rules().match(id),
                        catalogRoots.contains(id), collected.contains(id), config.rules().hasExactOverride(id)))
                .toList();
        ArchaeologyJournalState state = ArchaeologyJournalStateHolder.getState(player);
        List<SyncLootTableManagementPayload.RecentEntry> recentEntries = state == null ? List.of()
                : state.getRecentLootTables().stream()
                .filter(entry -> registeredTables.contains(entry.tableId()))
                .filter(entry -> isManageablePath(entry.tableId()))
                .map(entry -> new SyncLootTableManagementPayload.RecentEntry(
                        entry.tableId(), entry.encounterOrder()))
                .toList();
        String settings = LootTrackingConfigFile.encodeSettings(config.settings());
        ConfigView view = new ConfigView(config.revision(), canEditTracking(server, player), config.canSave(),
                ServerLootTableConfigManager.isCatalogPending(),
                settings.length() <= UpdateTrackedLootTablePayload.MAX_CONFIG_CHARS ? settings : null);
        Services.NETWORK.sendToPlayer(player, new SyncLootTableManagementPayload(
                entries, recentEntries, player.hasPermissions(EDIT_PERMISSION_LEVEL),
                LootTableTranslationStore.snapshot(), view, receipt));
    }

    private static boolean isManageable(MinecraftServer server, ResourceLocation tableId) {
        return isManageablePath(tableId)
                && server.reloadableRegistries().getKeys(Registries.LOOT_TABLE).contains(tableId);
    }

    private static boolean isManageablePath(ResourceLocation tableId) {
        return RecentLootTableService.isSupported(tableId);
    }
}
