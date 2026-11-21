package com.meteorite.unsuspiciousblock.platform.mixin.container;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.meteorite.unsuspiciousblock.core.recipe.PotterySmeltingRecipe;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** 让 Fabric 熔炉使用实际制陶产物校验输出槽并完成烧制。 */
@Mixin(AbstractFurnaceBlockEntity.class)
public abstract class AbstractFurnaceBlockEntityMixin {
    @WrapOperation(
            method = {"canBurn", "burn"},
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/item/crafting/Recipe;getResultItem(Lnet/minecraft/core/HolderLookup$Provider;)Lnet/minecraft/world/item/ItemStack;"),
            require = 2)
    private static ItemStack unsuspiciousblock$assemblePottery(
            Recipe<?> recipe, HolderLookup.Provider registries, Operation<ItemStack> original,
            @Local(argsOnly = true) NonNullList<ItemStack> inventory) {
        if (recipe instanceof PotterySmeltingRecipe pottery) {
            return pottery.assemble(new SingleRecipeInput(inventory.getFirst()), registries);
        }
        return original.call(recipe, registries);
    }
}
