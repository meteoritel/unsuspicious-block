package com.meteorite.unsuspiciousblock.core.plugin.lootr;

import com.meteorite.unsuspiciousblock.core.blockentity.TrackedContainerLootState;
import com.meteorite.unsuspiciousblock.core.journal.catalog.ArchaeologyJournalServerCatalog;
import com.meteorite.unsuspiciousblock.core.journal.state.LootSourceType;
import com.meteorite.unsuspiciousblock.core.journal.tracking.ArchaeologyLootRuntimeTracker;
import com.meteorite.unsuspiciousblock.core.journal.tracking.ContainerTrackingService;
import com.meteorite.unsuspiciousblock.core.journal.tracking.LootCapture;
import com.meteorite.unsuspiciousblock.core.journal.tracking.LootCapture.CaptureHandle;
import com.meteorite.unsuspiciousblock.core.loottable.signature.LootResultSignature;
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
import java.util.function.Supplier;

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
        if (!ArchaeologyJournalServerCatalog.tracked(tableId)) {
            return null;
        }

        BlockPos pos = provider.getInfoPos();
        ResourceLocation sourceBlockId = null;
        if (provider.getInfoContainer() instanceof BlockEntity blockEntity) {
            sourceBlockId = BuiltInRegistries.BLOCK.getKey(blockEntity.getBlockState().getBlock());
        }

        CaptureHandle capture = LootCapture.begin(new LootCapture.Source(
                serverPlayer, tableId, sourceType, serverLevel, pos, sourceBlockId));
        return new Session(serverPlayer, tableId, capture);
    }

    // 保持 Lootr 的生成调用相位，异常时由捕获句柄关闭作用域。
    @SuppressWarnings("resource") // 句柄跨 Lootr 注入相位持有，由 commit 关闭。
    public static <T> T roll(@Nullable Session session, Supplier<T> generator) {
        return session == null ? generator.get() : session.capture().roll(generator);
    }

    // 将 Lootr 实际生成的每玩家库存提交到容器追踪服务
    public static void commit(@Nullable Session session, Container inventory) {
        if (session == null) return;
        try (var capture = session.capture()) {
            if (!(inventory instanceof TrackedContainerLootState trackedContainer) || capture.context() == null) return;
            List<LootResultSignature> candidates = ArchaeologyLootRuntimeTracker.resolveCandidateSignatures(
                    session.tableId(), trackedContainer);
            ContainerTrackingService.onContainerLootResolved(session.player(), trackedContainer, capture,
                    trackedContainer.unsuspiciousblock$collectContainerItemCounts(candidates));
        }
    }

    /**
     * 单次 Lootr 战利品生成所需的不可变追踪信息。
     */
    public record Session(ServerPlayer player, ResourceLocation tableId,
                          CaptureHandle capture) {
    }
}
