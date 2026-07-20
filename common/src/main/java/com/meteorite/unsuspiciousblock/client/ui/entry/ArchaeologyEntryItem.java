package com.meteorite.unsuspiciousblock.client.ui.entry;

import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.LootAcquisitionPath;
import com.meteorite.unsuspiciousblock.loottable.signature.LootResultSignature;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 考古条目中的单个物品视图模型。
 * 合并物品目录定义与玩家进度数据，用于 UI 展示。
 * {@code sourceChildTable} 标识物品来自哪个嵌套子表（null=根表直接产出），用于 tooltip 展示与排序。
 * {@code injected} 标识该物品是否由外部模组注入（GLM / LootTableEvents.MODIFY），非 JSON 定义。
 */
public record ArchaeologyEntryItem(
        ResourceLocation id,
        Component displayName,
        @Nullable Component tooltipHint,
        String probability,
        boolean unlocked,
        int count,
        LootResultSignature signature,
        List<LootAcquisitionPath> acquisitionPaths,
        boolean injected
) implements ItemEntryLike {

    @Override
    public ResourceLocation itemId() {
        return id;
    }

    @Override
    public String itemDisplayName() {
        return displayName.getString();
    }

    @Nullable
    public ResourceLocation primarySourceChildTable() {
        if (this.acquisitionPaths.stream().anyMatch(path -> path.sourceChildTable() == null)) {
            return null;
        }
        return this.acquisitionPaths.stream()
                .map(LootAcquisitionPath::sourceChildTable)
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(null);
    }
}
