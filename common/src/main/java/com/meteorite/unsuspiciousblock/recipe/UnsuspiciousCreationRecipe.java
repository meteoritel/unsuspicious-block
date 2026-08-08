package com.meteorite.unsuspiciousblock.recipe;

import com.meteorite.unsuspiciousblock.item.ModItems;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.NotNull;

/**
 * 使用考古铲和沙子或沙砾制作空不可疑方块，并让考古铲损失一点耐久。
 */
public class UnsuspiciousCreationRecipe extends CustomRecipe {
    private final UnsuspiciousSealingRecipe.Variant variant;

    public UnsuspiciousCreationRecipe(CraftingBookCategory category,
                                      UnsuspiciousSealingRecipe.Variant variant) {
        super(category);
        this.variant = variant;
    }

    @Override
    public boolean matches(@NotNull CraftingInput input, @NotNull Level level) {
        return hasRequiredInputs(input);
    }

    @Override
    public @NotNull ItemStack assemble(@NotNull CraftingInput input,
                                       @NotNull HolderLookup.Provider registries) {
        return hasRequiredInputs(input) ? new ItemStack(this.variant.outputItem()) : ItemStack.EMPTY;
    }

    @Override
    public @NotNull NonNullList<ItemStack> getRemainingItems(@NotNull CraftingInput input) {
        NonNullList<ItemStack> remaining = NonNullList.withSize(input.size(), ItemStack.EMPTY);
        for (int index = 0; index < input.size(); index++) {
            ItemStack stack = input.getItem(index);
            if (!stack.is(ModItems.ARCHAEOLOGICAL_SHOVEL)) {
                continue;
            }

            ItemStack damagedShovel = stack.copyWithCount(1);
            int damage = damagedShovel.getDamageValue() + 1;
            if (damage < damagedShovel.getMaxDamage()) {
                damagedShovel.setDamageValue(damage);
                remaining.set(index, damagedShovel);
            }
            break;
        }
        return remaining;
    }

    @Override
    public boolean canCraftInDimensions(int width, int height) {
        return width * height >= 2;
    }

    @Override
    public @NotNull RecipeSerializer<?> getSerializer() {
        return ModRecipeSerializers.getCreation(this.variant);
    }

    // 验证输入中恰好包含一个对应基底和一把考古铲
    private boolean hasRequiredInputs(CraftingInput input) {
        boolean foundBase = false;
        boolean foundShovel = false;
        int occupiedSlots = 0;

        for (int index = 0; index < input.size(); index++) {
            ItemStack stack = input.getItem(index);
            if (stack.isEmpty()) {
                continue;
            }
            occupiedSlots++;
            if (stack.is(this.variant.baseItem()) && !foundBase) {
                foundBase = true;
            } else if (stack.is(ModItems.ARCHAEOLOGICAL_SHOVEL) && !foundShovel) {
                foundShovel = true;
            } else {
                return false;
            }
        }
        return occupiedSlots == 2 && foundBase && foundShovel;
    }
}
