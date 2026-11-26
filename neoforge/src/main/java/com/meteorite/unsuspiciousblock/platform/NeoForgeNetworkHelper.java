package com.meteorite.unsuspiciousblock.platform;

import com.meteorite.unsuspiciousblock.core.network.payload.s2c.PanningStatePayload;
import com.meteorite.unsuspiciousblock.platform.services.INetworkHelper;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

public class NeoForgeNetworkHelper implements INetworkHelper {

    @Override
    public boolean supportsJournal(ServerPlayer player) { return true; }

    @Override
    public <T extends CustomPacketPayload> void sendToPlayer(ServerPlayer player, T payload) {
        PacketDistributor.sendToPlayer(player, payload);
    }

    @Override
    public void broadcastPanningState(ServerPlayer player, PanningStatePayload payload) {
        PacketDistributor.sendToPlayersTrackingEntity(player, payload);
        this.sendPanningState(player, payload);
    }

    @Override
    public void sendPanningState(ServerPlayer observer, PanningStatePayload payload) {
        if (observer.connection.isAcceptingMessages()) {
            this.sendToPlayer(observer, payload);
        }
    }
}
