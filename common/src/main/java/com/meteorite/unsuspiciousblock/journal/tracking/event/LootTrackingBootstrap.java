package com.meteorite.unsuspiciousblock.journal.tracking.event;

import com.meteorite.unsuspiciousblock.journal.tracking.ArchaeologyChallengeChecker;
import com.meteorite.unsuspiciousblock.journal.tracking.ArchaeologyLootRuntimeTracker;
import com.meteorite.unsuspiciousblock.journal.tracking.JournalCompletionRewardChecker;
import com.meteorite.unsuspiciousblock.journal.tracking.JournalLogRecorder;
import com.meteorite.unsuspiciousblock.journal.tracking.LootSession;

/**
 * 聚合战利品提交的内建订阅者注册入口。
 */
public final class LootTrackingBootstrap {
    private static boolean listenersRegistered;

    private LootTrackingBootstrap() {
    }

    // 注册全部内建订阅者；解锁必须先于成就与完成奖励检查
    public static synchronized void registerListeners() {
        if (listenersRegistered) {
            return;
        }
        LootTrackingEvents.register(100, LootTrackingBootstrap::onUnlock);
        LootTrackingEvents.register(200, LootTrackingBootstrap::onRecordFirstUnlock);
        LootTrackingEvents.register(250, LootTrackingBootstrap::onSettle);
        LootTrackingEvents.register(300, LootTrackingBootstrap::onCheckChallenge);
        LootTrackingEvents.register(350, LootTrackingBootstrap::onCheckCompletionReward);
        listenersRegistered = true;
    }

    // 一次性应用本会话中每个表对应的发现物品
    private static void onUnlock(LootDiscoveredEvent event) {
        ArchaeologyLootRuntimeTracker.unlockSession(
                event.commit(), event.settlementStrategy().recordsItemsImmediately());
    }

    // 首次发现元数据只记录一次并锚定根表
    private static void onRecordFirstUnlock(LootDiscoveredEvent event) {
        LootSession.Commit commit = event.commit();
        var context = commit.rootContext();
        JournalLogRecorder.recordFirstUnlock(
                context.player(), context.rootTableId(), context.lootSource(),
                context.gameTime(), context.dayTime());
    }

    // 由策略决定立即写入最终日志或回传待定日志
    private static void onSettle(LootDiscoveredEvent event) {
        event.settlementStrategy().settle(event.commit());
    }

    private static void onCheckChallenge(LootDiscoveredEvent event) {
        ArchaeologyChallengeChecker.checkAndGrant(event.commit().rootContext().player(), event.state());
    }

    private static void onCheckCompletionReward(LootDiscoveredEvent event) {
        JournalCompletionRewardChecker.checkAndReward(
                event.commit().rootContext().player(), event.state(), event.commit().tableStack());
    }
}
