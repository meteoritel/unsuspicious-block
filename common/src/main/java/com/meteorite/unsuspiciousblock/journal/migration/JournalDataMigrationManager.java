package com.meteorite.unsuspiciousblock.journal.migration;

import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.journal.catalog.ArchaeologyJournalServerCatalog;
import com.meteorite.unsuspiciousblock.journal.state.ArchaeologyJournalLogState;
import com.meteorite.unsuspiciousblock.journal.state.ArchaeologyJournalState;
import com.meteorite.unsuspiciousblock.journal.state.ArchaeologyJournalStateHolder;
import com.meteorite.unsuspiciousblock.journal.sync.ArchaeologyJournalLogSyncSession;
import com.meteorite.unsuspiciousblock.journal.tracking.ArchaeologyLootRuntimeTracker;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.ItemDefinition;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.TableDefinition;
import com.meteorite.unsuspiciousblock.loottable.signature.LootResultSignature;
import com.meteorite.unsuspiciousblock.world.JournalLogStorage;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 玩家考古手册数据迁移的统一入口。
 * <p>
 * 负责玩家旧日志迁移和目录签名迁移；具体文件读写仍由 {@link JournalLogStorage}
 * 负责，NBT 字段转换统一委托给 {@link JournalNbtMigrator}。
 */
public final class JournalDataMigrationManager {
    private JournalDataMigrationManager() {
    }

    // 按固定顺序执行当前玩家的全部语义迁移
    public static void migratePlayerData(ServerPlayer player,
                                         ArchaeologyJournalLogSyncSession session) {
        migrateLegacyPlayerLog(player, session);
        migrateProgressSignatures(player);
    }

    // 将玩家 NBT 中的旧日志合并到 v2 分片；全部持久化成功后才确认清理源数据
    private static void migrateLegacyPlayerLog(ServerPlayer player,
                                               ArchaeologyJournalLogSyncSession session) {
        if (!(player instanceof LegacyJournalLogAccess access)) {
            return;
        }
        CompoundTag legacyTag = access.unsuspiciousblock$peekLegacyJournalLogTag();
        if (legacyTag == null) {
            return;
        }
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }

        try {
            ArchaeologyJournalLogState migrated = new ArchaeologyJournalLogState();
            migrated.readFrom(legacyTag);
            session.mergeFromClient(migrated);
            Set<ResourceLocation> migratedTables = Set.copyOf(migrated.getTables().keySet());
            if (!migratedTables.isEmpty()
                    && !JournalLogStorage.persistTablesNow(server, player.getUUID(), migratedTables)) {
                Constants.LOG.warn("玩家 {} 的旧考古日志尚未完整落盘，将保留源 NBT 等待重试",
                        player.getGameProfile().getName());
                return;
            }
            access.unsuspiciousblock$acknowledgeLegacyJournalLogMigration();
            Constants.LOG.info("玩家 {} 的旧考古日志迁移完成: {} 张表",
                    player.getGameProfile().getName(), migratedTables.size());
        } catch (RuntimeException exception) {
            Constants.LOG.error("玩家 {} 的旧考古日志迁移失败，源 NBT 已保留",
                    player.getGameProfile().getName(), exception);
        }
    }

    // 将历史回退签名迁移为当前目录中的规范签名
    private static int migrateProgressSignatures(ServerPlayer player) {
        ArchaeologyJournalState state = ArchaeologyJournalStateHolder.getState(player);
        if (state == null) {
            return 0;
        }

        int migrated = 0;
        Map<ResourceLocation, TableDefinition> catalog = ArchaeologyJournalServerCatalog.getRawCatalog();
        for (Map.Entry<ResourceLocation, ArchaeologyJournalState.TableProgress> progressEntry
                : state.getTables().entrySet()) {
            ResourceLocation tableId = progressEntry.getKey();
            TableDefinition table = catalog.get(tableId);
            if (table == null || table.items().isEmpty()) {
                continue;
            }

            LinkedHashSet<LootResultSignature> catalogSignatures = new LinkedHashSet<>();
            for (ItemDefinition item : table.items()) {
                catalogSignatures.add(item.signature());
            }
            for (LootResultSignature stored : progressEntry.getValue().getItemSignatures()) {
                if (catalogSignatures.contains(stored)
                        || stored.type() != LootResultSignature.SignatureType.PLAIN) {
                    continue;
                }
                LootResultSignature canonical = ArchaeologyLootRuntimeTracker.canonicalizeSignature(tableId, stored);
                if (!canonical.equals(stored)
                        && state.remapItemSignature(tableId, stored, canonical)) {
                    migrated++;
                }
            }
        }

        if (migrated > 0) {
            Constants.LOG.info("为玩家 {} 迁移了 {} 条考古进度签名",
                    player.getGameProfile().getName(), migrated);
        }
        return migrated;
    }
}
