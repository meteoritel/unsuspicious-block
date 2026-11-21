package com.meteorite.unsuspiciousblock.core.recipe;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CookingBookCategory;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.PotDecorations;
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
        PotDecorations decorations = input.item().get(DataComponents.POT_DECORATIONS);
        if (decorations == null || decorations.ordered().isEmpty()) {
            return new ItemStack(Items.BRICK);
        }
        return new ItemStack(decorations.ordered().getFirst());
    }

    @Override
    public @NotNull RecipeSerializer<?> getSerializer() {
        return ModRecipeSerializers.UNFIRED_DECORATED_SHERD_SMELTING.get();
    }
}
