package com.meteorite.unsuspiciousblock.loot;

import com.meteorite.unsuspiciousblock.item.ModItems;
import net.fabricmc.fabric.api.loot.v3.LootTableEvents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.storage.loot.LootPool;
import net.minecraft.world.level.storage.loot.entries.LootItem;
import net.minecraft.world.level.storage.loot.providers.number.ConstantValue;

/**
 * Fabric 平台埋藏宝藏战利品注入——通过 LootTableEvents.MODIFY 向原版埋藏宝藏表
 * 追加猫之瞳 pool（100% 出 1 个），保留原版战利品。
 * <p>
 * 【临时方案】该获取方式是暂时的，未来会更改到自定义结构中。
 * <p>
 * NeoForge 端通过 GlobalLootModifier（inject_item 类型）实现等价语义。
 */
public final class BuriedTreasureLootInjection {

    // 原版埋藏宝藏战利品表 id
    public static final ResourceLocation BURIED_TREASURE_ID =
            ResourceLocation.parse("minecraft:chests/buried_treasure");

    private BuriedTreasureLootInjection() {
    }

    // 【临时方案】该获取方式是暂时的，未来会更改到自定义结构中
    public static void register() {
        LootTableEvents.MODIFY.register((lootTableId, tableBuilder, source, registries) -> {
            // 仅修改原版内置表，避免误伤数据包自定义表
            if (!source.isBuiltin()) {
                return;
            }
            if (!lootTableId.location().equals(BURIED_TREASURE_ID)) {
                return;
            }
            // 追加一个必出 pool：100% 出 1 个猫之瞳
            LootPool pool = LootPool.lootPool()
                    .setRolls(ConstantValue.exactly(1.0f))
                    .add(LootItem.lootTableItem(ModItems.EYE_OF_CAT))
                    .build();
            tableBuilder.pool(pool);
        });
    }
}
