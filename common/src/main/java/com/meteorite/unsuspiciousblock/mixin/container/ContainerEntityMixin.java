package com.meteorite.unsuspiciousblock.mixin.container;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import com.meteorite.unsuspiciousblock.blockentity.TrackedContainerLootState;
import com.meteorite.unsuspiciousblock.journal.tracking.ContainerTrackingService;
import com.meteorite.unsuspiciousblock.journal.tracking.LootSession;
import com.meteorite.unsuspiciousblock.journal.tracking.LootTrackingContextHolder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.ContainerEntity;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 在原版实体容器解析战利品时接入普通容器追踪，箱子矿车仍归入 LOOT_CONTAINER。
 */
@Mixin(ContainerEntity.class)
public interface ContainerEntityMixin {

    // 在实体容器清空原始战利品表字段前准备追踪会话
    @Inject(method = "unpackChestVehicleLootTable", at = @At("HEAD"))
    private void unsuspiciousblock$prepareLootTracking(
            Player player, CallbackInfo ci,
            @Share("lootSession") LocalRef<LootSession> lootSession) {
        lootSession.set(null);
        ContainerEntity container = (ContainerEntity) this;
        ResourceKey<LootTable> lootTable = container.getLootTable();
        if (!(player instanceof ServerPlayer serverPlayer)
                || lootTable == null
                || !((Object) this instanceof Entity entity)
                || !(entity.level() instanceof ServerLevel serverLevel)) {
            return;
        }

        lootSession.set(ContainerTrackingService.prepareContainerLootSession(
                serverPlayer, lootTable.location(), serverLevel, entity.blockPosition(), null));
    }

    // 仅在原版实际填充矿车库存期间激活嵌套战利品表追踪上下文
    @WrapOperation(
            method = "unpackChestVehicleLootTable",
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

    // 实体容器填充完成后，按最终库存创建普通容器待定追踪
    @Inject(method = "unpackChestVehicleLootTable", at = @At("RETURN"))
    private void unsuspiciousblock$completeLootTracking(
            Player player, CallbackInfo ci,
            @Share("lootSession") LocalRef<LootSession> lootSession) {
        if ((Object) this instanceof TrackedContainerLootState trackedContainer) {
            ContainerTrackingService.completeContainerLootSession(trackedContainer, lootSession.get());
        }
    }
}
