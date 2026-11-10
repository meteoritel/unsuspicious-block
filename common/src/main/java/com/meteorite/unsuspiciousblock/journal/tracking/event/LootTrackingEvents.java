package com.meteorite.unsuspiciousblock.journal.tracking.event;

import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.journal.catalog.ArchaeologyJournalServerCatalog;
import com.meteorite.unsuspiciousblock.journal.state.ArchaeologyJournalState;
import com.meteorite.unsuspiciousblock.journal.state.ArchaeologyJournalStateHolder;
import com.meteorite.unsuspiciousblock.journal.tracking.ArchaeologyLootRuntimeTracker;
import com.meteorite.unsuspiciousblock.journal.tracking.LootSession;
import com.meteorite.unsuspiciousblock.journal.tracking.settlement.LootSettlementStrategy;
import com.meteorite.unsuspiciousblock.loottable.signature.LootResultSignature;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
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
    private static final List<Subscriber> SUBSCRIBERS = new ArrayList<>();
    private static long registrationSequence;
    private static long builtInSequence = -1L;

    static synchronized void initializeBuiltIns() {
        if (builtInSequence < 0L) builtInSequence = registrationSequence++;
    }

    private LootTrackingEvents() {
    }

    // 注册订阅者，priority 越小越先执行；同 priority 按注册顺序执行
    public static void register(int priority, Consumer<LootDiscoveredEvent> listener) {
        SUBSCRIBERS.add(new Subscriber(priority, registrationSequence++, Objects.requireNonNull(listener, "listener")));
        SUBSCRIBERS.sort(Comparator.comparingInt(Subscriber::priority));
    }

    // 提交单个最终物品栈
    public static void submit(LootSession session, ItemStack stack, LootSettlementStrategy settlementStrategy) {
        LinkedHashMap<String, Integer> itemCounts = new LinkedHashMap<>();
        if (!stack.isEmpty()) {
            LootResultSignature signature = resolveFinalSignature(session, stack);
            itemCounts.put(signature.toStoredKey(), stack.getCount());
        }
        submit(session, itemCounts, settlementStrategy);
    }

    // 提交多个最终物品栈
    public static void submit(LootSession session, Iterable<ItemStack> stacks,
                              LootSettlementStrategy settlementStrategy) {
        LinkedHashMap<String, Integer> itemCounts = new LinkedHashMap<>();
        for (ItemStack stack : stacks) {
            if (stack.isEmpty()) {
                continue;
            }
            LootResultSignature signature = resolveFinalSignature(session, stack);
            itemCounts.merge(signature.toStoredKey(), stack.getCount(), Integer::sum);
        }
        submit(session, itemCounts, settlementStrategy);
    }

    // 完成并提交聚合会话
    public static void submit(LootSession session, Map<String, Integer> finalItemCounts,
                              LootSettlementStrategy settlementStrategy) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(finalItemCounts, "finalItemCounts");
        Objects.requireNonNull(settlementStrategy, "settlementStrategy");
        LootSession.Commit commit = session.complete(finalItemCounts);
        ServerPlayer player = commit.rootContext().player();
        ArchaeologyJournalState state = resolveTrackingState(player, commit.rootContext().rootTableId());
        if (state == null || commit.discoveredLoot().isEmpty()) {
            return;
        }
        dispatch(new LootDiscoveredEvent(commit, state, settlementStrategy));
    }

    // Fabric 等平台可能在运行时向根表注入嵌套表，最终物品需同时尝试实际发现的子表目录
    private static LootResultSignature resolveFinalSignature(LootSession session, ItemStack stack) {
        for (ResourceLocation tableId : session.discoveredTableIds()) {
            LootResultSignature signature = ArchaeologyLootRuntimeTracker.resolveSignature(tableId, stack);
            if (signature != null) {
                return signature;
            }
        }
        return LootResultSignature.plain(BuiltInRegistries.ITEM.getKey(stack.getItem()));
    }

    private static void dispatch(LootDiscoveredEvent event) {
        CompatibilityDispatch extensions = new CompatibilityDispatch(event);
        if (builtInSequence < 0L) extensions.finish();
        else LootTrackingBootstrap.run(event, extensions);
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

    static final class CompatibilityDispatch {
        private final LootDiscoveredEvent event;
        private final List<Subscriber> subscribers = List.copyOf(SUBSCRIBERS);
        private int next;

        private CompatibilityDispatch(LootDiscoveredEvent event) { this.event = event; }

        void before(LootTrackingBootstrap.Step step) {
            int priority = switch (step) {
                case UNLOCK -> 100;
                case FIRST_UNLOCK -> 200;
                case SETTLE -> 250;
                case CHALLENGE -> 300;
                case COMPLETION_REWARD -> 350;
            };
            while (next < subscribers.size()) {
                Subscriber subscriber = subscribers.get(next);
                if (subscriber.priority() > priority || subscriber.priority() == priority
                        && subscriber.sequence() > builtInSequence) break;
                invoke(subscriber);
                next++;
            }
        }

        void finish() {
            while (next < subscribers.size()) invoke(subscribers.get(next++));
        }

        private void invoke(Subscriber subscriber) {
            runStep(event, "extension[priority=" + subscriber.priority() + ", registration="
                    + subscriber.sequence() + "]", () -> subscriber.listener().accept(event));
        }
    }

    @Nullable
    private static ArchaeologyJournalState resolveTrackingState(ServerPlayer player,
                                                                 ResourceLocation rootTableId) {
        if (!ArchaeologyJournalServerCatalog.isTrackedTable(rootTableId)) {
            return null;
        }
        return ArchaeologyJournalStateHolder.getState(player);
    }

    private record Subscriber(int priority, long sequence, Consumer<LootDiscoveredEvent> listener) {
    }
}
