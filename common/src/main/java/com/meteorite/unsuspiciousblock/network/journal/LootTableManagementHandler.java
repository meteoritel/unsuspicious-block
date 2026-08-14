package com.meteorite.unsuspiciousblock.network.journal;

import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableNames;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableTranslationStore;
import com.meteorite.unsuspiciousblock.journal.state.ArchaeologyJournalState;
import com.meteorite.unsuspiciousblock.journal.state.ArchaeologyJournalStateHolder;
import com.meteorite.unsuspiciousblock.journal.tracking.RecentLootTableService;
import com.meteorite.unsuspiciousblock.network.payload.c2s.UpdateTrackedLootTablePayload;
import com.meteorite.unsuspiciousblock.network.payload.s2c.SyncLootTableManagementPayload;
import com.meteorite.unsuspiciousblock.platform.Services;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

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
        if (server == null || !player.hasPermissions(EDIT_PERMISSION_LEVEL)
                || !isManageable(server, payload.tableId())) {
            sync(player);
            return;
        }
        boolean configChanged = Services.LOOT_TABLE_CONFIG.setTracked(payload.tableId(), payload.tracked());
        String key = LootTableNames.createTranslationKey(payload.tableId());
        String languageCode = payload.languageCode().toLowerCase(Locale.ROOT);
        boolean canUpdateName = "en_us".equals(languageCode) || payload.localizedName().isBlank()
                || LootTableTranslationStore.hasNonBlank("en_us", key);
        boolean nameChanged = canUpdateName
                && LootTableTranslationStore.put(languageCode, key, payload.localizedName());
        if (configChanged) {
            JournalCatalogHandler.onDataPackReload(server);
        }
        if (configChanged || nameChanged) {
            broadcast(server);
        } else {
            sync(player);
        }
    }

    public static void broadcast(MinecraftServer server) {
        server.getPlayerList().getPlayers().forEach(LootTableManagementHandler::sync);
    }

    public static void sync(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        Collection<ResourceLocation> registeredTables = server.reloadableRegistries()
                .getKeys(Registries.LOOT_TABLE);
        List<SyncLootTableManagementPayload.Entry> entries = registeredTables.stream()
                .filter(LootTableManagementHandler::isManageablePath)
                .sorted(Comparator.comparing(ResourceLocation::toString))
                .map(id -> new SyncLootTableManagementPayload.Entry(id,
                        LootTableNames.isArchaeologyLootTable(id)))
                .toList();
        ArchaeologyJournalState state = ArchaeologyJournalStateHolder.getState(player);
        List<SyncLootTableManagementPayload.RecentEntry> recentEntries = state == null ? List.of()
                : state.getRecentLootTables().stream()
                .filter(entry -> registeredTables.contains(entry.tableId()))
                .filter(entry -> isManageablePath(entry.tableId()))
                .map(entry -> new SyncLootTableManagementPayload.RecentEntry(
                        entry.tableId(), entry.encounterOrder()))
                .toList();
        Services.NETWORK.sendToPlayer(player, new SyncLootTableManagementPayload(
                entries, recentEntries, player.hasPermissions(EDIT_PERMISSION_LEVEL),
                LootTableTranslationStore.snapshot()));
    }

    private static boolean isManageable(MinecraftServer server, ResourceLocation tableId) {
        return isManageablePath(tableId)
                && server.reloadableRegistries().getKeys(Registries.LOOT_TABLE).contains(tableId);
    }

    private static boolean isManageablePath(ResourceLocation tableId) {
        return RecentLootTableService.isSupported(tableId);
    }
}
