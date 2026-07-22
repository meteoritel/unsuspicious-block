package com.meteorite.unsuspiciousblock.plugin.lootr;

import com.meteorite.unsuspiciousblock.blockentity.TrackedContainerLootState;
import com.meteorite.unsuspiciousblock.journal.catalog.ArchaeologyJournalServerCatalog;
import com.meteorite.unsuspiciousblock.journal.state.LootSourceType;
import com.meteorite.unsuspiciousblock.journal.tracking.ArchaeologyLootRuntimeTracker;
import com.meteorite.unsuspiciousblock.journal.tracking.ContainerTrackingService;
import com.meteorite.unsuspiciousblock.journal.tracking.LootSession;
import com.meteorite.unsuspiciousblock.journal.tracking.LootTrackingContext;
import com.meteorite.unsuspiciousblock.journal.tracking.LootTrackingContextHolder;
import com.meteorite.unsuspiciousblock.loottable.signature.LootResultSignature;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.storage.loot.LootTable;
import noobanidus.mods.lootr.common.api.data.ILootrInfoProvider;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Lootr 战利品生成流程与本模组容器追踪系统之间的共享桥接层。
 */
@SuppressWarnings("UnstableApiUsage")
public final class LootrTrackingBridge {
    private LootrTrackingBridge() {
    }

    // 创建并压入本次 Lootr 战利品生成上下文；不需要追踪时返回 null
    @Nullable
    public static Session prepare(ILootrInfoProvider provider, Player player, LootSourceType sourceType) {
        if (!(player instanceof ServerPlayer serverPlayer) || provider.isInfoReferenceInventory()) {
            return null;
        }

        ResourceKey<LootTable> lootTableKey = provider.getInfoLootTable();
        Level level = provider.getInfoLevel();
        if (lootTableKey == null || !(level instanceof ServerLevel serverLevel)) {
            return null;
        }

        ResourceLocation tableId = lootTableKey.location();
        if (!ArchaeologyJournalServerCatalog.isTrackedTable(tableId)) {
            return null;
        }

        BlockPos pos = provider.getInfoPos();
        ResourceLocation sourceBlockId = null;
        if (provider.getInfoContainer() instanceof BlockEntity blockEntity) {
            sourceBlockId = BuiltInRegistries.BLOCK.getKey(blockEntity.getBlockState().getBlock());
        }

        LootTrackingContext context = LootTrackingContext.root(
                serverPlayer, tableId, sourceType,
                serverLevel.getGameTime(), serverLevel.getDayTime(), pos, sourceBlockId);
        return new Session(serverPlayer, tableId, context, new LootSession(context));
    }

    // 为 Lootr 的实际 loot roll 建立异常安全的追踪作用域
    public static LootTrackingContextHolder.Scope openScope(@Nullable Session session) {
        return session == null
                ? LootTrackingContextHolder.open(null)
                : LootTrackingContextHolder.open(session.lootSession(), session.context());
    }

    // 将 Lootr 实际生成的每玩家库存提交到容器追踪服务
    public static void commit(@Nullable Session session, Container inventory) {
        if (session == null || !(inventory instanceof TrackedContainerLootState trackedContainer)) {
            return;
        }

        List<LootResultSignature> candidates = ArchaeologyLootRuntimeTracker.resolveCandidateSignatures(
                session.tableId(), trackedContainer);
        ContainerTrackingService.onContainerLootResolved(
                session.player(), trackedContainer, session.lootSession(),
                trackedContainer.unsuspiciousblock$collectContainerItemCounts(candidates));
    }

    /**
     * 单次 Lootr 战利品生成所需的不可变追踪信息。
     */
    public record Session(ServerPlayer player, ResourceLocation tableId,
                          LootTrackingContext context, LootSession lootSession) {
    }
}
