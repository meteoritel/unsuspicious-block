package com.meteorite.unsuspiciousblock.platform;

import com.meteorite.unsuspiciousblock.item.ModItems;
import com.meteorite.unsuspiciousblock.platform.services.IAccessoryHelper;
import dev.emi.trinkets.api.TrinketsApi;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.world.entity.player.Player;

/**
 * Fabric 端饰品栏查询实现——基于 Trinkets API。
 * Trinkets 为可选联动：未安装时 isJournalEquipped 提前返回 false，
 * 不触发 TrinketsApi 类加载，避免 NoClassDefFoundError。
 * （JVM 对方法体内的符号引用采用懒解析，运行时守卫足够安全。）
 */
public final class FabricAccessoryHelper implements IAccessoryHelper {

    @Override
    public boolean isJournalEquipped(Player player) {
        // Trinkets 未安装时直接返回 false，不引用 TrinketsApi 静态成员
        if (!FabricLoader.getInstance().isModLoaded("trinkets")) {
            return false;
        }
        return TrinketsApi.getTrinketComponent(player)
                .map(component -> component.isEquipped(ModItems.ARCHAEOLOGY_JOURNAL))
                .orElse(false);
    }
}
