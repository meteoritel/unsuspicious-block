package com.meteorite.unsuspiciousblock.client.state;

import com.meteorite.unsuspiciousblock.client.keybind.ModKeyBindings;
import com.meteorite.unsuspiciousblock.network.payload.c2s.CatDeterrenceTogglePayload;
import com.meteorite.unsuspiciousblock.network.payload.c2s.CatLightStepTogglePayload;
import com.meteorite.unsuspiciousblock.platform.Services;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

/**
 * 猫之手客户端状态——客户端每 tick 处理能力开关按键请求：
 * 1) 「猫的威慑」开关按键；
 * 2) 「轻步」压力板开关按键。
 * 「猫的眼」夜视由服务端自主分级检测亮度，客户端不再参与。
 * 所有能力的最终生效与校验均在服务端完成。
 */
public final class CatHandClientState {

    private CatHandClientState() {
    }

    public static void tick() {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null) {
            return;
        }

        // 威慑开关按键：发送切换请求
        while (ModKeyBindings.CAT_DETERRENCE_TOGGLE.consumeClick()) {
            Services.NETWORK.sendToServer(CatDeterrenceTogglePayload.INSTANCE);
        }

        // 轻步压力板开关按键：发送切换请求
        while (ModKeyBindings.CAT_LIGHT_STEP_TOGGLE.consumeClick()) {
            Services.NETWORK.sendToServer(CatLightStepTogglePayload.INSTANCE);
        }
    }
}
