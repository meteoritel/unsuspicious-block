package com.meteorite.unsuspiciousblock.client.state;

import com.meteorite.unsuspiciousblock.client.keybind.ModKeyBindings;
import com.meteorite.unsuspiciousblock.client.ui.ArchaeologyJournalUi;
import com.meteorite.unsuspiciousblock.client.ui.screen.ArchaeologyJournalScreen;
import com.meteorite.unsuspiciousblock.client.ui.support.ArchaeologyJournalClientState;
import com.meteorite.unsuspiciousblock.inventory.InventoryPresenceRegistry;
import com.meteorite.unsuspiciousblock.item.ModItems;
import com.meteorite.unsuspiciousblock.mixin.client.AbstractContainerScreenAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.inventory.Slot;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

/** 客户端状态：处理考古笔记快捷键打开逻辑 */
public final class ArchaeologyJournalKeyHandler {

    @Nullable
    private static JournalHoveredItemProvider hoveredItemProvider;

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

    // 注册 JEI 等可选物品查看器的悬停物品查询入口。
    public static void registerHoveredItemProvider(JournalHoveredItemProvider provider) {
        hoveredItemProvider = provider;
    }

    // JEI runtime 卸载时清除旧引用。
    public static void clearHoveredItemProvider() {
        hoveredItemProvider = null;
    }

    // GUI 内按下手册快捷键时，以悬停物品注册名执行物品搜索。
    public static boolean handleScreenKey(Screen screen, int keyCode, int scanCode) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || screen instanceof ArchaeologyJournalScreen
                || !ModKeyBindings.JOURNAL_OPEN.matches(keyCode, scanCode)
                || !InventoryPresenceRegistry.isPresent(player, ModItems.ARCHAEOLOGY_JOURNAL)) {
            return false;
        }

        double mouseX = minecraft.mouseHandler.xpos() * minecraft.getWindow().getGuiScaledWidth()
                / minecraft.getWindow().getScreenWidth();
        double mouseY = minecraft.mouseHandler.ypos() * minecraft.getWindow().getGuiScaledHeight()
                / minecraft.getWindow().getScreenHeight();
        ItemStack hovered = findHoveredItem(screen, mouseX, mouseY).orElse(ItemStack.EMPTY);
        if (hovered.isEmpty()) {
            return false;
        }

        ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(hovered.getItem());
        if (!ArchaeologyJournalClientState.hasItemSearchResult(itemId)) {
            return false;
        }
        ArchaeologyJournalUi.openForItem(itemId);
        return true;
    }

    private static Optional<ItemStack> findHoveredItem(Screen screen, double mouseX, double mouseY) {
        JournalHoveredItemProvider provider = hoveredItemProvider;
        if (provider != null) {
            Optional<ItemStack> provided = provider.findHoveredItem(screen, mouseX, mouseY);
            if (provided.isPresent()) {
                return provided;
            }
        }
        if (screen instanceof AbstractContainerScreen<?> containerScreen) {
            Slot hoveredSlot = ((AbstractContainerScreenAccessor) containerScreen).getHoveredSlot();
            if (hoveredSlot != null && hoveredSlot.hasItem()) {
                return Optional.of(hoveredSlot.getItem());
            }
        }
        return Optional.empty();
    }
}
