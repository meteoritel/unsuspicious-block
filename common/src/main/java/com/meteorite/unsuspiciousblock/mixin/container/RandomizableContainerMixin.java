package com.meteorite.unsuspiciousblock.mixin.container;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import com.meteorite.unsuspiciousblock.blockentity.DecoratedPotLootState;
import com.meteorite.unsuspiciousblock.blockentity.TrackedContainerLootState;
import com.meteorite.unsuspiciousblock.journal.catalog.ArchaeologyJournalServerCatalog;
import com.meteorite.unsuspiciousblock.journal.state.LootSourceType;
import com.meteorite.unsuspiciousblock.journal.tracking.ArchaeologyLootRuntimeTracker;
import com.meteorite.unsuspiciousblock.journal.tracking.ContainerTrackingService;
import com.meteorite.unsuspiciousblock.journal.tracking.DecoratedPotTrackingService;
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
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.DecoratedPotBlockEntity;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;

import java.util.List;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 在可随机生成战利品的容器解析时接入考古笔记追踪。
 */
@Mixin(RandomizableContainer.class)
public interface RandomizableContainerMixin {

    // 在解析前捕获本次容器将要使用的战利品表标识与追踪上下文
    @Inject(method = "unpackLootTable", at = @At("HEAD"))
    private void unsuspiciousblock$captureLootTable(Player player,
                                                    CallbackInfo ci,
                                                    @Share("capturedLootTable") LocalRef<ResourceLocation> capturedLootTable,
                                                    @Share("trackingContext") LocalRef<LootTrackingContext> trackingContext,
                                                    @Share("lootSession") LocalRef<LootSession> lootSession) {
        ResourceLocation tableId = null;
        trackingContext.set(null);
        lootSession.set(null);
        RandomizableContainer container = (RandomizableContainer) this;
        Level level = container.getLevel();
        ResourceKey<LootTable> lootTable = container.getLootTable();
        if (lootTable != null && level != null && !level.isClientSide() && level.getServer() != null) {
            tableId = lootTable.location();
        }
        // 陶罐原版始终以 null 解析战利品表，只能使用附近玩家作为追踪归属
        ServerPlayer trackingPlayer = player instanceof ServerPlayer sp
                ? sp
                : unsuspiciousblock$resolveDecoratedPotPlayer(container, level);
        // 陶罐只捕获原始表与归属玩家，破坏时再按最终单物品栈进行 immediate 提交
        if (container instanceof DecoratedPotBlockEntity) {
            if (tableId != null && container instanceof DecoratedPotLootState potState) {
                potState.unsuspiciousblock$setDecoratedPotLootTableName(tableId);
                if (trackingPlayer != null) {
                    potState.unsuspiciousblock$setDecoratedPotPlayerUuid(trackingPlayer.getUUID());
                }
            }
            capturedLootTable.set(tableId);
            return;
        }
        // 命中追踪规则时准备上下文，由 fill 调用点建立异常安全的作用域
        if (trackingPlayer != null && tableId != null
                && ArchaeologyJournalServerCatalog.isTrackedTable(tableId)
                && level instanceof ServerLevel serverLevel) {
            // 容器位置与源方块：方块容器有 BlockEntity 位置，非方块容器回退到 ZERO / null
            BlockPos pos = BlockPos.ZERO;
            ResourceLocation sourceBlockId = null;
            if (container instanceof BlockEntity blockEntity) {
                pos = blockEntity.getBlockPos();
                sourceBlockId = BuiltInRegistries.BLOCK.getKey(blockEntity.getBlockState().getBlock());
            }
            LootTrackingContext context = LootTrackingContext.root(
                    trackingPlayer, tableId, LootSourceType.LOOT_CONTAINER,
                    serverLevel.getGameTime(), serverLevel.getDayTime(), pos, sourceBlockId);
            trackingContext.set(context);
            lootSession.set(new LootSession(context));
        }

        capturedLootTable.set(tableId);
    }

    // 仅在 LootTable.fill 执行期间激活上下文，异常时由 Scope 自动恢复栈
    @WrapOperation(
            method = "unpackLootTable",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/storage/loot/LootTable;fill(Lnet/minecraft/world/Container;Lnet/minecraft/world/level/storage/loot/LootParams;J)V")
    )
    private void unsuspiciousblock$scopeLootRoll(
            LootTable lootTable, Container container, LootParams lootParams, long seed,
            Operation<Void> original,
            @Share("trackingContext") LocalRef<LootTrackingContext> trackingContext,
            @Share("lootSession") LocalRef<LootSession> lootSession) {
        try (LootTrackingContextHolder.Scope ignored = LootTrackingContextHolder.open(
                lootSession.get(), trackingContext.get())) {
            original.call(lootTable, container, lootParams, seed);
        }
    }

    // 在解析后根据容器实际生成的物品更新运行时追踪状态
    @Inject(method = "unpackLootTable", at = @At("TAIL"))
    private void unsuspiciousblock$trackResolvedLoot(Player player,
                                                     CallbackInfo ci,
                                                     @Share("capturedLootTable") LocalRef<ResourceLocation> capturedLootTable,
                                                     @Share("lootSession") LocalRef<LootSession> lootSession) {
        if ((Object) this instanceof DecoratedPotBlockEntity) {
            return;
        }
        if (!(this instanceof TrackedContainerLootState trackedContainer)) {
            return;
        }

        ResourceLocation tableId = capturedLootTable.get();
        if (tableId == null || !ArchaeologyJournalServerCatalog.isTrackedTable(tableId)) {
            return;
        }

        List<LootResultSignature> candidates = ArchaeologyLootRuntimeTracker.resolveCandidateSignatures(
                tableId, trackedContainer);
        LootSession session = lootSession.get();
        if (session == null) {
            return;
        }
        ServerPlayer sp = session.rootContext().player();
        ContainerTrackingService.onContainerLootResolved(sp, trackedContainer, session,
                trackedContainer.unsuspiciousblock$collectContainerItemCounts(candidates));
    }

    // 陶罐的原版解析调用不传玩家；仅在 32 格内选择最近玩家，普通无玩家容器不参与追踪
    private static ServerPlayer unsuspiciousblock$resolveDecoratedPotPlayer(
            RandomizableContainer container, Level level) {
        if (!(container instanceof DecoratedPotBlockEntity pot)
                || !(level instanceof ServerLevel serverLevel)) {
            return null;
        }
        return DecoratedPotTrackingService.resolvePlayer(serverLevel, pot.getBlockPos(), null);
    }
}
