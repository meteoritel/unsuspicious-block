package com.meteorite.unsuspiciousblock.plugin.trinket;

import com.meteorite.unsuspiciousblock.item.ModItems;
import com.meteorite.unsuspiciousblock.platform.services.IAccessoryHelper;
import dev.emi.trinkets.api.TrinketsApi;
import net.minecraft.util.Tuple;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.stream.Stream;

/**
 * 仅在 Trinkets 已安装时加载的 Fabric 饰品栏查询实现。
 */
public final class TrinketsAccessoryHelper implements IAccessoryHelper {
    public TrinketsAccessoryHelper() {
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
        return TrinketsApi.getTrinketComponent(player)
                .map(component -> component.getAllEquipped().stream().map(Tuple::getB))
                .orElse(Stream.empty());
    }
}
