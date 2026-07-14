package com.meteorite.unsuspiciousblock.journal.tracking;

import com.meteorite.unsuspiciousblock.item.ModItems;
import com.meteorite.unsuspiciousblock.journal.catalog.ArchaeologyJournalServerCatalog;
import com.meteorite.unsuspiciousblock.journal.state.ArchaeologyJournalState;
import com.meteorite.unsuspiciousblock.journal.state.ArchaeologyJournalState.TableProgress;
import com.meteorite.unsuspiciousblock.journal.state.ArchaeologyJournalStateHolder;
import com.meteorite.unsuspiciousblock.loottable.ArchaeologyLootTableCatalog.ItemDefinition;
import com.meteorite.unsuspiciousblock.loottable.ArchaeologyLootTableCatalog.TableDefinition;
import com.meteorite.unsuspiciousblock.network.journal.JournalStateHandler;
import com.meteorite.unsuspiciousblock.network.payload.s2c.NotifyTableCompletionRewardPayload;
import com.meteorite.unsuspiciousblock.platform.Services;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/***
 * 考古战利品表 100% 完成奖励检测器。
 * <p>
 * 当某张被追踪的战利品表内全部物品都至少解锁一次时，发放 1 枚古代金币并通知客户端弹 Toast。
 * 每张表的奖励仅发放一次，通过 {@link TableProgress#isCompletionRewardClaimed()} 持久化标记去重。
 * <p>
 * 提供两个入口：
 * <ul>
 *   <li>{@link #checkAndReward}：事件触发时仅检测本次变更的表，用于实时反馈</li>
 *   <li>{@link #checkAndRewardAll}：玩家加入世界时全量扫描，用于补发漏发的奖励</li>
 * </ul>
 */
public final class JournalCompletionRewardChecker {
    private JournalCompletionRewardChecker() {
    }

    // 事件触发：仅检测本次变更涉及的表（轻量，避免全量扫描）
    public static void checkAndReward(ServerPlayer player, ArchaeologyJournalState state,
                                      List<ResourceLocation> changedTables) {
        if (state == null || changedTables == null || changedTables.isEmpty()) {
            return;
        }
        for (ResourceLocation tableId : changedTables) {
            tryReward(player, state, tableId);
        }
    }

    // 补发入口：玩家加入世界时全量扫描所有追踪表，补发历史漏发的完成奖励
    public static void checkAndRewardAll(ServerPlayer player) {
        ArchaeologyJournalState state = ArchaeologyJournalStateHolder.getState(player);
        if (state == null) {
            return;
        }
        // 快照 catalog keySet，避免扫描期间 catalog 变更引发并发问题
        for (ResourceLocation tableId : List.copyOf(ArchaeologyJournalServerCatalog.getCatalog().keySet())) {
            tryReward(player, state, tableId);
        }
    }

    // 检测单张表是否达到 100% 完成条件，达标则发奖+发包
    private static void tryReward(ServerPlayer player, ArchaeologyJournalState state, ResourceLocation tableId) {
        TableProgress progress = state.getTable(tableId);
        if (progress == null || !progress.isUnlocked() || progress.isCompletionRewardClaimed()) {
            return;
        }

        TableDefinition def = ArchaeologyJournalServerCatalog.getCatalog().get(tableId);
        if (def == null || def.items().isEmpty()) {
            return;
        }

        // 检测表中所有追踪物品是否都已解锁
        for (ItemDefinition item : def.items()) {
            if (!progress.isItemUnlocked(item.signature())) {
                return;
            }
        }

        // 达标：标记 -> 发奖 -> 发包 -> 同步状态
        boolean newlyClaimed = state.claimCompletionReward(tableId);
        if (!newlyClaimed) {
            return;
        }
        grantAncientCoin(player);
        Services.NETWORK.sendToPlayer(player, new NotifyTableCompletionRewardPayload(tableId));
        // completionRewardClaimed 变更需要增量同步给客户端，保持状态一致
        JournalStateHandler.syncState(player);
    }

    // 发放 1 枚古代金币，背包满则掉落地面
    private static void grantAncientCoin(ServerPlayer player) {
        ItemStack coin = new ItemStack(ModItems.ANCIENT_COIN, 1);
        if (!player.getInventory().add(coin)) {
            player.drop(coin, false);
        }
    }
}
