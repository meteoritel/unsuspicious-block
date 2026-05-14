package com.meteorite.unsuspiciousblock.item;

import com.meteorite.unsuspiciousblock.menu.SpecimenBoxMenu;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.NotNull;

/** 标本箱物品——右键打开服务端菜单 */
public class SpecimenBoxItem extends Item {
    public SpecimenBoxItem(Properties properties) {
        super(properties);
    }

    @Override
    public @NotNull InteractionResultHolder<ItemStack> use(@NotNull Level level, @NotNull Player player, @NotNull InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (player instanceof ServerPlayer serverPlayer) {
            serverPlayer.openMenu(new SimpleMenuProvider(
                    (containerId, inventory, menuPlayer) -> new SpecimenBoxMenu(containerId, inventory, hand),
                    Component.translatable("screen.unsuspiciousblock.specimen_box.title")));
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
    }
}
