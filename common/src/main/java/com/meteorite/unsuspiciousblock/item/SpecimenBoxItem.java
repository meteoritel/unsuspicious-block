package com.meteorite.unsuspiciousblock.item;

import com.meteorite.unsuspiciousblock.specimen.SpecimenBoxMenu;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * 标本箱物品——右键打开标本箱菜单。
 * 服务端创建并打开 SpecimenBoxMenu（含隐藏后端容器）
 */
public class SpecimenBoxItem extends Item {
    public SpecimenBoxItem(Properties properties) {
        super(properties);
    }

    @Override
    public void appendHoverText(@NotNull ItemStack stack, @NotNull TooltipContext context,
                                @NotNull List<Component> tooltipLines, @NotNull TooltipFlag flag) {
        // WIP 标识：物品仍在开发中
        tooltipLines.add(Component.translatable("tooltip.unsuspiciousblock.wip")
                .withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
        super.appendHoverText(stack, context, tooltipLines, flag);
    }

    @Override
    public @NotNull InteractionResultHolder<ItemStack> use(@NotNull Level level, @NotNull Player player, @NotNull InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        // 服务端创建并打开菜单；客户端不开放菜单直接返回成功
        if (player instanceof ServerPlayer serverPlayer) {
            serverPlayer.openMenu(new SimpleMenuProvider(
                    (containerId, inventory, menuPlayer) -> new SpecimenBoxMenu(containerId, inventory, hand),
                    Component.translatable("screen.unsuspiciousblock.specimen_box.title")));
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
    }
}
