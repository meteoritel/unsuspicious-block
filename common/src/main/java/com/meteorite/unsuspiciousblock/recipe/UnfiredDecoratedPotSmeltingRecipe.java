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
import net.minecraft.world.level.block.entity.DecoratedPotBlockEntity;
import net.minecraft.world.level.block.entity.PotDecorations;

/** 将未烧制纹饰陶罐动态转换为保留四面纹饰的原版陶罐。 */
public class UnfiredDecoratedPotSmeltingRecipe extends AbstractCookingRecipe {
    public UnfiredDecoratedPotSmeltingRecipe(String group, CookingBookCategory category,
                                             Ingredient ingredient, ItemStack result,
                                             float experience, int cookingTime) {
        super(RecipeType.SMELTING, group, category, ingredient, result, experience, cookingTime);
    }

    @Override
    public ItemStack assemble(SingleRecipeInput input, HolderLookup.Provider registries) {
        PotDecorations decorations = input.item().getOrDefault(
                DataComponents.POT_DECORATIONS, PotDecorations.EMPTY);
        return DecoratedPotBlockEntity.createDecoratedPotItem(decorations);
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return ModRecipeSerializers.UNFIRED_DECORATED_POT_SMELTING.get();
    }
}
