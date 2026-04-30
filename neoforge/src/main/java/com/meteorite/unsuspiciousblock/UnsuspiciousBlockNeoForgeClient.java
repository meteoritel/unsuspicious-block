package com.meteorite.unsuspiciousblock;

import com.meteorite.unsuspiciousblock.client.ui.journal.ArchaeologyJournalUi;
import com.meteorite.unsuspiciousblock.client.ui.journal.ArchaeologyJournalScreen;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

@EventBusSubscriber(modid = Constants.MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class UnsuspiciousBlockNeoForgeClient {
    private UnsuspiciousBlockNeoForgeClient() {
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> ArchaeologyJournalUi.registerOpener(state -> Minecraft.getInstance().setScreen(new ArchaeologyJournalScreen(state))));
    }
}
