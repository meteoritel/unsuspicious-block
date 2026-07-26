package com.meteorite.unsuspiciousblock.effect;

import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;

/**
 * 猫之九命无敌缓冲标记——由伤害 Mixin 读取，不附加力量、速度或其他属性增益。
 */
public class CatFavorEffect extends MobEffect {
    private static final int COLOR = 0xFFD580;

    public CatFavorEffect() {
        super(MobEffectCategory.BENEFICIAL, COLOR);
    }
}
