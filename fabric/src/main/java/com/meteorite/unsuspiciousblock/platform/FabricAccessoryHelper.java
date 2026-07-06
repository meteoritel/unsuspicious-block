package com.meteorite.unsuspiciousblock.platform;

import com.meteorite.unsuspiciousblock.item.ModItems;
import com.meteorite.unsuspiciousblock.platform.services.IAccessoryHelper;
import dev.emi.trinkets.api.TrinketsApi;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;

/**
 * Fabric 端饰品栏查询实现——基于 Trinkets API。
 * Trinkets 为可选联动：未安装时所有查询提前返回 false，
 * 不触发 TrinketsApi 类加载，避免 NoClassDefFoundError。
 * （JVM 对方法体内的符号引用采用懒解析，运行时守卫足够安全。）
 */
public final class FabricAccessoryHelper implements IAccessoryHelper {

    @Override
    public boolean isJournalEquipped(Player player) {
        // 委托通用查询，保持向后兼容
        return isPresent(player, ModItems.ARCHAEOLOGY_JOURNAL);
    }

    @Override
    public boolean isPresent(Player player, Item item) {
        if (item == null) {
            return false;
        }
        // Trinkets 未安装时直接返回 false，不引用 TrinketsApi 静态成员
        if (!FabricLoader.getInstance().isModLoaded("trinkets")) {
            return false;
        }
        return TrinketsApi.getTrinketComponent(player)
                .map(component -> component.isEquipped(item))
                .orElse(false);
    }
}
