package com.meteorite.unsuspiciousblock.core.blockentity;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;


/** 刷拭与封存物的物品访问能力，扫描流程由宿主契约提供。 */
public interface BrushableBlockEntityScanState extends ScanStateHost {
    @Nullable
    ResourceLocation unsuspiciousblock$getLootTableName();

    // 获取可疑方块内的物品
    ItemStack unsuspiciousblock$getItem();

    // 设置可疑方块内的物品
    void unsuspiciousblock$setItem(ItemStack stack);

    // 解析战利品表并返回生成的物品副本，由各平台实现覆盖
    default ItemStack unsuspiciousblock$resolveAndGetLoot(Player player) {
        return ItemStack.EMPTY;
    }

}
