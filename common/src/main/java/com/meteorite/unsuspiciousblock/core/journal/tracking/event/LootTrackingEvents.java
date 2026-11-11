package com.meteorite.unsuspiciousblock.core.journal.tracking.event;

import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.core.journal.catalog.ArchaeologyJournalServerCatalog;
import com.meteorite.unsuspiciousblock.core.journal.state.ArchaeologyJournalState;
import com.meteorite.unsuspiciousblock.core.journal.state.ArchaeologyJournalStateHolder;
import com.meteorite.unsuspiciousblock.core.journal.tracking.LootSession;
import com.meteorite.unsuspiciousblock.core.journal.tracking.settlement.LootSettlementTarget;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * 聚合战利品会话事件总线。
 * <p>
 * 各入口在一次 loot roll 结束后通过 {@link #submit} 提交最终结果，嵌套表结果已提前汇入同一
 * {@link LootSession}，订阅者因此只处理一次完整提交。
 */
public final class LootTrackingEvents {
    private LootTrackingEvents() {
    }

    /** 兼容已公开的扩展订阅；内建结算始终直接执行。 */
    @Deprecated
    public static void register(int priority, Consumer<LootDiscoveredEvent> listener) {
        LegacyLootSubscriptions.register(priority, listener);
    }

    // 完成并提交聚合会话
    public static void submit(LootSession session, Map<String, Integer> finalItemCounts,
                              LootSettlementTarget settlementTarget) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(finalItemCounts, "finalItemCounts");
        Objects.requireNonNull(settlementTarget, "settlementTarget");
        LootSession.Commit commit = session.complete(finalItemCounts);
        ServerPlayer player = commit.rootContext().player();
        ArchaeologyJournalState state = resolveTrackingState(player, commit.rootContext().rootTableId());
        if (commit.finalItemCounts().isEmpty() && settlementTarget instanceof LootSettlementTarget.Deferred(var pending))
            pending.stage(commit, null);
        if (state == null || commit.discoveredLoot().isEmpty()) return;
        dispatch(new LootDiscoveredEvent(commit, state, settlementTarget));
    }

    private static void dispatch(LootDiscoveredEvent event) {
        LootTrackingBootstrap.run(event);
    }

    static void runStep(LootDiscoveredEvent event, String step, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException | Error failure) {
            String context = "战利品结算失败: step=" + step + ", table="
                    + event.commit().rootContext().rootTableId() + ", player="
                    + event.commit().rootContext().player().getUUID();
            failure.addSuppressed(new IllegalStateException(context));
            Constants.LOG.error(context, failure);
            throw failure;
        }
    }

    @Nullable
    private static ArchaeologyJournalState resolveTrackingState(ServerPlayer player,
                                                                 ResourceLocation rootTableId) {
        if (!ArchaeologyJournalServerCatalog.tracked(rootTableId)) {
            return null;
        }
        return ArchaeologyJournalStateHolder.getState(player);
    }

}
