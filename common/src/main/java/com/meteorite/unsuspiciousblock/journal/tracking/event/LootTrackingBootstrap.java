package com.meteorite.unsuspiciousblock.journal.tracking.event;

import com.meteorite.unsuspiciousblock.journal.tracking.ArchaeologyChallengeChecker;
import com.meteorite.unsuspiciousblock.journal.tracking.ArchaeologyLootRuntimeTracker;
import com.meteorite.unsuspiciousblock.journal.tracking.JournalCompletionRewardChecker;
import com.meteorite.unsuspiciousblock.network.journal.JournalLogHandler;
import com.meteorite.unsuspiciousblock.journal.tracking.LootSession;

/**
 * 聚合战利品提交的命名结算链。
 */
public final class LootTrackingBootstrap {
    enum Step { UNLOCK, FIRST_UNLOCK, SETTLE, CHALLENGE, COMPLETION_REWARD }

    private LootTrackingBootstrap() {
    }

    // 保留原初始化入口；注册时序用于兼容同优先级扩展。
    public static synchronized void registerListeners() {
        LootTrackingEvents.initializeBuiltIns();
    }

    static void run(LootDiscoveredEvent event, LootTrackingEvents.CompatibilityDispatch extensions) {
        extensions.before(Step.UNLOCK);
        LootTrackingEvents.runStep(event, "unlock", () -> onUnlock(event));
        extensions.before(Step.FIRST_UNLOCK);
        LootTrackingEvents.runStep(event, "first-unlock", () -> onRecordFirstUnlock(event));
        extensions.before(Step.SETTLE);
        LootTrackingEvents.runStep(event, "settle", () -> onSettle(event));
        extensions.before(Step.CHALLENGE);
        LootTrackingEvents.runStep(event, "challenge", () -> onCheckChallenge(event));
        extensions.before(Step.COMPLETION_REWARD);
        LootTrackingEvents.runStep(event, "completion-reward", () -> onCheckCompletionReward(event));
        extensions.finish();
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
        JournalLogHandler.recordFirstUnlock(
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
