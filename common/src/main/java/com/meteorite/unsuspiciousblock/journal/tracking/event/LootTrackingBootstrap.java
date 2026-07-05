package com.meteorite.unsuspiciousblock.journal.tracking.event;

import com.meteorite.unsuspiciousblock.journal.tracking.ArchaeologyChallengeChecker;
import com.meteorite.unsuspiciousblock.journal.tracking.ArchaeologyLootRuntimeTracker;
import com.meteorite.unsuspiciousblock.journal.tracking.JournalLogRecorder;

/**
 * 战利品发现事件订阅者注册入口。
 * <p>
 * 集中注册内建订阅者，按 priority 升序同步执行：
 * <ol>
 *   <li>priority 100：调用 {@link ArchaeologyLootRuntimeTracker#unlockResolvedLoot} 解锁日记状态</li>
 *   <li>priority 200：调用 {@link JournalLogRecorder#recordFirstUnlock} 记录首次发现元数据</li>
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
        LootTrackingEvents.register(300, LootTrackingBootstrap::onCheckChallenge);
    }

    // 解锁订阅者：更新日记状态并触发概率模拟
    private static void onUnlock(LootDiscoveredEvent event) {
        ArchaeologyLootRuntimeTracker.unlockResolvedLoot(
                event.player(), event.tableId(), event.itemCounts());
    }

    // 日志记录订阅者：写入首次发现元数据
    private static void onRecordFirstUnlock(LootDiscoveredEvent event) {
        JournalLogRecorder.recordFirstUnlock(
                event.player(), event.tableId(), event.lootSource(),
                event.gameTime(), event.dayTime());
    }

    // 成就检查订阅者：核对考古收集类成就（依赖 state 已被解锁订阅者更新）
    private static void onCheckChallenge(LootDiscoveredEvent event) {
        ArchaeologyChallengeChecker.checkAndGrant(event.player(), event.state());
    }
}
