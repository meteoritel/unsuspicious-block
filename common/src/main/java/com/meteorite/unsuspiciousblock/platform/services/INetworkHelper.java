package com.meteorite.unsuspiciousblock.platform.services;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

public interface INetworkHelper {
    <T extends CustomPacketPayload> void sendToPlayer(ServerPlayer player, T payload);

    <T extends CustomPacketPayload> void sendToServer(T payload);
}
