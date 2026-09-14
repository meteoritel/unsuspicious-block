package com.meteorite.unsuspiciousblock.block;

import com.meteorite.unsuspiciousblock.client.tooltip.TooltipBuilder;
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
        return Component.translatable("unsuspiciousblock.sealed.item")
                .withStyle(TooltipBuilder.LABEL);
    }

    // 构建封存物品值，包含物品名称与数量
    public static MutableComponent sealedItemValue(ItemStack sealedItem) {
        if (sealedItem.isEmpty()) {
            return Component.translatable("unsuspiciousblock.sealed.empty")
                    .withStyle(TooltipBuilder.HINT);
        }
        return sealedItem.getHoverName().copy()
                .withStyle(TooltipBuilder.NAME)
                .append(Component.literal(" ×" + sealedItem.getCount())
                        .withStyle(TooltipBuilder.BODY));
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
                ? Component.translatable("unsuspiciousblock.sealed.unknown_player")
                : Component.literal(crafterName);
        return Component.translatable("unsuspiciousblock.sealed.by", displayName)
                .withStyle(TooltipBuilder.ACCENT);
    }

    // 玩家名缺失时使用 UUID，确保已有身份数据始终可识别
    public static String displayCrafterName(SealedContents.CrafterIdentity identity) {
        return identity.name().isBlank() ? identity.uuid().toString() : identity.name();
    }
}
