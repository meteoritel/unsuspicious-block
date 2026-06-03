package com.meteorite.unsuspiciousblock.mixin;

import com.meteorite.unsuspiciousblock.journal.tracking.ArchaeologyLootRuntimeTracker;
import net.minecraft.core.NonNullList;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Collection;
import java.util.LinkedHashSet;

/**
 * 为容器菜单补充战利品入包追踪。
 */
@Mixin(AbstractContainerMenu.class)
public abstract class AbstractContainerMenuMixin {
    @Final
    @Shadow
    public NonNullList<Slot> slots;

    @Unique
    @Nullable
    private ArchaeologyLootRuntimeTracker.MenuTrackingSnapshot unsuspiciousblock$menuTrackingSnapshot;

    // 在点击处理前记录玩家背包与相关容器的追踪快照
    @Inject(method = "clicked", at = @At("HEAD"))
    private void unsuspiciousblock$captureInventoryCountsBeforeClick(int slotId, int button, ClickType clickType, Player player, CallbackInfo ci) {
        if (!(player instanceof ServerPlayer sp)) {
            return;
        }

        this.unsuspiciousblock$menuTrackingSnapshot = ArchaeologyLootRuntimeTracker.captureMenuTrackingSnapshot(sp,
                this.unsuspiciousblock$collectRootContainers());
    }

    // 在点击处理后根据背包增量结算真正进入背包的战利品
    @Inject(method = "clicked", at = @At("TAIL"))
    private void unsuspiciousblock$trackInventoryCountsAfterClick(int slotId, int button, ClickType clickType, Player player, CallbackInfo ci) {
        if (this.unsuspiciousblock$menuTrackingSnapshot == null) {
            return;
        }

        try {
            if (player instanceof ServerPlayer sp) {
                ArchaeologyLootRuntimeTracker.applyMenuTrackingSnapshot(sp, this.unsuspiciousblock$menuTrackingSnapshot);
            }
        } finally {
            this.unsuspiciousblock$menuTrackingSnapshot = null;
        }
    }

    // 在菜单关闭后校正容器内尚未被拿走的追踪数据
    @Inject(method = "removed", at = @At("TAIL"))
    private void unsuspiciousblock$reconcileTrackedLootOnClose(Player player, CallbackInfo ci) {
        if (!(player instanceof ServerPlayer)) {
            return;
        }

        ArchaeologyLootRuntimeTracker.reconcileTrackedContainers(this.unsuspiciousblock$collectRootContainers());
    }

    // 收集当前菜单关联的根容器，供运行时追踪统一处理
    @Unique
    private Collection<Container> unsuspiciousblock$collectRootContainers() {
        LinkedHashSet<Container> rootContainers = new LinkedHashSet<>();
        for (Slot slot : this.slots) {
            rootContainers.add(slot.container);
        }
        return rootContainers;
    }
}
