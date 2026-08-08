package com.meteorite.unsuspiciousblock.block;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * 不可疑方块封存信息的公共展示格式，供物品提示与 Jade 联动共同使用。
 */
public final class SealedContentsDisplay {
    private SealedContentsDisplay() {
    }

    // 构建“封存物品”标签
    public static MutableComponent sealedItemPrefix() {
        return Component.translatable("jade.unsuspiciousblock.sealed_item")
                .withStyle(ChatFormatting.GRAY);
    }

    // 构建封存物品值，包含物品名称与数量
    public static MutableComponent sealedItemValue(ItemStack sealedItem) {
        if (sealedItem.isEmpty()) {
            return Component.translatable("jade.unsuspiciousblock.empty")
                    .withStyle(ChatFormatting.DARK_GRAY);
        }
        return sealedItem.getHoverName().copy()
                .withStyle(ChatFormatting.YELLOW)
                .append(Component.literal(" ×" + sealedItem.getCount())
                        .withStyle(ChatFormatting.WHITE));
    }

    // 构建不含物品图标的完整封存物品行
    public static MutableComponent sealedItemLine(ItemStack sealedItem) {
        return sealedItemPrefix().append(sealedItemValue(sealedItem));
    }

    // 从方块物品保存的身份数据构建“封存玩家”行
    public static MutableComponent sealedByIdentityLine(@Nullable SealedContents.CrafterIdentity crafter) {
        String crafterName = crafter == null ? null : displayCrafterName(crafter);
        return sealedByNameLine(crafterName);
    }

    // 从服务端已解析的玩家名构建“封存玩家”行
    public static MutableComponent sealedByNameLine(@Nullable String crafterName) {
        Component displayName = crafterName == null || crafterName.isBlank()
                ? Component.translatable("jade.unsuspiciousblock.unknown_player")
                : Component.literal(crafterName);
        return Component.translatable("jade.unsuspiciousblock.sealed_by", displayName)
                .withStyle(ChatFormatting.AQUA);
    }

    // 玩家名缺失时使用 UUID，确保已有身份数据始终可识别
    public static String displayCrafterName(SealedContents.CrafterIdentity identity) {
        return identity.name().isBlank() ? identity.uuid().toString() : identity.name();
    }
}
