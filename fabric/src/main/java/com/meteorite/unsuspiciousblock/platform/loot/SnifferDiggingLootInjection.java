package com.meteorite.unsuspiciousblock.platform.loot;

import com.meteorite.unsuspiciousblock.core.loottable.injection.SnifferDiggingLoot;
import net.fabricmc.fabric.api.loot.v3.LootTableEvents;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;

/** Fabric 嗅探兽挖掘表接线，向原有表追加咕噜荆芥种子池。 */
public final class SnifferDiggingLootInjection {
    private SnifferDiggingLootInjection() {
    }

    /** 同名数据包表也经过追加，与 NeoForge 的加载事件保持一致。 */
    public static void register() {
        LootTableEvents.MODIFY.register((id, builder, source, registries) -> {
            if (id.equals(BuiltInLootTables.SNIFFER_DIGGING)) {
                builder.withPool(SnifferDiggingLoot.createSeedPool());
            }
        });
    }
}
