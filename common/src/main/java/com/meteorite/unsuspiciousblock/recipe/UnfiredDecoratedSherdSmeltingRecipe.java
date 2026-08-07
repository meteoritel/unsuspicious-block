package com.meteorite.unsuspiciousblock.recipe;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.CookingBookCategory;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.PotDecorations;

/** 未烧制纹饰陶片的动态熔炼配方，烧制结果取自输入组件中的花纹物品。 */
public class UnfiredDecoratedSherdSmeltingRecipe extends AbstractCookingRecipe {
    public UnfiredDecoratedSherdSmeltingRecipe(String group, CookingBookCategory category,
                                               Ingredient ingredient, ItemStack result,
                                               float experience, int cookingTime) {
        super(RecipeType.SMELTING, group, category, ingredient, result, experience, cookingTime);
    }

    @Override
    public ItemStack assemble(SingleRecipeInput input, HolderLookup.Provider registries) {
        PotDecorations decorations = input.item().get(DataComponents.POT_DECORATIONS);
        if (decorations == null || decorations.ordered().isEmpty()) return ItemStack.EMPTY;
        return new ItemStack(decorations.ordered().getFirst());
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return ModRecipeSerializers.UNFIRED_DECORATED_SHERD_SMELTING.get();
    }
}
