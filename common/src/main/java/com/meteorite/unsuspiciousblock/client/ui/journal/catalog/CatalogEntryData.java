package com.meteorite.unsuspiciousblock.client.ui.journal.catalog;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * 目录树行的展示投影，父路径与分类归属保持不可变。
 */
public record CatalogEntryData(ResourceLocation id, Component displayName, boolean unlocked, boolean favorite,
                               int depth, boolean child, boolean hasChildren, boolean expanded,
                               List<ResourceLocation> parentPath, List<ResourceLocation> categoryIds) {
    public CatalogEntryData {
        parentPath = List.copyOf(parentPath);
        categoryIds = List.copyOf(categoryIds);
    }
}
