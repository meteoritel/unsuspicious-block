package com.meteorite.unsuspiciousblock.platform.mixin.compat.create;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.meteorite.unsuspiciousblock.core.recipe.PotterySmeltingRecipe;
import com.simibubi.create.content.kinetics.fan.processing.AllFanProcessingTypes.BlastingType;
import com.simibubi.create.foundation.item.ItemHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;

/** 将 Create 鼓风机选中的制陶配方转交给共用的动态产物规则。 */
@SuppressWarnings({"UnusedMixin", "unused"}) // CreateFanMixinPlugin 按版本动态登记。
@Mixin(value = BlastingType.class, remap = false)
public abstract class BlastingTypeMixin {
    @WrapOperation(
            method = "process(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/level/Level;)Ljava/util/List;",
            at = @At(value = "INVOKE",
                    target = "Lcom/simibubi/create/foundation/recipe/RecipeApplier;applyRecipeOn(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/crafting/Recipe;Z)Ljava/util/List;"),
            allow = 1)
    private @Nullable List<ItemStack> unsuspiciousblock$assemblePottery(
            Level level, ItemStack stackIn, Recipe<?> recipe, boolean returnProcessingRemainder,
            Operation<List<ItemStack>> original) {
        if (!(recipe instanceof PotterySmeltingRecipe pottery)) {
            return original.call(level, stackIn, recipe, returnProcessingRemainder);
        }
        ItemStack result = pottery.assemble(new SingleRecipeInput(stackIn), level.registryAccess());
        // Create 用 null 保留输入，空列表会消耗坯件。
        return result.isEmpty() ? null : ItemHelper.multipliedOutput(stackIn, result);
    }
}
