package com.meteorite.unsuspiciousblock.journal.state;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;

import java.util.function.Supplier;

/**
 * 日志条目的战利品来源类型。
 * <p>
 * 描述战利品是通过何种途径获得的，取代旧的 TriggerType 枚举。
 * 序列化时使用 {@link #serializedName}，旧格式自动兼容：
 * "brush" → ARCHAEOLOGY, "container" → LOOT_CONTAINER, "unknown" → null。
 */
public enum LootSourceType {
    ARCHAEOLOGY("archaeology", "screen.unsuspiciousblock.archaeology_journal.loot_source_type.archaeology",
            () -> new ItemStack(Items.BRUSH)),
    READER("reader", "screen.unsuspiciousblock.archaeology_journal.loot_source_type.reader",
            () -> new ItemStack(Items.BRUSH)),
    SPADE("spade", "screen.unsuspiciousblock.archaeology_journal.loot_source_type.spade",
            () -> new ItemStack(Items.BRUSH)),
    LOOT_CONTAINER("loot_container", "screen.unsuspiciousblock.archaeology_journal.loot_source_type.loot_container",
            () -> new ItemStack(Items.CHEST));

    private final String serializedName;
    private final String translationKey;
    private final Supplier<ItemStack> iconItem;

    LootSourceType(String serializedName, String translationKey, Supplier<ItemStack> iconItem) {
        this.serializedName = serializedName;
        this.translationKey = translationKey;
        this.iconItem = iconItem;
    }

    // 返回枚举的序列化名称
    public String serializedName() {
        return this.serializedName;
    }

    // 返回本地化显示名
    public Component displayName() {
        return Component.translatable(this.translationKey);
    }

    // 返回图标物品栈
    public ItemStack iconItem() {
        return this.iconItem.get();
    }

    // 根据序列化名称反序列化枚举值
    // 兼容旧格式：brush→ARCHAEOLOGY, container→LOOT_CONTAINER, unknown→null
    @Nullable
    public static LootSourceType fromSerializedName(String name) {
        // 向后兼容旧序列化名
        switch (name) {
            case "brush":
                return ARCHAEOLOGY;
            case "container":
                return LOOT_CONTAINER;
            case "unknown":
                return null;
            default:
                break;
        }
        for (LootSourceType value : values()) {
            if (value.serializedName.equals(name)) {
                return value;
            }
        }
        return null;
    }
}