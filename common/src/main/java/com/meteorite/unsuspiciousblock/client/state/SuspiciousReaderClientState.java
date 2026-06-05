package com.meteorite.unsuspiciousblock.client.state;

import com.meteorite.unsuspiciousblock.client.keybind.ModKeyBindings;
import com.meteorite.unsuspiciousblock.item.ModItems;
import com.meteorite.unsuspiciousblock.item.SuspiciousReaderItem;
import com.meteorite.unsuspiciousblock.network.payload.c2s.UpdateReaderScanLevelPayload;
import com.meteorite.unsuspiciousblock.platform.Services;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/** 客户端状态：处理按键切换扫描等级、action bar 提示 */
public class SuspiciousReaderClientState {

    private SuspiciousReaderClientState() {
    }

    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.screen != null) return;

        if (ModKeyBindings.SCAN_LEVEL_CYCLE.consumeClick()) {
            handleScanLevelCycle(player);
        }
    }

    private static void handleScanLevelCycle(LocalPlayer player) {
        ItemStack mainHand = player.getMainHandItem();
        ItemStack offHand = player.getOffhandItem();
        ItemStack readerStack = null;

        if (mainHand.getItem() == ModItems.SUSPICIOUS_READER) {
            readerStack = mainHand;
        } else if (offHand.getItem() == ModItems.SUSPICIOUS_READER) {
            readerStack = offHand;
        }

        if (readerStack == null) return;

        int currentLevel = SuspiciousReaderItem.getScanLevel(readerStack);
        int newLevel = (currentLevel + 1) % (SuspiciousReaderItem.MAX_SCAN_LEVEL + 1);

        // 写入客户端 ItemStack NBT 以便即时反映
        SuspiciousReaderItem.setScanLevel(readerStack, newLevel);

        // 同步到服务端
        Services.NETWORK.sendToServer(new UpdateReaderScanLevelPayload(newLevel));

        // 向玩家 action bar 显示当前扫描等级
        String modeKey = switch (newLevel) {
            case 1 -> "item.unsuspiciousblock.suspicious_reader.scan_mode_1";
            case 2 -> "item.unsuspiciousblock.suspicious_reader.scan_mode_2";
            case 3 -> "item.unsuspiciousblock.suspicious_reader.scan_mode_3";
            default -> "item.unsuspiciousblock.suspicious_reader.scan_mode";
        };
        player.displayClientMessage(
                Component.translatable("item.unsuspiciousblock.suspicious_reader.scan_level",
                        Component.translatable(modeKey)),
                true
        );
    }
}