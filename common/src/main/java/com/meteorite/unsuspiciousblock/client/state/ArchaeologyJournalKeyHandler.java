package com.meteorite.unsuspiciousblock.client.state;

import com.meteorite.unsuspiciousblock.client.keybind.ModKeyBindings;
import com.meteorite.unsuspiciousblock.client.ui.ArchaeologyJournalUi;
import com.meteorite.unsuspiciousblock.inventory.InventoryPresenceRegistry;
import com.meteorite.unsuspiciousblock.item.ModItems;
import com.meteorite.unsuspiciousblock.platform.Services;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;

/** 客户端状态：处理考古笔记快捷键打开逻辑 */
public final class ArchaeologyJournalKeyHandler {

    private ArchaeologyJournalKeyHandler() {
    }

    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.screen != null) return;

        // 快捷键打开考古笔记：物品栏或饰品栏任一含手册均可触发
        if (ModKeyBindings.JOURNAL_OPEN.consumeClick()) {
            boolean hasJournal = InventoryPresenceRegistry.isPresent(player, ModItems.ARCHAEOLOGY_JOURNAL);
            if (hasJournal) {
                ArchaeologyJournalUi.openFromKeybind();
            }
        }
    }
}
