package com.meteorite.unsuspiciousblock;

import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModItems {
    public static final DeferredRegister.Items ITEMS =
            DeferredRegister.createItems(UnsuspiciousBlock.MOD_ID);

    public static final DeferredItem<SuspiciousReaderItem> SUSPICIOUS_READER =
            ITEMS.register("suspicious_reader",
                    () -> new SuspiciousReaderItem(new Item.Properties().stacksTo(1)));

    public static final DeferredItem<LuoyangSpadeItem> LUOYANG_SPADE =
            ITEMS.register("luoyang_spade",
                    () -> new LuoyangSpadeItem(new Item.Properties().stacksTo(1)));

    // 将物品注册到原版的工具与实用工具标签页
    public static void addCreativeTabEntries(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            event.accept(SUSPICIOUS_READER);
            event.accept(LUOYANG_SPADE);
        }
    }
}
