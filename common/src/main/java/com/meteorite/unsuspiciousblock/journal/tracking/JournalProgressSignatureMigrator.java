package com.meteorite.unsuspiciousblock.journal.tracking;

import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.journal.catalog.ArchaeologyJournalServerCatalog;
import com.meteorite.unsuspiciousblock.journal.state.ArchaeologyJournalState;
import com.meteorite.unsuspiciousblock.journal.state.ArchaeologyJournalStateHolder;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.ItemDefinition;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.TableDefinition;
import com.meteorite.unsuspiciousblock.loottable.signature.LootResultSignature;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.LinkedHashSet;
import java.util.Map;

/**
 * 将历史玩家进度中的回退签名迁移为当前目录使用的规范签名。
 *
 * @deprecated 临时版本迁移兼容层，计划在 1.5.0 移除
 */
@Deprecated(forRemoval = true, since = "1.4.1-bug_fix")
public final class JournalProgressSignatureMigrator {
    private JournalProgressSignatureMigrator() {
    }

    // 仅迁移同一物品 ID 在目录中具有唯一候选的记录，避免多变体数据被错误合并
    public static int migrate(ServerPlayer player) {
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
            Constants.LOG.info("为玩家 {} 迁移了 {} 条考古进度签名", player.getGameProfile().getName(), migrated);
        }
        return migrated;
    }
}
