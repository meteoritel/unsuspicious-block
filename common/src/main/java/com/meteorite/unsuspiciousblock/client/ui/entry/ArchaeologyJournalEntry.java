package com.meteorite.unsuspiciousblock.client.ui.entry;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/** 目录行排序与导航需要的表级信息。 */
public record ArchaeologyJournalEntry(ResourceLocation id, Component displayName, String type,
                                      int itemCount, boolean unlocked, boolean favorite) {
}
