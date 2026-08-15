package com.meteorite.unsuspiciousblock.loot;

import com.meteorite.unsuspiciousblock.loottable.condition.MudDredgingCondition;
import net.fabricmc.fabric.api.loot.v3.LootTableEvents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.storage.loot.LootPool;
import net.minecraft.world.level.storage.loot.entries.NestedLootTable;
import net.minecraft.world.level.storage.loot.providers.number.ConstantValue;

/**
 * Fabric 平台泥地打捞钓鱼战利品注入——通过 {@link LootTableEvents#MODIFY} 向原版钓鱼表
 * 追加一个父表 pool，注入层只检查泥地打捞附魔资格，群系与概率由数据表处理。
 * <p>
 * NeoForge 端通过 FishingLootModifier（GLM）实现等价语义。
 */
public final class FishingLootInjection {

    // 原版钓鱼战利品表 id
    public static final ResourceLocation FISHING_ID =
            ResourceLocation.parse("minecraft:gameplay/fishing");

    private FishingLootInjection() {
    }

    public static void register() {
        LootTableEvents.MODIFY.register((lootTableId, tableBuilder, source, registries) -> {
            if (!lootTableId.location().equals(FISHING_ID)) {
                return;
            }
            LootPool pool = LootPool.lootPool()
                    .setRolls(ConstantValue.exactly(1.0f))
                    .add(NestedLootTable.lootTableReference(MudDredgingCondition.MUD_DREDGING))
                    .when(new MudDredgingCondition(false))
                    .build();
            tableBuilder.pool(pool);
        });
    }
}
