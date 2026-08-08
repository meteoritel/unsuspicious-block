package com.meteorite.unsuspiciousblock.block;

import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemContainerContents;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 不可疑方块物品的封存内容与合成者信息读写工具。
 */
public final class SealedContents {
    private static final String TAG_CRAFTER_UUID = "UnsuspiciousCrafterUuid";
    private static final String TAG_CRAFTER_NAME = "UnsuspiciousCrafterName";

    private SealedContents() {
    }

    /** 封存者身份快照，UUID 用于稳定识别，名称用于离线显示。 */
    public record CrafterIdentity(UUID uuid, String name) {
    }

    // 将单个物品及其全部 Data Components 写入方块物品
    public static void seal(ItemStack carrier, ItemStack sealedItem) {
        ItemStack singleItem = sealedItem.copyWithCount(1);
        carrier.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(singleItem)));
    }

    // 读取封存物品的独立副本
    public static ItemStack getSealedItem(ItemStack carrier) {
        NonNullList<ItemStack> contents = NonNullList.withSize(1, ItemStack.EMPTY);
        carrier.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY).copyInto(contents);
        return contents.getFirst().copy();
    }

    // 判断方块物品是否已经完成过填充
    public static boolean isSealed(ItemStack carrier) {
        return !getSealedItem(carrier).isEmpty();
    }

    // 在玩家取得合成结果时写入身份；没有玩家上下文时不调用此方法
    public static void recordCrafter(ItemStack carrier, Player player) {
        CustomData.update(DataComponents.CUSTOM_DATA, carrier, tag -> {
            tag.putUUID(TAG_CRAFTER_UUID, player.getUUID());
            tag.putString(TAG_CRAFTER_NAME, player.getGameProfile().getName());
        });
    }

    // 读取封存者；缺少 UUID 时由调用方显示为未知
    public static Optional<CrafterIdentity> getCrafter(ItemStack carrier) {
        CompoundTag tag = carrier.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        if (!tag.hasUUID(TAG_CRAFTER_UUID)) {
            return Optional.empty();
        }
        String name = tag.getString(TAG_CRAFTER_NAME);
        return Optional.of(new CrafterIdentity(tag.getUUID(TAG_CRAFTER_UUID), name));
    }
}
