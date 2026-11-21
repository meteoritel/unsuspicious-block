package com.meteorite.unsuspiciousblock.core.loottable.catalog;

import com.meteorite.unsuspiciousblock.core.loottable.catalog.LootTableCatalog.ItemDefinition;

import java.util.List;

/**
 * 单张战利品表展开引用后的原生静态候选，供目录构建时合并声明注入。
 *
 * @param items       展平后的物品条目，按物品 id 与签名稳定排序
 */
public record StaticTableProjection(String declaredType, List<ItemDefinition> items) {
    public StaticTableProjection {
        items = List.copyOf(items);
    }
}
