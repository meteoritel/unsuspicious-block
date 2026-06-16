package com.meteorite.unsuspiciousblock.cat;

import com.meteorite.unsuspiciousblock.network.payload.c2s.CatNightVisionPayload;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/** 猫之手相关 C2S 数据包的服务端处理器 */
public final class CatNetworkHandler {

    private CatNetworkHandler() {
    }

    // 处理夜视开关请求
    public static void handleNightVision(ServerPlayer player, CatNightVisionPayload payload) {
        CatPassiveAbilities.onNightVisionRequest(player, payload.active());
    }

    // 处理「猫的威慑」开关切换，并向玩家反馈当前状态
    public static void handleDeterrenceToggle(ServerPlayer player) {
        boolean disabled = CatPassiveAbilities.onDeterrenceToggle(player);
        String key = disabled
                ? "message.unsuspiciousblock.cat_deterrence.off"
                : "message.unsuspiciousblock.cat_deterrence.on";
        player.displayClientMessage(Component.translatable(key), true);
    }
}
