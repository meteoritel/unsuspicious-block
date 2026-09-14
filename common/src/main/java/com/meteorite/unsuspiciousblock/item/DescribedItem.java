package com.meteorite.unsuspiciousblock.item;

import com.meteorite.unsuspiciousblock.client.tooltip.TooltipBuilder;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * 携带一行简介文本的简单物品——用于古代金币、失落书页、基页等
 * 无交互逻辑、只需一句说明的素材类物品。
 */
public class DescribedItem extends Item {

    // 简介文本的本地化键（item.unsuspiciousblock.<name>.tooltip.desc）
    private final String descriptionKey;

    public DescribedItem(Properties properties, String descriptionKey) {
        super(properties);
        this.descriptionKey = descriptionKey;
    }

    @Override
    public void appendHoverText(@NotNull ItemStack stack, @NotNull TooltipContext context,
                                @NotNull List<Component> tooltipLines, @NotNull TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltipLines, flag);
        new TooltipBuilder(tooltipLines).intro(descriptionKey);
    }
}
