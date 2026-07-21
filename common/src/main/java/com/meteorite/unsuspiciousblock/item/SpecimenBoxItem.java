package com.meteorite.unsuspiciousblock.item;

import com.meteorite.unsuspiciousblock.inventory.PortableContainer;
import com.meteorite.unsuspiciousblock.specimen.SpecimenBoxMenu;
import com.meteorite.unsuspiciousblock.specimen.SpecimenBoxTooltip;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;
import java.util.stream.Stream;

/**
 * 标本箱物品--便携容器，放在背包或饰品栏中时内部物品的"背包生效"功能正常触发。
 * 右键打开 5 格容器 GUI。实现 PortableContainer 供 InventoryPresenceRegistry 递归扫描。
 */
public class SpecimenBoxItem extends Item implements PortableContainer {
    public SpecimenBoxItem(Properties properties) {
        super(properties);
    }

    @Override
    public Stream<ItemStack> getContents(ItemStack container) {
        // 返回盒内非空物品流，供 InventoryPresenceRegistry 递归扫描
        return container.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY)
                .nonEmptyStream();
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

    @Override
    public @NotNull Optional<TooltipComponent> getTooltipImage(ItemStack stack) {
        ItemContainerContents contents = stack.getOrDefault(
                DataComponents.CONTAINER, ItemContainerContents.EMPTY);
        NonNullList<ItemStack> items = NonNullList.withSize(
                SpecimenBoxMenu.CONTAINER_SIZE, ItemStack.EMPTY);
        contents.copyInto(items);
        return Optional.of(new SpecimenBoxTooltip(items));
    }
}
