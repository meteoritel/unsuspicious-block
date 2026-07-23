package com.meteorite.unsuspiciousblock.loot;

import com.meteorite.unsuspiciousblock.item.ModItems;
import net.fabricmc.fabric.api.loot.v3.LootTableEvents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.storage.loot.LootPool;
import net.minecraft.world.level.storage.loot.entries.LootItem;
import net.minecraft.world.level.storage.loot.predicates.LootItemRandomChanceCondition;
import net.minecraft.world.level.storage.loot.providers.number.ConstantValue;

/**
 * Fabric 平台村庄铁匠铺战利品注入——向原版武器匠箱子追加标本箱。
 */
public final class VillageWeaponsmithLootInjection {

    // 原版村庄武器匠战利品表
    public static final ResourceLocation VILLAGE_WEAPONSMITH_ID =
            ResourceLocation.parse("minecraft:chests/village/village_weaponsmith");

    private VillageWeaponsmithLootInjection() {
    }

    public static void register() {
        LootTableEvents.MODIFY.register((lootTableId, tableBuilder, source, registries) -> {
            // 仅修改原版内置表，避免误伤数据包自定义表
            if (!source.isBuiltin() || !lootTableId.location().equals(VILLAGE_WEAPONSMITH_ID)) {
                return;
            }

            // 独立池以 30% 概率生成 1 个标本箱，保留原版战利品
            LootPool pool = LootPool.lootPool()
                    .setRolls(ConstantValue.exactly(1.0f))
                    .add(LootItem.lootTableItem(ModItems.SPECIMEN_BOX))
                    .when(LootItemRandomChanceCondition.randomChance(0.30f))
                    .build();
            tableBuilder.pool(pool);
        });
    }
}
