package com.meteorite.unsuspiciousblock.core.recipe;

import net.minecraft.core.HolderLookup;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CookingBookCategory;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import org.jetbrains.annotations.NotNull;

/** 制陶熔炼的产物由输入纹饰决定，供支持动态产出的熔炉执行。 */
public abstract class PotterySmeltingRecipe extends SmeltingRecipe {
    protected PotterySmeltingRecipe(String group, CookingBookCategory category, Ingredient ingredient,
                                    ItemStack result, float experience, int cookingTime) {
        super(group, category, ingredient, result, experience, cookingTime);
    }

    /** 根据当前输入栈的纹饰组件生成一份实际产物。 */
    @Override
    public abstract @NotNull ItemStack assemble(
            @NotNull SingleRecipeInput input, HolderLookup.@NotNull Provider registries);

    @Override
    public final boolean isSpecial() {
        return true;
    }

    /** 产物需要实际输入，固定产物机器可据此跳过转换。 */
    @Override
    public final @NotNull ItemStack getResultItem(HolderLookup.@NotNull Provider registries) {
        return ItemStack.EMPTY;
    }
}
