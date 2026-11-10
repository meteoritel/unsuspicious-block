package com.meteorite.unsuspiciousblock.platform.mixin.container;

import com.meteorite.unsuspiciousblock.core.inventory.AncientCoinRepair;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Fabric 的金币修复 Adapter：保留所有 RETURN 后且结果为空的注入条件。
 */
@Mixin(AnvilMenu.class)
public abstract class AnvilMenuMixin {

    // 原版 AnvilMenu.INPUT_SLOT / ADDITIONAL_SLOT / RESULT_SLOT
    @Unique
    private static final int INPUT_SLOT = 0;
    @Unique
    private static final int ADDITIONAL_SLOT = 1;
    @Unique
    private static final int RESULT_SLOT = 2;

    @Shadow
    private int repairItemCountCost;

    @Final
    @Shadow
    private DataSlot cost;

    @Shadow
    @Nullable
    private String itemName;

    // 在原方法所有 return 之后介入：若结果为空且右侧为古代金币，则生成修复输出并同步重命名
    @Inject(method = "createResult", at = @At("RETURN"))
    private void unsuspiciousblock$ancientCoinRepair(CallbackInfo ci) {
        AbstractContainerMenu menu = (AbstractContainerMenu) (Object) this;
        ItemStack current = menu.getSlot(RESULT_SLOT).getItem();
        // 已被原版或其他 mod 处理则不覆盖
        if (!current.isEmpty()) {
            return;
        }
        AncientCoinRepair.Result repair = AncientCoinRepair.calculate(
                menu.getSlot(INPUT_SLOT).getItem(), menu.getSlot(ADDITIONAL_SLOT).getItem(), this.itemName);
        if (repair == null) {
            return;
        }
        this.cost.set(repair.experienceCost());
        // onTake 依据此值消费金币，默认值会清空整堆。
        this.repairItemCountCost = repair.materialCost();
        menu.getSlot(RESULT_SLOT).set(repair.output());
    }
}
