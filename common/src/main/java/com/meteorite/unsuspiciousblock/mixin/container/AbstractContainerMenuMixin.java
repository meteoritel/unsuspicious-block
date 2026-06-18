package com.meteorite.unsuspiciousblock.mixin.container;

import com.meteorite.unsuspiciousblock.journal.tracking.ArchaeologyLootRuntimeTracker;
import net.minecraft.core.NonNullList;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
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

    @Shadow
    public abstract ItemStack getCarried();

    @Unique
    @Nullable
    private ArchaeologyLootRuntimeTracker.MenuTrackingSnapshot unsuspiciousblock$menuTrackingSnapshot;

    // 在点击处理前记录玩家背包与光标中相关物品的快照
    @Inject(method = "clicked", at = @At("HEAD"))
    private void unsuspiciousblock$captureInventoryCountsBeforeClick(int slotId, int button, ClickType clickType, Player player, CallbackInfo ci) {
        if (!(player instanceof ServerPlayer sp)) {
            return;
        }

        this.unsuspiciousblock$menuTrackingSnapshot = ArchaeologyLootRuntimeTracker.captureMenuTrackingSnapshot(sp,
                this.unsuspiciousblock$collectRootContainers(), this.getCarried());
    }

    // 在点击处理后根据背包增量与光标增量结算真正进入背包的战利品
    @Inject(method = "clicked", at = @At("TAIL"))
    private void unsuspiciousblock$trackInventoryCountsAfterClick(int slotId, int button, ClickType clickType, Player player, CallbackInfo ci) {
        if (this.unsuspiciousblock$menuTrackingSnapshot == null) {
            return;
        }

        if (player instanceof ServerPlayer sp) {
            if (ArchaeologyLootRuntimeTracker.applyMenuTrackingSnapshot(sp, this.unsuspiciousblock$menuTrackingSnapshot, this.getCarried())) {
                this.unsuspiciousblock$menuTrackingSnapshot = null;
            }
        } else {
            // 非服务端玩家，清除快照防止泄漏
            this.unsuspiciousblock$menuTrackingSnapshot = null;
        }
    }

    // 在菜单关闭后：先处理光标物品→物品栏的转移，再校正容器追踪数据
    @Inject(method = "removed", at = @At("TAIL"))
    private void unsuspiciousblock$reconcileTrackedLootOnClose(Player player, CallbackInfo ci) {
        if (!(player instanceof ServerPlayer sp)) {
            return;
        }

        // 若快照仍存在（物品在光标上但未被 click TAIL 结算），此时 vanilla removed() 已将光标物品放入物品栏
        // 重新应用快照，光标应已空，增量体现在背包中
        if (this.unsuspiciousblock$menuTrackingSnapshot != null) {
            ArchaeologyLootRuntimeTracker.applyMenuTrackingSnapshot(sp, this.unsuspiciousblock$menuTrackingSnapshot, ItemStack.EMPTY);
            this.unsuspiciousblock$menuTrackingSnapshot = null;
        }

        // 校正容器追踪数据（结算残留待定条目后清理）
        ArchaeologyLootRuntimeTracker.reconcileTrackedContainers(sp, this.unsuspiciousblock$collectRootContainers());
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
