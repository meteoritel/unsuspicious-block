package com.meteorite.unsuspiciousblock.client.ui.entry;

import com.meteorite.unsuspiciousblock.client.ui.support.ArchaeologyJournalClientState;
import com.meteorite.unsuspiciousblock.journal.state.ArchaeologyJournalLogState;
import com.meteorite.unsuspiciousblock.journal.state.ArchaeologyJournalState;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.ItemDefinition;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.TableDefinition;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 考古手册左侧条目的完整视图模型。
 * 合并目录定义、玩家进度与日志引用数据，作为目录面板与右侧页面的数据源。
 * 设计为不可变快照，在 {@code rebuildViewModels()} 时整体重建。
 */
public record ArchaeologyJournalEntry(
        // --- 基础标识 ---
        ResourceLocation id,
        Component displayName,
        // 战利品表声明的 type（如 minecraft:archaeology），用于目录按类型聚类排序
        String type,

        // --- 考古信息（右侧 Archaeology tab + Intro tab 共用）---
        List<ArchaeologyEntryItem> items,
        int simulationCount,
        int totalCount,
        int parsedCount,
        boolean unlocked,
        // 用户是否收藏该表（客户端偏好）
        boolean favorite,

        // --- 日志数据引用（与条目本身解耦）---
        ArchaeologyEntryLogRef logRef
) {
    /**
     * 从目录定义、玩家进度和日志历史构建条目视图模型。
     */
    public static ArchaeologyJournalEntry of(
            ResourceLocation tableId,
            TableDefinition definition,
            @Nullable ArchaeologyJournalState.TableProgress progress,
            @Nullable ArchaeologyJournalLogState.TableLogHistory logHistory) {
        List<ArchaeologyEntryItem> items = new ArrayList<>();
        int parsedCount = 0;
        for (ItemDefinition itemDefinition : definition.items()) {
            ArchaeologyJournalState.ItemProgress itemProgress = progress != null
                    ? progress.getItemProgress(itemDefinition.signature())
                    : null;
            boolean unlocked = itemProgress != null && itemProgress.isUnlocked();
            int count = progress != null ? progress.getItemCount(itemDefinition.signature()) : 0;
            if (unlocked) {
                parsedCount++;
            }
            items.add(new ArchaeologyEntryItem(itemDefinition.id(), itemDefinition.displayName(),
                    itemDefinition.tooltipHint(), itemDefinition.probability(), unlocked, count,
                    itemDefinition.signature(), itemDefinition.acquisitionPaths(),
                    itemDefinition.injected(), itemDefinition.scenarioProbabilities()));
        }
        boolean tableUnlocked = progress != null && progress.isUnlocked();
        boolean favorite = ArchaeologyJournalClientState.isFavorite(tableId);
        ArchaeologyEntryLogRef logRef = ArchaeologyEntryLogRef.from(logHistory);
        return new ArchaeologyJournalEntry(tableId, definition.displayName(), definition.type(), items,
                definition.simulationCount(), definition.items().size(), parsedCount, tableUnlocked, favorite, logRef);
    }
}
