package com.meteorite.unsuspiciousblock.platform;

import com.meteorite.unsuspiciousblock.platform.services.INetworkHelper;
import com.meteorite.unsuspiciousblock.core.network.payload.s2c.JournalTransferPayload;
import com.meteorite.unsuspiciousblock.core.network.payload.s2c.PanningStatePayload;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

public class FabricNetworkHelper implements INetworkHelper {

    @Override
    public boolean supportsJournal(ServerPlayer player) { return ServerPlayNetworking.canSend(player, JournalTransferPayload.TYPE); }

    @Override
    public <T extends CustomPacketPayload> void sendToPlayer(ServerPlayer player, T payload) {
        ServerPlayNetworking.send(player, payload);
    }

    @Override
    public void broadcastPanningState(ServerPlayer player, PanningStatePayload payload) {
        this.sendPanningState(player, payload);
        for (ServerPlayer observer : PlayerLookup.tracking(player)) {
            this.sendPanningState(observer, payload);
        }
    }

    @Override
    public void sendPanningState(ServerPlayer observer, PanningStatePayload payload) {
        if (observer.connection.isAcceptingMessages() && ServerPlayNetworking.canSend(observer, PanningStatePayload.TYPE)) {
            this.sendToPlayer(observer, payload);
        }
    }
}
