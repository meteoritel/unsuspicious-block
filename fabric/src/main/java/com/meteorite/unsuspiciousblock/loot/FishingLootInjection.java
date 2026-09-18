package com.meteorite.unsuspiciousblock.loot;

import com.meteorite.unsuspiciousblock.enchantment.ModEnchantments;
import com.meteorite.unsuspiciousblock.loottable.condition.ToolEnchantmentCondition;
import com.meteorite.unsuspiciousblock.loottable.graph.RuntimeLootLinks;
import net.fabricmc.fabric.api.loot.v3.LootTableEvents;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.level.storage.loot.LootPool;
import net.minecraft.world.level.storage.loot.entries.NestedLootTable;
import net.minecraft.world.level.storage.loot.providers.number.ConstantValue;

import java.util.Optional;

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
            // 注入只负责资格门槛，概率与群系由子表自己的数据表达；
            // 附魔 Holder 由回调提供的注册表查询取得，不需要另设资源 id 常量。
            Holder<Enchantment> mudDredging = registries.lookupOrThrow(Registries.ENCHANTMENT)
                    .getOrThrow(ModEnchantments.MUD_DREDGING);
            LootPool pool = LootPool.lootPool()
                    .setRolls(ConstantValue.exactly(1.0f))
                    .add(NestedLootTable.lootTableReference(RuntimeLootLinks.MUD_DREDGING_TABLE_KEY))
                    .when(new ToolEnchantmentCondition(mudDredging,
                            ToolEnchantmentCondition.DEFAULT_MIN_LEVEL, Optional.empty()))
                    .build();
            tableBuilder.pool(pool);
        });
    }
}
