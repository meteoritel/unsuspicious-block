package com.meteorite.unsuspiciousblock.client.ui.entry;

import net.minecraft.resources.ResourceLocation;

public interface ItemEntryLike {
    // 物品条目最小接口，用于解耦
    ResourceLocation itemId();
    String itemDisplayName();
    boolean unlocked();
    String probability();
}
