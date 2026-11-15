package com.meteorite.unsuspiciousblock.platform.loot;

import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.core.loottable.injection.SnifferDiggingLoot;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.LootTableLoadEvent;

/** NeoForge 嗅探兽挖掘表接线，加载时直接追加共享种子池。 */
@EventBusSubscriber(modid = Constants.MOD_ID)
public final class SnifferDiggingLootInjection {
    private SnifferDiggingLootInjection() {
    }

    /** 保留表的原有池、函数和随机序列，只追加种子池。 */
    @SubscribeEvent
    public static void onLootTableLoad(LootTableLoadEvent event) {
        if (event.getName().equals(BuiltInLootTables.SNIFFER_DIGGING.location())) {
            event.getTable().addPool(SnifferDiggingLoot.createSeedPool().build());
        }
    }
}
