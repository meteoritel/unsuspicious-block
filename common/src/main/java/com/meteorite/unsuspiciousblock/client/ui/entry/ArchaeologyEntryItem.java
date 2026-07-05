package com.meteorite.unsuspiciousblock.client.ui.entry;

import com.meteorite.unsuspiciousblock.client.ui.support.JournalSearchQuery;
import com.meteorite.unsuspiciousblock.loottable.LootResultSignature;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/**
 * 考古条目中的单个物品视图模型。
 * 合并物品目录定义与玩家进度数据，用于 UI 展示。
 * {@code sourceChildTable} 标识物品来自哪个嵌套子表（null=根表直接产出），用于 tooltip 展示与排序。
 */
public record ArchaeologyEntryItem(
        ResourceLocation id,
        Component displayName,
        @Nullable Component tooltipHint,
        String probability,
        boolean unlocked,
        int count,
        LootResultSignature signature,
        @Nullable ResourceLocation sourceChildTable
) implements ItemEntryLike {

    // 兼容旧调用方的便利构造器：sourceChildTable 默认 null
    public ArchaeologyEntryItem(ResourceLocation id, Component displayName, @Nullable Component tooltipHint,
                                String probability, boolean unlocked, int count, LootResultSignature signature) {
        this(id, displayName, tooltipHint, probability, unlocked, count, signature, null);
    }

    @Override
    public ResourceLocation itemId() {
        return id;
    }

    @Override
    public String itemDisplayName() {
        return displayName.getString();
    }
}
