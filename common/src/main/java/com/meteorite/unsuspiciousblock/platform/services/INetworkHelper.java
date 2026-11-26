package com.meteorite.unsuspiciousblock.platform.services;

import com.meteorite.unsuspiciousblock.core.network.payload.s2c.PanningStatePayload;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

/** 服务端向玩家发送下行消息的平台能力。 */
public interface INetworkHelper {
    /** 玩家是否通告当前笔记下行通道。 */
    boolean supportsJournal(ServerPlayer player);

    /** 向指定玩家发送消息，调用方保持服务器线程约定。 */
    <T extends CustomPacketPayload> void sendToPlayer(ServerPlayer player, T payload);

    /** 向本人及支持淘洗状态频道的观察者同步会话。 */
    void broadcastPanningState(ServerPlayer player, PanningStatePayload payload);

    /** 向新开始追踪玩家的观察者补发会话。 */
    void sendPanningState(ServerPlayer observer, PanningStatePayload payload);
}
