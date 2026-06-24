package com.meteorite.unsuspiciousblock.loot;

import com.meteorite.unsuspiciousblock.item.ModItems;
import com.meteorite.unsuspiciousblock.loottable.LootInjection;
import net.fabricmc.fabric.api.loot.v3.LootTableEvents;
import net.fabricmc.fabric.api.loot.v3.LootTableSource;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.storage.loot.LootPool;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.entries.LootItem;
import net.minecraft.world.level.storage.loot.predicates.LootItemRandomChanceCondition;
import net.minecraft.world.level.storage.loot.providers.number.ConstantValue;

/**
 * Fabric 平台战利品表注入——通过 LootTableEvents.MODIFY 向原版古迹废墟战利品表追加条目。
 * <p>
 * 仅修改 builtin（原版数据包）来源的战利品表，避免覆盖玩家自定义数据包。
 * 注入概率与目标表 id 与 NeoForge 端保持一致，定义见 {@link LootInjection}。
 */
public final class FabricLootTableInjection {

    private FabricLootTableInjection() {
    }

    // 注册 LootTableEvents.MODIFY 监听器，匹配目标表后追加单条目池
    public static void register() {
        LootTableEvents.MODIFY.register(FabricLootTableInjection::onModifyLootTable);
    }

    private static void onModifyLootTable(ResourceKey<LootTable> key, LootTable.Builder tableBuilder,
            LootTableSource source, HolderLookup.Provider registries) {
        if (!source.isBuiltin()) {
            return;
        }
        if (key.location().equals(LootInjection.TRAIL_RUINS_COMMON_ID)) {
            tableBuilder.pool(buildSingleItemPool(ModItems.ANCIENT_COIN, LootInjection.ANCIENT_COIN_CHANCE));
        } else if (key.location().equals(LootInjection.TRAIL_RUINS_RARE_ID)) {
            tableBuilder.pool(buildSingleItemPool(ModItems.LOST_PAGE, LootInjection.LOST_PAGE_CHANCE));
        }
    }

    // 构造一个固定 1 roll、按概率触发的单条目池
    private static LootPool buildSingleItemPool(Item item, float chance) {
        return LootPool.lootPool()
                .setRolls(ConstantValue.exactly(1))
                .add(LootItem.lootTableItem(item))
                .when(LootItemRandomChanceCondition.randomChance(chance))
                .build();
    }
}
