package com.meteorite.unsuspiciousblock.core.recipe;

import com.meteorite.unsuspiciousblock.core.pottery.PotteryFiring;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CookingBookCategory;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import org.jetbrains.annotations.NotNull;

/** 未烧制纹饰陶片的动态熔炼配方，烧制结果取自输入组件中的花纹物品。 */
public class UnfiredDecoratedSherdSmeltingRecipe extends PotterySmeltingRecipe {
    public UnfiredDecoratedSherdSmeltingRecipe(String group, CookingBookCategory category,
                                               Ingredient ingredient, ItemStack result,
                                               float experience, int cookingTime) {
        super(group, category, ingredient, result, experience, cookingTime);
    }

    @Override
    public @NotNull ItemStack assemble(@NotNull SingleRecipeInput input, HolderLookup.@NotNull Provider registries) {
        return PotteryFiring.SHERD.fire(input.item());
    }

    @Override
    public @NotNull RecipeSerializer<?> getSerializer() {
        return ModRecipeSerializers.UNFIRED_DECORATED_SHERD_SMELTING.get();
    }
}
