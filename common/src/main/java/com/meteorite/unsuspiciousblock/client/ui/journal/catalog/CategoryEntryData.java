package com.meteorite.unsuspiciousblock.client.ui.journal.catalog;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * 目录分类的展示投影，只携带业务身份、文案、图标与解锁状态。
 */
public record CategoryEntryData(ResourceLocation id, Component name, Component description,
                                ItemStack icon, int unlocked, int total) {
}
