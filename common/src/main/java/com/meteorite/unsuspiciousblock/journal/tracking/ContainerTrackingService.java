package com.meteorite.unsuspiciousblock.journal.tracking;

import com.meteorite.unsuspiciousblock.blockentity.TrackedContainerLootState;
import com.meteorite.unsuspiciousblock.journal.state.ExcavationLogEntry;
import com.meteorite.unsuspiciousblock.journal.state.LootSourceType;
import com.meteorite.unsuspiciousblock.platform.Services;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 容器追踪服务。
 * 管理追踪容器的生命周期：战利品解析确认、菜单核对、超时清理、方块破坏 flush。
 * 通过容器图遍历收集当前玩家拥有的未过期追踪容器，供运行时追踪器与菜单快照机制复用。
 */
public final class ContainerTrackingService {
    private ContainerTrackingService() {
    }

    // 容器战利品确认处理：结算旧条目（如有）→ 解锁物品 + 创建待定日志条目 + 记录追踪状态
    // 仅第一个触发解析的玩家会创建追踪，后续玩家跳过
    public static void onContainerLootResolved(ServerPlayer player,
                                               TrackedContainerLootState container,
                                               ResourceLocation tableId,
                                               Map<String, Integer> itemCounts) {
        long gameTime = player.serverLevel().getGameTime();
        long dayTime = player.serverLevel().getDayTime();
        long timeoutTicks = Services.LOOT_TABLE_CONFIG.getTrackingTimeoutTicks();

        // 多玩家并发安全：若追踪玩家 UUID 已存在且不是当前玩家，跳过
        UUID trackedPlayerUuid = container.unsuspiciousblock$getTrackedPlayerUuid();
        if (trackedPlayerUuid != null && !trackedPlayerUuid.equals(player.getUUID())) {
            // 超时检查：即使不是当前玩家，也要检查是否需要清理过期追踪
            if (container.unsuspiciousblock$isTrackingExpired(gameTime, timeoutTicks)) {
                flushTrackingToJournal(player, container);
            }
            return;
        }

        // 超时检查：若追踪已超时，先 flush 旧条目再创建新追踪
        if (trackedPlayerUuid == null && container.unsuspiciousblock$isTrackingExpired(gameTime, timeoutTicks)) {
            flushTrackingToJournal(player, container);
        } else if (container.unsuspiciousblock$getPendingJournalEntry() != null
                && container.unsuspiciousblock$getTrackedLootTableName() != null) {
            // 若容器有未结算的旧追踪条目（非超时），先将旧条目写入日志再覆盖追踪状态
            ResourceLocation oldTableId = container.unsuspiciousblock$getTrackedLootTableName();
            ExcavationLogEntry oldPendingEntry = container.unsuspiciousblock$getPendingJournalEntry();
            if (oldPendingEntry != null) {
                JournalLogRecorder.upsertExcavationEntry(player, oldTableId, oldPendingEntry,
                        ArchaeologyLootRuntimeTracker.toSignatureCounts(oldPendingEntry.actualLoot()));
            }
        }

        ArchaeologyLootRuntimeTracker.onLootDiscovered(player, tableId, itemCounts, LootSourceType.LOOT_CONTAINER, gameTime, dayTime);
        if (itemCounts.isEmpty()) {
            container.unsuspiciousblock$clearAllTrackingState();
            return;
        }
        BlockPos pos = WorldContextResolver.resolveContainerPos(container);
        container.unsuspiciousblock$setPendingJournalEntry(ArchaeologyLootRuntimeTracker.createPendingEntry(player, LootSourceType.LOOT_CONTAINER,
                WorldContextResolver.resolveContainerSourceBlockId(container), pos, itemCounts, gameTime, dayTime));
        container.unsuspiciousblock$setTrackedLoot(tableId, itemCounts);
        container.unsuspiciousblock$setTrackedPlayerUuid(player.getUUID());
    }

    // 核对容器追踪状态：清理已过期或已标记移除的追踪，并结算残留的待定日志条目
    // 仅处理当前玩家拥有的追踪容器，超时的先 flush
    public static void reconcileTrackedContainers(ServerPlayer player, Collection<Container> rootContainers) {
        long gameTime = player.serverLevel().getGameTime();
        long timeoutTicks = Services.LOOT_TABLE_CONFIG.getTrackingTimeoutTicks();

        for (TrackedContainerLootState trackedContainer : collectOwnTrackedContainers(player, rootContainers, gameTime, timeoutTicks)) {
            // reconcile 前获取旧条目和 tableId
            ExcavationLogEntry pendingEntry = trackedContainer.unsuspiciousblock$getPendingJournalEntry();
            ResourceLocation tableId = trackedContainer.unsuspiciousblock$getTrackedLootTableName();

            trackedContainer.unsuspiciousblock$reconcileTrackedLoot();

            // reconcile 后若追踪状态已清空但有残留的 pendingJournalEntry，将旧条目写入日志并清除
            if (!trackedContainer.unsuspiciousblock$hasTrackedLoot() && pendingEntry != null) {
                if (tableId != null) {
                    ServerPlayer recipient = resolveTrackingPlayer(player, trackedContainer);
                    JournalLogRecorder.upsertExcavationEntry(recipient, tableId, pendingEntry,
                            ArchaeologyLootRuntimeTracker.toSignatureCounts(pendingEntry.actualLoot()));
                }
                trackedContainer.unsuspiciousblock$clearAllTrackingState();
            }
        }
    }

