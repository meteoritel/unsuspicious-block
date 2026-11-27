package com.meteorite.unsuspiciousblock.core.item;

import com.meteorite.unsuspiciousblock.core.text.TooltipBuilder;
import net.minecraft.network.chat.Component;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * 勉强粘好的胡萝卜——由上下两半胡萝卜与蜂蜜瓶拼合而成的彩蛋食物。
 * 食用后除提供可观饱食度与幸运收获增益外，还延续蜂蜜特性清除中毒效果。
 */
public class BarelyGluedCarrotItem extends Item {

    private static final int NUTRITION = 6;
    private static final float SATURATION_MODIFIER = 0.6F;
    private static final int LUCK_DURATION_TICKS = 3600;
    private static final int HASTE_DURATION_TICKS = 2400;
    private static final int REGEN_DURATION_TICKS = 200;
    private static final float GUARANTEED_EFFECT_CHANCE = 1.0F;

    private static final String DESC_KEY = "item.unsuspiciousblock.barely_glued_carrot.tooltip.desc";
    private static final String CURE_KEY = "item.unsuspiciousblock.barely_glued_carrot.tooltip.cure";

    /** 6 饥饿 / 7.2 饱和，满饱食度可食用，附带幸运 I、急迫 I 与生命恢复 I。 */
    public static final FoodProperties FOOD_PROPERTIES = new FoodProperties.Builder()
            .nutrition(NUTRITION)
            .saturationModifier(SATURATION_MODIFIER)
            .alwaysEdible()
            .effect(new MobEffectInstance(MobEffects.LUCK, LUCK_DURATION_TICKS, 0), GUARANTEED_EFFECT_CHANCE)
            .effect(new MobEffectInstance(MobEffects.DIG_SPEED, HASTE_DURATION_TICKS, 0), GUARANTEED_EFFECT_CHANCE)
            .effect(new MobEffectInstance(MobEffects.REGENERATION, REGEN_DURATION_TICKS, 0), GUARANTEED_EFFECT_CHANCE)
            .build();

    /** 构造勉强粘好的胡萝卜物品。 */
    public BarelyGluedCarrotItem(Properties properties) {
        super(properties);
    }

    @Override
    public @NotNull ItemStack finishUsingItem(@NotNull ItemStack stack, @NotNull Level level,
                                              @NotNull LivingEntity entity) {
        ItemStack result = super.finishUsingItem(stack, level, entity);
        if (!level.isClientSide()) {
            // 延续原版蜂蜜瓶特性，在服务端移除中毒效果
            entity.removeEffect(MobEffects.POISON);
        }
        return result;
    }

    @Override
    public void appendHoverText(@NotNull ItemStack stack, @NotNull TooltipContext context,
                                @NotNull List<Component> tooltipLines, @NotNull TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltipLines, flag);
        TooltipBuilder tooltip = new TooltipBuilder(tooltipLines);
        tooltip.intro(DESC_KEY);
        tooltip.bullet(CURE_KEY, TooltipBuilder.POSITIVE);
        tooltip.blank();
        tooltip.foodEffects(stack, context);
    }
}
