package com.meteorite.unsuspiciousblock;

import net.minecraft.world.item.Item;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModItems {
    public static final DeferredRegister.Items ITEMS =
            DeferredRegister.createItems(UnsuspiciousBlock.MOD_ID);

    public static final DeferredItem<SuspiciousReaderItem> SUSPICIOUS_READER =
            ITEMS.register("suspicious_reader",
                    () -> new SuspiciousReaderItem(new Item.Properties().stacksTo(1)));
}
