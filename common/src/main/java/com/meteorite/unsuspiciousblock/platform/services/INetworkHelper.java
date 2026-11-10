package com.meteorite.unsuspiciousblock.platform.services;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

/** 服务端向玩家发送下行消息的平台能力。 */
public interface INetworkHelper {
    /** 向指定玩家发送消息，调用方保持服务器线程约定。 */
    <T extends CustomPacketPayload> void sendToPlayer(ServerPlayer player, T payload);

}
