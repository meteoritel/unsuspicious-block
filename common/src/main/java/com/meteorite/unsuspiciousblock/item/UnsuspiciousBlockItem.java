package com.meteorite.unsuspiciousblock.item;

import com.meteorite.unsuspiciousblock.block.SealedContents;
import com.meteorite.unsuspiciousblock.block.SealedContentsDisplay;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * 不可疑方块的物品形态，负责填充提示与首次进入玩家物品栏时的身份绑定。
 */
public class UnsuspiciousBlockItem extends BlockItem {
    public UnsuspiciousBlockItem(Block block, Properties properties) {
        super(block, properties);
    }

    @Override
    public void inventoryTick(@NotNull ItemStack stack, @NotNull Level level, @NotNull Entity entity,
                              int slotId, boolean isSelected) {
        if (!level.isClientSide()
                && entity instanceof Player player
                && SealedContents.isSealed(stack)
                && SealedContents.getCrafter(stack).isEmpty()) {
            SealedContents.recordCrafter(stack, player);
        }
        super.inventoryTick(stack, level, entity, slotId, isSelected);
    }

    @Override
    public void appendHoverText(@NotNull ItemStack stack, @NotNull TooltipContext context,
                                @NotNull List<Component> tooltipLines, @NotNull TooltipFlag flag) {
        ItemStack sealedItem = SealedContents.getSealedItem(stack);
        if (sealedItem.isEmpty()) {
            tooltipLines.add(Component.translatable(
                            "item.unsuspiciousblock.unsuspicious_block.tooltip_fill")
                    .withStyle(ChatFormatting.GRAY));
        } else {
            tooltipLines.add(SealedContentsDisplay.sealedItemLine(sealedItem));
            tooltipLines.add(SealedContentsDisplay.sealedByIdentityLine(
                    SealedContents.getCrafter(stack).orElse(null)));
        }
        super.appendHoverText(stack, context, tooltipLines, flag);
    }
}
