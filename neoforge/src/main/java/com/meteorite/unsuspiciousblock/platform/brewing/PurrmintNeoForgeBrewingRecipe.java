package com.meteorite.unsuspiciousblock.platform.brewing;

import com.meteorite.unsuspiciousblock.core.potion.PurrmintPotionBrewing;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.brewing.IBrewingRecipe;

/**
 * NeoForge 酿造台配方适配器——将原生 {@link IBrewingRecipe} 回调委托给跨平台的 {@link PurrmintPotionBrewing}。
 */
public final class PurrmintNeoForgeBrewingRecipe implements IBrewingRecipe {

    @Override
    public boolean isInput(ItemStack input) {
        return PurrmintPotionBrewing.isBrewingInput(input);
    }

    @Override
    public boolean isIngredient(ItemStack ingredient) {
        return PurrmintPotionBrewing.isBrewingIngredient(ingredient);
    }

    @Override
    public ItemStack getOutput(ItemStack input, ItemStack ingredient) {
        return PurrmintPotionBrewing.getOutput(input, ingredient);
    }
}
