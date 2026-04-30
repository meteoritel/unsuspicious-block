package com.meteorite.unsuspiciousblock.mixin;

import com.meteorite.unsuspiciousblock.journal.ArchaeologyJournalStateHolder;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerPlayer.class)
public abstract class ServerPlayerJournalStateMixin {
    @Inject(method = "restoreFrom", at = @At("TAIL"))
    private void unsuspiciousblock$copyJournalState(ServerPlayer oldPlayer, boolean alive, CallbackInfo ci) {
        if (oldPlayer instanceof ArchaeologyJournalStateHolder holder && this instanceof ArchaeologyJournalStateHolder self) {
            self.unsuspiciousblock$getArchaeologyJournalState().copyFrom(holder.unsuspiciousblock$getArchaeologyJournalState());
        }
    }
}
