package com.meteorite.unsuspiciousblock.core.mixin.container;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.meteorite.unsuspiciousblock.core.enchantment.EnchantedBookRoller;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ItemCombinerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.SmithingMenu;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** 在快速移动耗空源槽前鉴定实际交付的锻造书。 */
@Mixin(ItemCombinerMenu.class)
public abstract class ItemCombinerMenuMixin {
    @WrapOperation(method = "quickMoveStack", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/inventory/ItemCombinerMenu;moveItemStackTo(Lnet/minecraft/world/item/ItemStack;IIZ)Z",
            ordinal = 0))
    private boolean unsuspiciousblock$moveSmithingBook(ItemCombinerMenu menu, ItemStack source,
            int start, int end, boolean reverse, Operation<Boolean> original,
            @Local(argsOnly = true) Player player) {
        if (!(menu instanceof SmithingMenu) || !(player instanceof ServerPlayer serverPlayer)
                || !EnchantedBookRoller.isPending(source)) {
            return original.call(menu, source, start, end, reverse);
        }

        // 结果槽向后扫描背包。预览书本身不可堆叠，找到可交换的空槽后才掷骰。
        for (int index = end - 1; index >= start; index--) {
            Slot destination = menu.getSlot(index);
            if (!destination.hasItem() && destination.mayPlace(source)
                    && destination.getMaxStackSize(source) >= source.getCount()) {
                ItemStack result = source.split(source.getCount());
                EnchantedBookRoller.reveal(result, serverPlayer);
                destination.setByPlayer(result);
                return true;
            }
        }
        return false;
    }
}
