package com.meteorite.unsuspiciousblock.item;

import com.meteorite.unsuspiciousblock.block.ModBlocks;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.block.entity.PotDecorations;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/** 携带四面纹饰数据的未烧制纹饰陶罐物品。 */
public class UnfiredDecoratedPotItem extends BlockItem {
    public UnfiredDecoratedPotItem(Properties properties) {
        super(ModBlocks.UNFIRED_DECORATED_POT.get(), properties);
    }

    @Override
    public void appendHoverText(@NotNull ItemStack stack, @NotNull TooltipContext context,
                                @NotNull List<Component> tooltip, @NotNull TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);
        PotDecorations decorations = stack.get(DataComponents.POT_DECORATIONS);
        if (decorations == null || decorations.equals(PotDecorations.EMPTY)) {
            return;
        }

        tooltip.add(CommonComponents.EMPTY);
        for (Item pattern : decorations.ordered()) {
            tooltip.add(new ItemStack(pattern).getHoverName().copy().withStyle(ChatFormatting.GRAY));
        }
    }
}
