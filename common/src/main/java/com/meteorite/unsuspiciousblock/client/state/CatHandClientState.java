package com.meteorite.unsuspiciousblock.client.state;

import com.meteorite.unsuspiciousblock.client.keybind.ModKeyBindings;
import com.meteorite.unsuspiciousblock.item.ModItems;
import com.meteorite.unsuspiciousblock.network.payload.c2s.CatDeterrenceTogglePayload;
import com.meteorite.unsuspiciousblock.network.payload.c2s.CatNightVisionPayload;
import com.meteorite.unsuspiciousblock.platform.Services;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * 猫之手客户端状态——客户端每 tick 处理：
 * 1) 「猫的眼」环境亮度监测：仅在亮度跨越阈值（暗↔亮）时向服务端发包，避免频繁检测；
 * 2) 「猫的威慑」开关按键：按下时向服务端发送切换请求。
 * 所有能力的最终生效与校验均在服务端完成。
 */
public final class CatHandClientState {

    // 触发夜视的亮度阈值（环境亮度低于此值视为黑暗）
    private static final int DARKNESS_THRESHOLD = 7;

    // 上次上报给服务端的黑暗状态
    private static boolean lastDark;
    // 是否已进行过首次上报
    private static boolean initialized;

    private CatHandClientState() {
    }

    public static void tick() {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null) {
            reset();
            return;
        }

        // 威慑开关按键：发送切换请求
        while (ModKeyBindings.CAT_DETERRENCE_TOGGLE.consumeClick()) {
            Services.NETWORK.sendToServer(CatDeterrenceTogglePayload.INSTANCE);
        }

        // 夜视亮度监测：背包无猫之手时不监测，若此前处于黑暗态则通知服务端淡出
        if (!hasHandOfCat(player)) {
            if (lastDark) {
                lastDark = false;
                initialized = false;
                Services.NETWORK.sendToServer(new CatNightVisionPayload(false));
            }
            return;
        }

        Level level = player.level();
        BlockPos pos = player.blockPosition();
        boolean dark = level.getMaxLocalRawBrightness(pos) < DARKNESS_THRESHOLD;
        if (!initialized || dark != lastDark) {
            initialized = true;
            lastDark = dark;
            Services.NETWORK.sendToServer(new CatNightVisionPayload(dark));
        }
    }

    // 客户端侧检查背包是否存在猫之手（仅用于发包优化）
    private static boolean hasHandOfCat(LocalPlayer player) {
        if (ModItems.HAND_OF_CAT == null) {
            return false;
        }
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.getItem() == ModItems.HAND_OF_CAT) {
                return true;
            }
        }
        return false;
    }

    // 断线时重置监测状态
    public static void reset() {
        lastDark = false;
        initialized = false;
    }
}
