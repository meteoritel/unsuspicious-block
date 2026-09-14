package com.meteorite.unsuspiciousblock.item;

import com.meteorite.unsuspiciousblock.client.tooltip.TooltipBuilder;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * 回溯粉——由花火粉与回响碎片合成的序列帧动画物品，
 * 当前仅注册与展示，具体功能后续实现。
 */
public class RewindDustItem extends Item {

    // 简介文本的本地化键（item.unsuspiciousblock.<name>.tooltip.desc）
    private final String descriptionKey;

    public RewindDustItem(Properties properties, String descriptionKey) {
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
