package com.meteorite.unsuspiciousblock.core.item;

import com.meteorite.unsuspiciousblock.core.text.TooltipBuilder;
import net.minecraft.network.chat.Component;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * 半个胡萝卜（上半或下半）——雨天农田彩蛋掉落的断口胡萝卜。
 * 可直接充饥，亦可凑齐上下两半与蜂蜜瓶合成「勉强粘好的胡萝卜」。
 */
public class HalfCarrotItem extends Item {

    private static final int NUTRITION = 2;
    private static final float SATURATION_MODIFIER = 0.45F;
    private static final String HINT_KEY = "item.unsuspiciousblock.half_carrot.tooltip.hint";

    /** 对应原版胡萝卜约一半的营养与饱和度。 */
    public static final FoodProperties FOOD_PROPERTIES = new FoodProperties.Builder()
            .nutrition(NUTRITION)
            .saturationModifier(SATURATION_MODIFIER)
            .build();

    private final String descriptionKey;

    /**
     * 构造半个胡萝卜物品。
     *
     * @param properties     物品属性（由调用方挂载 {@link #FOOD_PROPERTIES}）
     * @param descriptionKey 简介行本地化键
     */
    public HalfCarrotItem(Properties properties, String descriptionKey) {
        super(properties);
        this.descriptionKey = descriptionKey;
    }

    @Override
    public void appendHoverText(@NotNull ItemStack stack, @NotNull TooltipContext context,
                                @NotNull List<Component> tooltipLines, @NotNull TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltipLines, flag);
        TooltipBuilder tooltip = new TooltipBuilder(tooltipLines);
        tooltip.intro(descriptionKey);
        Component honeyBottle = Items.HONEY_BOTTLE.getDescription().copy().withStyle(TooltipBuilder.ACCENT);
        tooltip.hint(HINT_KEY, honeyBottle);
    }
}
