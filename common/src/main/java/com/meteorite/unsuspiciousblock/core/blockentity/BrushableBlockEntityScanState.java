package com.meteorite.unsuspiciousblock.core.blockentity;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;


/** 刷拭与封存物的物品访问能力，扫描流程由宿主契约提供。 */
public interface BrushableBlockEntityScanState extends ScanStateHost {
    @Nullable
    ResourceLocation unsuspiciousblock$getLootTableName();

    // 获取可疑方块内的物品
    ItemStack unsuspiciousblock$getItem();

    /** 在服务端取得并清空同一份已授权物品，物品交付后由调用者确认。 */
    Extraction unsuspiciousblock$takeScannedLoot(ServerPlayer player);

    // 解析战利品表并返回生成的物品副本，由各平台实现覆盖
    default ItemStack unsuspiciousblock$resolveAndGetLoot(Player player) {
        return ItemStack.EMPTY;
    }

    /** 解析当前玩家能扫描的物品并记录授权。 */
    default ItemStack unsuspiciousblock$scanFor(ServerPlayer player) {
        ItemStack stack = unsuspiciousblock$resolveAndGetLoot(player);
        unsuspiciousblock$markScanned(player.getUUID());
        return stack;
    }

    /** 提取结果；有库存来源时在物品实际交付后通知库存结算。 */
    record Extraction(ItemStack stack, @Nullable Consumer<ItemStack> onDelivery) {
        public static final Extraction EMPTY = new Extraction(ItemStack.EMPTY, null);

        public void recordDelivery(ItemStack delivered) {
            if (onDelivery != null) onDelivery.accept(delivered);
        }
    }
}
