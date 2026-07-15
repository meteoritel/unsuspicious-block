package com.meteorite.unsuspiciousblock.loot;

import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.loottable.condition.MudDredgingCondition;
import net.fabricmc.fabric.api.loot.v3.LootTableEvents;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.storage.loot.LootPool;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.entries.NestedLootTable;
import net.minecraft.world.level.storage.loot.providers.number.ConstantValue;

/**
 * Fabric 平台泥地打捞钓鱼战利品注入——通过 {@link LootTableEvents#MODIFY} 向原版钓鱼表
 * 追加两个 pool（基础 / 沼泽），由 {@link MudDredgingCondition} 在运行时检查附魔等级、群系与概率。
 * <p>
 * NeoForge 端通过 FishingLootModifier（GLM）实现等价语义。
 */
public final class FishingLootInjection {

    // 原版钓鱼战利品表 id
    public static final ResourceLocation FISHING_ID =
            ResourceLocation.parse("minecraft:gameplay/fishing");

    // 泥地打捞基础战利品表
    public static final ResourceKey<LootTable> MUD_DREDGING =
            ResourceKey.create(Registries.LOOT_TABLE,
                    ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "gameplay/fishing/mud_dredging"));

    // 泥地打捞沼泽战利品表（更丰厚）
    public static final ResourceKey<LootTable> MUD_DREDGING_SWAMP =
            ResourceKey.create(Registries.LOOT_TABLE,
                    ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "gameplay/fishing/mud_dredging_swamp"));

    private FishingLootInjection() {
    }

    public static void register() {
        LootTableEvents.MODIFY.register((lootTableId, tableBuilder, source, registries) -> {
            // 仅修改原版内置表，避免误伤数据包自定义表
            if (!source.isBuiltin()) {
                return;
            }
            if (!lootTableId.location().equals(FISHING_ID)) {
                return;
            }
            // 追加基础池：非沼泽群系时按等级概率触发
            LootPool basicPool = LootPool.lootPool()
                    .setRolls(ConstantValue.exactly(1.0f))
                    .add(NestedLootTable.lootTableReference(MUD_DREDGING))
                    .when(new MudDredgingCondition(false))
                    .build();
            tableBuilder.pool(basicPool);

            // 追加沼泽池：仅沼泽群系时按等级+15% 概率触发
            LootPool swampPool = LootPool.lootPool()
                    .setRolls(ConstantValue.exactly(1.0f))
                    .add(NestedLootTable.lootTableReference(MUD_DREDGING_SWAMP))
                    .when(new MudDredgingCondition(true))
                    .build();
            tableBuilder.pool(swampPool);
        });
    }
}