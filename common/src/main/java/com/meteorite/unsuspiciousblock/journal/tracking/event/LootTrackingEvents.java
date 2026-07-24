package com.meteorite.unsuspiciousblock.journal.tracking.event;

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

    private LootTrackingEvents() {
    }

    // 注册订阅者，priority 越小越先执行；同 priority 按注册顺序执行
    public static void register(int priority, Consumer<LootDiscoveredEvent> listener) {
        SUBSCRIBERS.add(new Subscriber(priority, Objects.requireNonNull(listener, "listener")));
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

    // 按 priority 顺序同步通知所有订阅者
    private static void dispatch(LootDiscoveredEvent event) {
        for (Subscriber subscriber : SUBSCRIBERS) {
            subscriber.listener().accept(event);
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

    private record Subscriber(int priority, Consumer<LootDiscoveredEvent> listener) {
    }
}
