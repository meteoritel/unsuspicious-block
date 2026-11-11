package com.meteorite.unsuspiciousblock.core.recipe;

import com.meteorite.unsuspiciousblock.core.item.ModItems;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/** 封存与空方块制作共用的载体类型。 */
public enum UnsuspiciousCarrier {
    SAND, GRAVEL;

    /** 制作空载体需要的原版基底。 */
    public Item baseItem() {
        return switch (this) {
            case SAND -> Items.SAND;
            case GRAVEL -> Items.GRAVEL;
        };
    }

    /** 已注册的不可疑方块物品，延迟到使用配方时读取。 */
    public Item outputItem() {
        return switch (this) {
            case SAND -> ModItems.UNSUSPICIOUS_SAND;
            case GRAVEL -> ModItems.UNSUSPICIOUS_GRAVEL;
        };
    }
}
