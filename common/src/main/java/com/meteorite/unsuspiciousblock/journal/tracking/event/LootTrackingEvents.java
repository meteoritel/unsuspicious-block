package com.meteorite.unsuspiciousblock.journal.tracking.event;

import com.meteorite.unsuspiciousblock.journal.state.ArchaeologyJournalState;
import com.meteorite.unsuspiciousblock.journal.state.ArchaeologyJournalStateHolder;
import com.meteorite.unsuspiciousblock.journal.state.LootSourceType;
import com.meteorite.unsuspiciousblock.journal.tracking.ArchaeologyLootRuntimeTracker;
import com.meteorite.unsuspiciousblock.loottable.LootResultSignature;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 战利品发现事件总线。
 * <p>
 * 提供 {@link LootDiscoveredEvent} 的订阅与发布能力，参考 {@code EnchantmentManager} 静态注册表模式。
 * 订阅者按 {@code priority} 升序同步执行（越小越先执行），保证解锁订阅者先于成就检查订阅者。
 * <p>
 * 5 个追踪入口（考古 mixin / 扫描仪 / 考古铲 / 钓鱼 mixin / 容器追踪服务）通过 {@link #publish} 发布事件，
 * 由 {@link LootTrackingBootstrap} 注册的内建订阅者统一处理解锁、记录、成就检查三条副作用。
 * <p>
 * 仅在服务端主线程调用，{@code SUBSCRIBERS} 列表初始化后只读，无需同步。
 */
public final class LootTrackingEvents {
    private LootTrackingEvents() {
    }

    private static final List<Subscriber> SUBSCRIBERS = new ArrayList<>();

    // 注册订阅者，priority 越小越先执行；同 priority 按注册顺序执行
    public static void register(int priority, Consumer<LootDiscoveredEvent> listener) {
        SUBSCRIBERS.add(new Subscriber(priority, listener));
        SUBSCRIBERS.sort(Comparator.comparingInt(Subscriber::priority));
    }

    // 单栈便利发布：内部调用 resolveSignature 转为 1-entry map；state 为 null 时静默返回
    public static void publish(ServerPlayer player, ResourceLocation tableId,
                               ItemStack stack, LootSourceType lootSource,
                               long gameTime, long dayTime) {
        ArchaeologyJournalState state = resolveState(player);
        if (state == null) {
            return;
        }

        Map<String, Integer> itemCounts = new HashMap<>();
        LootResultSignature signature = ArchaeologyLootRuntimeTracker.resolveSignature(tableId, stack);
        if (signature != null) {
            itemCounts.put(signature.toStoredKey(), stack.getCount());
        }
        dispatch(new LootDiscoveredEvent(player, tableId, lootSource, gameTime, dayTime, itemCounts, state));
    }

    // 批量发布：直接传递 signature storedKey → count 映射；state 为 null 时静默返回
    public static void publish(ServerPlayer player, ResourceLocation tableId,
                               Map<String, Integer> itemCounts, LootSourceType lootSource,
                               long gameTime, long dayTime) {
        ArchaeologyJournalState state = resolveState(player);
        if (state == null) {
            return;
        }
        dispatch(new LootDiscoveredEvent(player, tableId, lootSource, gameTime, dayTime, itemCounts, state));
    }

    // 按 priority 顺序同步通知所有订阅者
    private static void dispatch(LootDiscoveredEvent event) {
        for (Subscriber subscriber : SUBSCRIBERS) {
            subscriber.listener().accept(event);
        }
    }

    // 从玩家解析考古日记状态，未实现 ArchaeologyJournalStateHolder 时返回 null
    private static @Nullable ArchaeologyJournalState resolveState(ServerPlayer player) {
        if (player instanceof ArchaeologyJournalStateHolder holder) {
            return holder.unsuspiciousblock$getArchaeologyJournalState();
        }
        return null;
    }

    private record Subscriber(int priority, Consumer<LootDiscoveredEvent> listener) {
    }
}
