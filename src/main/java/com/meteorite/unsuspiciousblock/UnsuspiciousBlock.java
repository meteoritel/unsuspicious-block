package com.meteorite.unsuspiciousblock;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(UnsuspiciousBlock.MOD_ID)
public class UnsuspiciousBlock {
    public static final String MOD_ID = "unsuspiciousblock";
    public static final Logger LOGGER = LogManager.getLogger(MOD_ID);

    public UnsuspiciousBlock(IEventBus modEventBus) {
        ModItems.ITEMS.register(modEventBus);
        modEventBus.addListener(this::registerPackets);

        modEventBus.addListener(ModItems::addCreativeTabEntries);
        LOGGER.info("[UnsuspiciousBlock] Mod initialized.");
    }

    private void registerPackets(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(MOD_ID).versioned("1");
        registrar.playToClient(
                LootResultPacket.TYPE,
                LootResultPacket.STREAM_CODEC,
                LootResultPacket::handle
        );
    }
}
