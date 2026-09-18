package com.meteorite.unsuspiciousblock.loottable.catalog;

import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.ItemDefinition;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

/**
 * 单张表的**静态投影**——把引用图与编译产物链接后，展平得到的"上下文无关的目录读模型"。
 * <p>
 * 与模拟完成态的 {@code SimulatedTable} 严格分工：本类型只承载静态解析结果，
 * 概率字段保持 {@code "?"} 占位，不含模拟期发现的动态条目。不需要概率的查询
 * （命令、成就判定、迁移）直接消费本类型。
 * <p>
 * 不持有编译中间态、不持有图中转、也不兼任网络 DTO。
 *
 * @param items       展平后的物品条目，按物品 id 与签名稳定排序
 * @param childTables 可直接点击进入的直接子表入口；只收录真正产出物品的子表
 */
public record StaticTableProjection(ResourceLocation id, String declaredType,
                                    List<ItemDefinition> items, List<ResourceLocation> childTables) {
    public StaticTableProjection {
        items = List.copyOf(items);
        childTables = List.copyOf(childTables);
    }

    /** 该表是否展平出了任何物品条目。 */
    public boolean isEmpty() {
        return this.items.isEmpty();
    }
}
