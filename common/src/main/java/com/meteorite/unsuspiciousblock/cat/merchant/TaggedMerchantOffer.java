package com.meteorite.unsuspiciousblock.cat.merchant;

import net.minecraft.tags.TagKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;

/**
 * 支持任意 Tag 成员作为第一项输入的交易报价。
 */
public class TaggedMerchantOffer extends MerchantOffer {
    private final TagKey<Item> acceptedTag;
    private final int acceptedCount;

    public TaggedMerchantOffer(ItemCost displayedCost, ItemStack result, int maxUses,
                               TagKey<Item> acceptedTag, int acceptedCount) {
        super(displayedCost, result, maxUses, 0, 0.05F);
        this.acceptedTag = acceptedTag;
        this.acceptedCount = acceptedCount;
    }

    public ResourceLocation getAcceptedTagId() {
        return this.acceptedTag.location();
    }

    public int getAcceptedCount() {
        return this.acceptedCount;
    }

    @Override
    public boolean satisfiedBy(ItemStack first, ItemStack second) {
        return first.is(this.acceptedTag) && first.getCount() >= this.acceptedCount && second.isEmpty();
    }

    @Override
    public boolean take(ItemStack first, ItemStack second) {
        if (!this.satisfiedBy(first, second)) {
            return false;
        }
        first.shrink(this.acceptedCount);
        return true;
    }
}
