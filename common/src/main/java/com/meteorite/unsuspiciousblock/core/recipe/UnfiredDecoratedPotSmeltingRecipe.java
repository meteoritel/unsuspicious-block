package com.meteorite.unsuspiciousblock.core.recipe;

import com.meteorite.unsuspiciousblock.core.pottery.PotteryFiring;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CookingBookCategory;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import org.jetbrains.annotations.NotNull;

/** 将未烧制纹饰陶罐动态转换为保留四面纹饰的原版陶罐。 */
public class UnfiredDecoratedPotSmeltingRecipe extends PotterySmeltingRecipe {
    public UnfiredDecoratedPotSmeltingRecipe(String group, CookingBookCategory category,
                                             Ingredient ingredient, ItemStack result,
                                             float experience, int cookingTime) {
        super(group, category, ingredient, result, experience, cookingTime);
    }

    @Override
    public @NotNull ItemStack assemble(@NotNull SingleRecipeInput input, HolderLookup.@NotNull Provider registries) {
        return PotteryFiring.POT.fire(input.item());
    }

    @Override
    public @NotNull RecipeSerializer<?> getSerializer() {
        return ModRecipeSerializers.UNFIRED_DECORATED_POT_SMELTING.get();
    }
}
