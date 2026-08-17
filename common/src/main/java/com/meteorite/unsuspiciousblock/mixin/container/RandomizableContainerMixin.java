package com.meteorite.unsuspiciousblock.mixin.container;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import com.meteorite.unsuspiciousblock.blockentity.DecoratedPotLootState;
import com.meteorite.unsuspiciousblock.blockentity.TrackedContainerLootState;
import com.meteorite.unsuspiciousblock.journal.tracking.ContainerTrackingService;
import com.meteorite.unsuspiciousblock.journal.tracking.DecoratedPotTrackingService;
import com.meteorite.unsuspiciousblock.journal.tracking.LootSession;
import com.meteorite.unsuspiciousblock.journal.tracking.LootTrackingContextHolder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.DecoratedPotBlockEntity;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
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
                                                    @Share("lootSession") LocalRef<LootSession> lootSession) {
        ResourceLocation tableId = null;
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
            return;
        }
        // 命中追踪规则时准备会话，由 fill 调用点建立异常安全的作用域
        if (trackingPlayer != null && tableId != null && level instanceof ServerLevel serverLevel) {
            // 容器位置与源方块：方块容器有 BlockEntity 位置，非方块容器回退到 ZERO / null
            BlockPos pos = BlockPos.ZERO;
            ResourceLocation sourceBlockId = null;
            if (container instanceof BlockEntity blockEntity) {
                pos = blockEntity.getBlockPos();
                sourceBlockId = BuiltInRegistries.BLOCK.getKey(blockEntity.getBlockState().getBlock());
            } else if (container instanceof Entity entity) {
                pos = entity.blockPosition();
            }
            lootSession.set(ContainerTrackingService.prepareContainerLootSession(
                    trackingPlayer, tableId, serverLevel, pos, sourceBlockId));
        }
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
            @Share("lootSession") LocalRef<LootSession> lootSession) {
        LootSession session = lootSession.get();
        try (LootTrackingContextHolder.Scope ignored = session == null
                ? LootTrackingContextHolder.open(null)
                : LootTrackingContextHolder.open(session, session.rootContext())) {
            original.call(lootTable, container, lootParams, seed);
        }
    }

    // 在解析后根据容器实际生成的物品更新运行时追踪状态
    @Inject(method = "unpackLootTable", at = @At("TAIL"))
    private void unsuspiciousblock$trackResolvedLoot(Player player,
                                                     CallbackInfo ci,
                                                     @Share("lootSession") LocalRef<LootSession> lootSession) {
        if (this instanceof DecoratedPotBlockEntity) {
            return;
        }
        if (!(this instanceof TrackedContainerLootState trackedContainer)) {
            return;
        }

        ContainerTrackingService.completeContainerLootSession(trackedContainer, lootSession.get());
    }

    // 陶罐的原版解析调用不传玩家；仅在 32 格内选择最近玩家，普通无玩家容器不参与追踪
    @Unique
    private static ServerPlayer unsuspiciousblock$resolveDecoratedPotPlayer(
            RandomizableContainer container, Level level) {
        if (!(container instanceof DecoratedPotBlockEntity pot)
                || !(level instanceof ServerLevel serverLevel)) {
            return null;
        }
        return DecoratedPotTrackingService.resolvePlayer(serverLevel, pot.getBlockPos(), null);
    }
}
