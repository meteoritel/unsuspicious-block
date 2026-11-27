package com.meteorite.unsuspiciousblock.core.item;

import com.meteorite.unsuspiciousblock.core.potion.ModPotions;
import com.meteorite.unsuspiciousblock.core.potion.PurrmintPotionBrewing;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.PotionItem;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

/**
 * 灵猫药水（饮用型）——饮用后赋予「喵步」效果，由粗制药水与咕噜荆芥酿造而成。
 */
public class PurrmintPotionItem extends PotionItem {

    public PurrmintPotionItem(Properties properties) {
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
