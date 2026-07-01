package com.meteorite.unsuspiciousblock.cat;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/** 猫之手相关 C2S 数据包的服务端处理器 */
public final class CatNetworkHandler {

    private CatNetworkHandler() {
    }

    // 处理「猫的威慑」开关切换，并向玩家反馈当前状态
    public static void handleDeterrenceToggle(ServerPlayer player) {
        boolean disabled = CatPassiveAbilities.onDeterrenceToggle(player);
        String key = disabled
                ? "message.unsuspiciousblock.cat_deterrence.off"
                : "message.unsuspiciousblock.cat_deterrence.on";
        player.displayClientMessage(Component.translatable(key), true);
    }

    // 处理「轻步」压力板开关切换，并向玩家反馈当前状态
    public static void handleLightStepToggle(ServerPlayer player) {
        boolean prevented = CatPassiveAbilities.onLightStepToggle(player);
        String key = prevented
                ? "message.unsuspiciousblock.cat_light_step.on"
                : "message.unsuspiciousblock.cat_light_step.off";
        player.displayClientMessage(Component.translatable(key), true);
    }
}
