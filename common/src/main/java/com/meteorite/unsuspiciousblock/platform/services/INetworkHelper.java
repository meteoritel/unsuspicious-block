package com.meteorite.unsuspiciousblock.platform.services;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

/** 网络抽象接口——抽象各平台的数据包发送 */
public interface INetworkHelper {

    /** 向指定玩家发送数据包 */
    <T extends CustomPacketPayload> void sendToPlayer(ServerPlayer player, T payload);
}
