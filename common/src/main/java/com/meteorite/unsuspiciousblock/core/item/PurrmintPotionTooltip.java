package com.meteorite.unsuspiciousblock.core.item;

import com.meteorite.unsuspiciousblock.core.effect.ModEffects;
import com.meteorite.unsuspiciousblock.core.text.TooltipBuilder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.alchemy.PotionContents;

import java.util.List;

/**
 * 灵猫药水三形态共用 Tooltip 辅助——在原版药水时长与属性提示之后，补充静音与反幽匿说明。
 */
final class PurrmintPotionTooltip {
    private static final int STRONG_AMPLIFIER = 1;
    private static final String KEY_SILENCE_BASE = "item.unsuspiciousblock.purrmint_potion.tooltip.silence_base";
    private static final String KEY_SILENCE_STRONG = "item.unsuspiciousblock.purrmint_potion.tooltip.silence_strong";

    private PurrmintPotionTooltip() {
    }

    /** 按当前药水栈包含的喵步等级追加对应的静音与防幽匿提示行。 */
    static void appendSilenceHint(ItemStack stack, List<Component> tooltipComponents) {
        int maxAmplifier = findCatfootedAmplifier(stack);
        if (maxAmplifier < 0) {
            return;
        }
        TooltipBuilder tooltip = new TooltipBuilder(tooltipComponents);
        if (maxAmplifier >= STRONG_AMPLIFIER) {
            tooltip.bullet(KEY_SILENCE_STRONG, TooltipBuilder.ACCENT);
        } else {
            tooltip.bullet(KEY_SILENCE_BASE, TooltipBuilder.POSITIVE);
        }
    }

    private static int findCatfootedAmplifier(ItemStack stack) {
        if (ModEffects.CATFOOTED == null) {
            return -1;
        }
        PotionContents contents = stack.getOrDefault(DataComponents.POTION_CONTENTS, PotionContents.EMPTY);
        int maxAmplifier = -1;
        for (MobEffectInstance effect : contents.getAllEffects()) {
            if (effect.is(ModEffects.CATFOOTED)) {
                maxAmplifier = Math.max(maxAmplifier, effect.getAmplifier());
            }
        }
        return maxAmplifier;
    }
}
