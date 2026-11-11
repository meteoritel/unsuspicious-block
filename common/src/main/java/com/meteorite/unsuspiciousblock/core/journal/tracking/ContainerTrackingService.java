package com.meteorite.unsuspiciousblock.core.journal.tracking;

import com.meteorite.unsuspiciousblock.core.blockentity.TrackedContainerLootState;
import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.core.journal.state.LootSourceType;
import com.meteorite.unsuspiciousblock.core.journal.tracking.LootCapture.CaptureHandle;
import com.meteorite.unsuspiciousblock.core.journal.tracking.settlement.LootSettlementTarget;
import com.meteorite.unsuspiciousblock.core.loottable.signature.LootResultSignature;
import com.meteorite.unsuspiciousblock.platform.Services;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.Nullable;

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
    private enum EndReason { OVERWRITTEN, RECONCILED, EXPIRED, DESTROYED }

    private ContainerTrackingService() {
    }

    // 在既有容器填充相位创建捕获句柄；不满足资格时句柄只执行生成。
    public static CaptureHandle prepareContainerCapture(ServerPlayer player, ResourceLocation tableId,
            ServerLevel level, BlockPos pos, @Nullable ResourceLocation sourceBlockId) {
        RecentLootTableService.record(player, tableId);
        return LootCapture.begin(new LootCapture.Source(player, tableId, LootSourceType.LOOT_CONTAINER,
                level, pos, sourceBlockId));
    }

    // 最终库存是权威结果，余量后续由领取路径增量结算。
    public static void completeContainerCapture(TrackedContainerLootState container,
            @Nullable CaptureHandle capture) {
        if (capture == null) return;
        try (capture) {
            var context = capture.context();
            if (context == null) return;
            List<LootResultSignature> candidates = ArchaeologyLootRuntimeTracker.resolveCandidateSignatures(
                    context.rootTableId(), container);
            onContainerLootResolved(context.player(), container, capture,
                    container.unsuspiciousblock$collectContainerItemCounts(candidates));
        }
    }

    // 首位解析玩家绑定追踪；覆盖和过期先结算原待定条目。
    public static void onContainerLootResolved(ServerPlayer player, TrackedContainerLootState container,
            CaptureHandle capture, Map<String, Integer> itemCounts) {
        long gameTime = player.serverLevel().getGameTime();
        long timeoutTicks = Services.LOOT_TABLE_CONFIG.getTrackingTimeoutTicks();
        UUID trackedPlayerUuid = container.unsuspiciousblock$getTrackedPlayerUuid();
        if (trackedPlayerUuid != null && !trackedPlayerUuid.equals(player.getUUID())) {
            if (container.unsuspiciousblock$isTrackingExpired(gameTime, timeoutTicks)) {
                terminate(resolveTrackingPlayer(player, container), container, EndReason.EXPIRED, null);
            }
            return;
        }
        if (trackedPlayerUuid == null && container.unsuspiciousblock$isTrackingExpired(gameTime, timeoutTicks)) {
            terminate(resolveTrackingPlayer(player, container), container, EndReason.EXPIRED, null);
        } else if (container.unsuspiciousblock$pendingJournal().current() != null) {
            terminate(player, container, EndReason.OVERWRITTEN, null);
        }
        capture.complete(itemCounts, LootSettlementTarget.container(container));
    }

    // 核对容器追踪状态：清理已过期或已标记移除的追踪，并结算残留的待定日志条目
    // 仅处理当前玩家拥有的追踪容器，超时的先 flush
    public static void reconcileTrackedContainers(ServerPlayer player, Collection<Container> rootContainers) {
        long gameTime = player.serverLevel().getGameTime();
        long timeoutTicks = Services.LOOT_TABLE_CONFIG.getTrackingTimeoutTicks();

        for (TrackedContainerLootState trackedContainer : collectOwnTrackedContainers(player, rootContainers, gameTime, timeoutTicks)) {
            ResourceLocation tableId = trackedContainer.unsuspiciousblock$tracking().settlementTable();
            trackedContainer.unsuspiciousblock$reconcileTrackedLoot();
            if (!trackedContainer.unsuspiciousblock$hasTrackedLoot()
                    && trackedContainer.unsuspiciousblock$pendingJournal().current() != null) {
                terminate(resolveTrackingPlayer(player, trackedContainer), trackedContainer,
                        EndReason.RECONCILED, tableId);
            }
        }
    }

    // 终结只提交日志最终形态；取得量已在菜单/掉落的增量阶段累计。
    private static void terminate(@Nullable ServerPlayer recipient, TrackedContainerLootState container,
                                  EndReason reason, @Nullable ResourceLocation previousTable) {
        ResourceLocation table = previousTable != null ? previousTable
                : container.unsuspiciousblock$tracking().settlementTable();
        try {
            if (recipient != null && table != null) {
                container.unsuspiciousblock$pendingJournal().finish(recipient, table);
            }
            container.unsuspiciousblock$clearAllTrackingState();
        } catch (RuntimeException | Error failure) {
            String context = "容器追踪终结失败: reason=" + reason + ", table=" + table
                    + ", owner=" + container.unsuspiciousblock$getTrackedPlayerUuid();
            failure.addSuppressed(new IllegalStateException(context));
            Constants.LOG.error(context, failure);
            throw failure;
        }
    }

    // 容器方块被破坏时 flush 其追踪状态
    public static void onContainerBlockDestroyed(ServerLevel level, BlockPos pos) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (!(blockEntity instanceof TrackedContainerLootState trackedContainer)) {
            return;
        }
        onTrackedContainerDestroyed(level, trackedContainer);
    }

    // 方块或实体容器真正销毁时，按追踪玩家结算待定日志并清空状态
    public static void onTrackedContainerDestroyed(ServerLevel level, TrackedContainerLootState trackedContainer) {
        if (!trackedContainer.unsuspiciousblock$hasTrackedLoot()
                && trackedContainer.unsuspiciousblock$pendingJournal().current() == null) {
            return;
        }
        // 尝试按追踪玩家 UUID 查找在线玩家，若离线则使用 null 作为回退（放弃该条目）
        UUID trackedUuid = trackedContainer.unsuspiciousblock$getTrackedPlayerUuid();
        ServerPlayer recipient = null;
        if (trackedUuid != null) {
            recipient = level.getServer().getPlayerList().getPlayer(trackedUuid);
        }
        terminate(recipient, trackedContainer, EndReason.DESTROYED, null);
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
                terminate(resolveTrackingPlayer(player, tracked), tracked, EndReason.EXPIRED, null);
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
                && (trackedContainer.unsuspiciousblock$hasTrackedLoot()
                    || trackedContainer.unsuspiciousblock$pendingJournal().current() != null)
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
