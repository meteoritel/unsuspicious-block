package com.meteorite.unsuspiciousblock.journal.tracking.event;

import com.meteorite.unsuspiciousblock.journal.state.ExcavationLogEntry;
import com.meteorite.unsuspiciousblock.journal.tracking.ArchaeologyChallengeChecker;
import com.meteorite.unsuspiciousblock.journal.tracking.ArchaeologyLootRuntimeTracker;
import com.meteorite.unsuspiciousblock.journal.tracking.JournalCompletionRewardChecker;
import com.meteorite.unsuspiciousblock.journal.tracking.JournalLogRecorder;
import com.meteorite.unsuspiciousblock.journal.state.LootSourceType;

import java.util.function.Consumer;

/**
 * 战利品发现事件订阅者注册入口。
 * <p>
 * 集中注册内建订阅者，按 priority 升序同步执行：
 * <ol>
 *   <li>priority 100：调用 {@link ArchaeologyLootRuntimeTracker#unlockResolvedLootMultiTable} 解锁日记状态</li>
 *   <li>priority 200：调用 {@link JournalLogRecorder#recordFirstUnlock} 记录首次发现元数据</li>
 *   <li>priority 250：调用 {@link ArchaeologyLootRuntimeTracker#recordExcavationEntryMultiTable} 创建日志条目（FISHING）；
 *       非 FISHING 通过 pendingEntryConsumer 回传待定日志条目</li>
 *   <li>priority 300：调用 {@link ArchaeologyChallengeChecker#checkAndGrant} 检查并授予考古成就</li>
 * </ol>
 * 解锁必须先于成就检查——后者读取的 state 必须反映本次解锁。
 * 由 {@code UnsuspiciousBlockCommon.init()} 在 mod 初始化时调用。
 */
public final class LootTrackingBootstrap {
    private LootTrackingBootstrap() {
    }

    // 注册战利品发现事件的全部内建订阅者
    public static void registerListeners() {
        LootTrackingEvents.register(100, LootTrackingBootstrap::onUnlock);
        LootTrackingEvents.register(200, LootTrackingBootstrap::onRecordFirstUnlock);
        LootTrackingEvents.register(250, LootTrackingBootstrap::onRecordExcavationEntry);
        LootTrackingEvents.register(300, LootTrackingBootstrap::onCheckChallenge);
        LootTrackingEvents.register(350, LootTrackingBootstrap::onCheckCompletionReward);
    }

    // 解锁订阅者：遍历 tableStack 对每个在 catalog 中的表更新解锁状态并触发概率模拟
    // 钓鱼场景（FISHING）同时记录物品获取计数；考古/开箱场景由待定日志条目机制负责计数
    private static void onUnlock(LootDiscoveredEvent event) {
        boolean recordItemCounts = event.lootSource() == LootSourceType.FISHING;
        ArchaeologyLootRuntimeTracker.unlockResolvedLootMultiTable(
                event.player(), event.tableStack(), event.rootTableId(), event.itemCounts(),
                recordItemCounts);
    }

    // 日志记录订阅者：写入首次发现元数据（锚定根表，一次嵌套表发现 = 一条日志条目）
    private static void onRecordFirstUnlock(LootDiscoveredEvent event) {
        JournalLogRecorder.recordFirstUnlock(
                event.player(), event.rootTableId(), event.lootSource(),
                event.gameTime(), event.dayTime());
    }

    // 日志条目创建订阅者：FISHING 直接创建最终日志条目；非 FISHING 通过 pendingEntryConsumer 回传待定条目
    // consumer 为 null 时（NestedLootTableMixin 子表捕获场景）跳过待定条目创建
    private static void onRecordExcavationEntry(LootDiscoveredEvent event) {
        if (event.lootSource() == LootSourceType.FISHING) {
            // FISHING：直接创建最终日志条目（无待定日志条目机制）
            ArchaeologyLootRuntimeTracker.recordExcavationEntryMultiTable(
                    event.player(), event.tableStack(), event.lootSource(),
                    event.gameTime(), event.dayTime(), event.itemCounts(),
                    event.pos(), event.sourceBlockId());
            return;
        }

        // 非 FISHING（ARCHAEOLOGY / LOOT_CONTAINER）：通过 consumer 回传待定条目
        Consumer<ExcavationLogEntry> consumer = event.pendingEntryConsumer();
        if (consumer != null) {
            ExcavationLogEntry pendingEntry = ArchaeologyLootRuntimeTracker.createPendingEntry(
                    event.player(), event.lootSource(), event.sourceBlockId(), event.pos(),
                    event.itemCounts(), event.gameTime(), event.dayTime());
            if (pendingEntry != null) {
                consumer.accept(pendingEntry);
            }
        }
        // consumer == null（NestedLootTableMixin 子表捕获）：no-op
    }

    // 成就检查订阅者：核对考古收集类成就（依赖 state 已被解锁订阅者更新）
    private static void onCheckChallenge(LootDiscoveredEvent event) {
        ArchaeologyChallengeChecker.checkAndGrant(event.player(), event.state());
    }

    // 完成奖励订阅者：检测 100% 完成的表，发放古代金币并通知客户端弹 Toast
    private static void onCheckCompletionReward(LootDiscoveredEvent event) {
        JournalCompletionRewardChecker.checkAndReward(
                event.player(), event.state(), event.tableStack());
    }
}
