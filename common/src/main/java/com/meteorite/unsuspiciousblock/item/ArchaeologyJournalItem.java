package com.meteorite.unsuspiciousblock.item;

import com.meteorite.unsuspiciousblock.text.ClientTooltipBridge;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.NotNull;

public class ArchaeologyJournalItem extends Item {
    public ArchaeologyJournalItem(Properties properties) {
        super(properties);
    }

    @Override
    public @NotNull InteractionResultHolder<ItemStack> use(Level level, @NotNull Player player, @NotNull InteractionHand hand) {
        if (level.isClientSide()) {
            // 经桥接打开客户端界面：服务端可达类不直接加载 client 包
            ClientTooltipBridge.openJournal();
        }
        return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand), level.isClientSide());
    }
}
