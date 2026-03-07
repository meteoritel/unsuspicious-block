package com.meteorite.unsuspiciousblock;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.BlockEvent;
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
        NeoForge.EVENT_BUS.addListener(this::onBlockBreak);
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

    @SubscribeEvent
    private void onBlockBreak(BlockEvent.BreakEvent event) {
        long posKey = event.getPos().asLong();
        SuspiciousReaderItem.SCAN_CACHE.remove(posKey);
    }
}
