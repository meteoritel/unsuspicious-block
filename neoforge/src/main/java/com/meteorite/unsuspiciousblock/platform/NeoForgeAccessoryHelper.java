package com.meteorite.unsuspiciousblock.platform;

import com.meteorite.unsuspiciousblock.item.ModItems;
import com.meteorite.unsuspiciousblock.platform.services.IAccessoryHelper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.neoforged.fml.ModList;
import top.theillusivec4.curios.api.CuriosApi;

/**
 * NeoForge 端饰品栏查询实现——基于 Curios API。
 * Curios 为可选联动：未安装时所有查询提前返回 false，
 * 不触发 CuriosApi 类加载，避免 NoClassDefFoundError。
 * （JVM 对方法体内的符号引用采用懒解析，运行时守卫足够安全。）
 */
public final class NeoForgeAccessoryHelper implements IAccessoryHelper {

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
        // Curios 未安装时直接返回 false，不引用 CuriosApi 静态成员
        if (!ModList.get().isLoaded("curios")) {
            return false;
        }
        return CuriosApi.getCuriosInventory(player)
                .map(handler -> handler.findFirstCurio(item).isPresent())
                .orElse(false);
    }
}
