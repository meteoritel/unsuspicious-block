package com.meteorite.unsuspiciousblock.core.item;

import com.meteorite.unsuspiciousblock.core.potion.ModPotions;
import com.meteorite.unsuspiciousblock.core.potion.PurrmintPotionBrewing;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.LingeringPotionItem;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

/**
 * 滞留型灵猫药水——投掷破碎后生成持续效果云，为经过的生物赋予「喵步」效果。
 */
public class LingeringPurrmintPotionItem extends LingeringPotionItem {

    public LingeringPurrmintPotionItem(Properties properties) {
        super(properties);
    }

    @Override
    public ItemStack getDefaultInstance() {
        return ModPotions.PURRMINT != null
                ? PurrmintPotionBrewing.createStack(this, ModPotions.PURRMINT)
                : super.getDefaultInstance();
    }

    @Override
    public String getDescriptionId(ItemStack stack) {
        return getDescriptionId();
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltipComponents,
                                TooltipFlag tooltipFlag) {
        super.appendHoverText(stack, context, tooltipComponents, tooltipFlag);
        PurrmintPotionTooltip.appendSilenceHint(stack, tooltipComponents);
    }
}
