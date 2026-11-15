package com.meteorite.unsuspiciousblock.core.loottable.injection;

import com.meteorite.unsuspiciousblock.core.item.ModItems;
import net.minecraft.world.level.storage.loot.LootPool;
import net.minecraft.world.level.storage.loot.entries.LootItem;
import net.minecraft.world.level.storage.loot.predicates.LootItemRandomChanceCondition;
import net.minecraft.world.level.storage.loot.providers.number.ConstantValue;

/** 嗅探兽挖掘追加池的共享规则，保留原版种子的抽取池。 */
public final class SnifferDiggingLoot {
    public static final float SEED_CHANCE = 0.5F;

    private SnifferDiggingLoot() {
    }

    /** 创建独立追加池，每次挖掘以原版单种种子的概率额外产生一个咕噜荆芥种子。 */
    public static LootPool.Builder createSeedPool() {
        return LootPool.lootPool()
                .setRolls(ConstantValue.exactly(1.0F))
                .add(LootItem.lootTableItem(ModItems.PURRMINT_SEEDS))
                .when(LootItemRandomChanceCondition.randomChance(SEED_CHANCE));
    }
}
