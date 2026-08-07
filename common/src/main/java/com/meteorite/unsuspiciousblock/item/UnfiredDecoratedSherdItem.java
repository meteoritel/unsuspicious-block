package com.meteorite.unsuspiciousblock.item;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.block.entity.PotDecorations;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/** 未烧制纹饰陶片，使用原版 POT_DECORATIONS 组件保存对应花纹。 */
public class UnfiredDecoratedSherdItem extends Item {
    public UnfiredDecoratedSherdItem(Properties properties) {
        super(properties);
    }

    @Override
    public void appendHoverText(@NotNull ItemStack stack, @NotNull TooltipContext context,
                                @NotNull List<Component> tooltip, @NotNull TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);
        PotDecorations decorations = stack.get(DataComponents.POT_DECORATIONS);
        if (decorations != null && decorations.ordered().stream().findFirst().isPresent()) {
            Item pattern = decorations.ordered().getFirst();
            tooltip.add(Component.translatable("item.unsuspiciousblock.unfired_decorated_sherd.pattern",
                    pattern.getDescription()));
        }
    }
}
