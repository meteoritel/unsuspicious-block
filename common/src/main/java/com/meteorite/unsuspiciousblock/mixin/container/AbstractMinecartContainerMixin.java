package com.meteorite.unsuspiciousblock.mixin.container;

import com.meteorite.unsuspiciousblock.blockentity.TrackedContainerLootState;
import com.meteorite.unsuspiciousblock.journal.tracking.ContainerLootTracking;
import com.meteorite.unsuspiciousblock.journal.tracking.ContainerTrackingService;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.AbstractMinecartContainer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;


/**
 * 为原版随机战利品矿车保存运行时追踪状态，并接入实体存档与销毁生命周期。
 */
@Mixin(AbstractMinecartContainer.class)
public abstract class AbstractMinecartContainerMixin implements TrackedContainerLootState {
    @Unique
    private final ContainerLootTracking unsuspiciousblock$tracking =
            new ContainerLootTracking(() -> {  });

    @Override
    public ContainerLootTracking unsuspiciousblock$tracking() {
        return this.unsuspiciousblock$tracking;
    }

    // 原版矿车保存物品与战利品表后，追加本模组追踪状态
    @Inject(method = "addAdditionalSaveData", at = @At("TAIL"))
    private void unsuspiciousblock$saveTrackedLootData(CompoundTag tag, CallbackInfo ci) {
        this.unsuspiciousblock$writeTrackedLootData(tag);
    }

    // 原版矿车读取物品与战利品表后，恢复本模组追踪状态
    @Inject(method = "readAdditionalSaveData", at = @At("TAIL"))
    private void unsuspiciousblock$loadTrackedLootData(CompoundTag tag, CallbackInfo ci) {
        this.unsuspiciousblock$readTrackedLootData(tag);
    }

    // 仅在实体真正销毁时结算待定日志；区块卸载仍依靠实体 NBT 保留状态
    @Inject(method = "remove", at = @At("HEAD"))
    private void unsuspiciousblock$flushTrackedLootOnDestroy(Entity.RemovalReason reason, CallbackInfo ci) {
        AbstractMinecartContainer minecart = (AbstractMinecartContainer) (Object) this;
        if (reason.shouldDestroy() && minecart.level() instanceof ServerLevel serverLevel) {
            ContainerTrackingService.onTrackedContainerDestroyed(serverLevel, this);
        }
    }

}
