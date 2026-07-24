package com.meteorite.unsuspiciousblock.plugin.curio;

import com.meteorite.unsuspiciousblock.item.ModItems;
import com.meteorite.unsuspiciousblock.platform.services.IAccessoryHelper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import top.theillusivec4.curios.api.CuriosApi;

import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * 仅在 Curios 已安装时加载的 NeoForge 饰品栏查询实现。
 */
public final class CuriosAccessoryHelper implements IAccessoryHelper {
    public CuriosAccessoryHelper() {
    }

    @Override
    public boolean isJournalEquipped(Player player) {
        return isPresent(player, ModItems.ARCHAEOLOGY_JOURNAL);
    }

    @Override
    public boolean isPresent(Player player, Item item) {
        return item != null && streamEquippedStacks(player).anyMatch(stack -> stack.getItem() == item);
    }

    @Override
    public Stream<ItemStack> streamEquippedStacks(Player player) {
        return CuriosApi.getCuriosInventory(player)
                .map(handler -> {
                    var equipped = handler.getEquippedCurios();
                    return IntStream.range(0, equipped.getSlots())
                            .mapToObj(equipped::getStackInSlot);
                })
                .orElse(Stream.empty());
    }
}
