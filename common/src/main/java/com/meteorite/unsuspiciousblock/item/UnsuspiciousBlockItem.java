package com.meteorite.unsuspiciousblock.item;

import com.meteorite.unsuspiciousblock.block.SealedContents;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import org.jetbrains.annotations.NotNull;

/**
 * 不可疑方块的物品形态，在玩家合成时记录封存者身份。
 */
public class UnsuspiciousBlockItem extends BlockItem {
    public UnsuspiciousBlockItem(Block block, Properties properties) {
        super(block, properties);
    }

    @Override
    public void onCraftedBy(@NotNull ItemStack stack, @NotNull Level level, @NotNull Player player) {
        SealedContents.recordCrafter(stack, player);
        super.onCraftedBy(stack, level, player);
    }
}
