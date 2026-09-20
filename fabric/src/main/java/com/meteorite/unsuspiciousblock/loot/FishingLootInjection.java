package com.meteorite.unsuspiciousblock.loot;

import com.meteorite.unsuspiciousblock.loottable.graph.RuntimeLootLinks;
import net.fabricmc.fabric.api.loot.v3.LootTableEvents;
import net.minecraft.world.level.storage.loot.LootPool;
import net.minecraft.world.level.storage.loot.entries.NestedLootTable;
import net.minecraft.world.level.storage.loot.providers.number.ConstantValue;

/**
 * Fabric 平台泥地打捞钓鱼战利品注入——通过 {@link LootTableEvents#MODIFY} 向原版钓鱼表
 * 追加一个父表 pool，由该 pool 的条件承载**入口门槛与掉落概率**（附魔等级曲线），
 * 被注入的泥地打捞子表自身不写条件，因此它在自己的页面里按"已进入本表"展示内容分布。
 * <p>
 * 门槛条件从 {@link RuntimeLootLinks#MUD_DREDGING_GATE} 构造，与 NeoForge 端、与父表页
 * 子表入口的"需要什么条件"同源——三处不会各说一套。
 * NeoForge 端通过 FishingLootModifier（GLM）实现等价语义。
 */
public final class FishingLootInjection {

    private FishingLootInjection() {
    }

    public static void register() {
        LootTableEvents.MODIFY.register((lootTableId, tableBuilder, source, registries) -> {
            if (!lootTableId.location().equals(RuntimeLootLinks.FISHING_TABLE)) {
                return;
            }
            // 资格与概率都由父表的 pool 条件表达，子表自己的数据只描述"进来之后产出什么"
            LootPool pool = LootPool.lootPool()
                    .setRolls(ConstantValue.exactly(1.0f))
                    .add(NestedLootTable.lootTableReference(RuntimeLootLinks.MUD_DREDGING_TABLE_KEY))
                    .when(RuntimeLootLinks.MUD_DREDGING_GATE.condition(registries))
                    .build();
            tableBuilder.pool(pool);
        });
    }
}