    // flush 追踪容器的待定日志条目到日志系统，然后清除完整追踪状态
    public static void flushTrackingToJournal(ServerPlayer fallbackPlayer, TrackedContainerLootState container) {
        ExcavationLogEntry pendingEntry = container.unsuspiciousblock$getPendingJournalEntry();
        ResourceLocation tableId = container.unsuspiciousblock$getTrackedLootTableName();
        if (pendingEntry != null && tableId != null) {
            ServerPlayer recipient = resolveTrackingPlayer(fallbackPlayer, container);
            JournalLogRecorder.upsertExcavationEntry(recipient, tableId, pendingEntry,
                    ArchaeologyLootRuntimeTracker.toSignatureCounts(pendingEntry.actualLoot()));
        }
        container.unsuspiciousblock$clearAllTrackingState();
    }

    // 容器方块被破坏时 flush 其追踪状态
    public static void onContainerBlockDestroyed(ServerLevel level, BlockPos pos) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (!(blockEntity instanceof TrackedContainerLootState trackedContainer)) {
            return;
        }
        if (!trackedContainer.unsuspiciousblock$hasTrackedLoot()
                && trackedContainer.unsuspiciousblock$getPendingJournalEntry() == null) {
            return;
        }
        // 尝试按追踪玩家 UUID 查找在线玩家，若离线则使用 null 作为回退（放弃该条目）
        UUID trackedUuid = trackedContainer.unsuspiciousblock$getTrackedPlayerUuid();
        ServerPlayer recipient = null;
        if (trackedUuid != null) {
            recipient = level.getServer().getPlayerList().getPlayer(trackedUuid);
        }
        if (recipient == null) {
            // 追踪玩家不在线，无法写入日志，清除追踪状态
            trackedContainer.unsuspiciousblock$clearAllTrackingState();
            return;
        }
        flushTrackingToJournal(recipient, trackedContainer);
    }

    // 仅收集当前玩家拥有的追踪容器；超时的追踪先 flush 后移除
    static List<TrackedContainerLootState> collectOwnTrackedContainers(ServerPlayer player,
                                                                       Collection<Container> rootContainers,
                                                                       long currentGameTime,
                                                                       long timeoutTicks) {
        List<TrackedContainerLootState> allTracked = collectTrackedContainers(rootContainers);
        List<TrackedContainerLootState> result = new ArrayList<>();
        UUID playerUuid = player.getUUID();
        for (TrackedContainerLootState tracked : allTracked) {
            UUID owner = tracked.unsuspiciousblock$getTrackedPlayerUuid();
            // 超时：先 flush 再移除，不参与本次快照
            if (tracked.unsuspiciousblock$isTrackingExpired(currentGameTime, timeoutTicks)) {
                flushTrackingToJournal(player, tracked);
                continue;
            }
            // 仅当前玩家拥有的追踪容器参与
            if (owner != null && !owner.equals(playerUuid)) {
                continue;
            }
            result.add(tracked);
        }
        return result;
    }

    private static List<TrackedContainerLootState> collectTrackedContainers(Collection<Container> rootContainers) {
        List<TrackedContainerLootState> trackedContainers = new ArrayList<>();
        Set<Container> visitedContainers = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<TrackedContainerLootState> visitedTrackedContainers = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Container rootContainer : rootContainers) {
            collectTrackedContainers(rootContainer, visitedContainers, visitedTrackedContainers, trackedContainers);
        }
        return trackedContainers;
    }

    private static void collectTrackedContainers(Container container,
                                                 Set<Container> visitedContainers,
                                                 Set<TrackedContainerLootState> visitedTrackedContainers,
                                                 List<TrackedContainerLootState> trackedContainers) {
        if (!visitedContainers.add(container)) {
            return;
        }

        if (container instanceof TrackedContainerLootState trackedContainer
                && trackedContainer.unsuspiciousblock$hasTrackedLoot()
                && visitedTrackedContainers.add(trackedContainer)) {
            trackedContainers.add(trackedContainer);
        }

        if (container instanceof CompoundContainerAccess access) {
            collectTrackedContainers(access.unsuspiciousblock$getFirstContainer(),
                    visitedContainers, visitedTrackedContainers, trackedContainers);
            collectTrackedContainers(access.unsuspiciousblock$getSecondContainer(),
                    visitedContainers, visitedTrackedContainers, trackedContainers);
        }
    }

    // 解析追踪容器的日志接收者：优先追踪玩家 UUID 对应的在线玩家，回退到当前操作玩家
    private static ServerPlayer resolveTrackingPlayer(ServerPlayer fallbackPlayer, TrackedContainerLootState container) {
        UUID trackedUuid = container.unsuspiciousblock$getTrackedPlayerUuid();
        if (trackedUuid != null) {
            var trackedPlayer = fallbackPlayer.server.getPlayerList().getPlayer(trackedUuid);
            if (trackedPlayer != null) {
                return trackedPlayer;
            }
        }
        return fallbackPlayer;
    }
}
