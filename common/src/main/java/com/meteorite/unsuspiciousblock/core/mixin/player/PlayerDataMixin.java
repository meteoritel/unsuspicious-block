package com.meteorite.unsuspiciousblock.core.mixin.player;

import com.meteorite.unsuspiciousblock.core.cat.state.CatFavorState;
import com.meteorite.unsuspiciousblock.core.cat.state.CatFavorStateHolder;
import com.meteorite.unsuspiciousblock.core.journal.migration.LegacyJournalLogAccess;
import com.meteorite.unsuspiciousblock.core.journal.state.ArchaeologyJournalState;
import com.meteorite.unsuspiciousblock.core.journal.state.ArchaeologyJournalStateHolder;
import com.meteorite.unsuspiciousblock.core.player.PlayerDataLifecycle;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 在玩家存取入口协调领域状态，迁移源保留至成功确认。 */
@Mixin(Player.class)
public abstract class PlayerDataMixin implements ArchaeologyJournalStateHolder, CatFavorStateHolder, LegacyJournalLogAccess {
    @Unique
    private final PlayerDataLifecycle unsuspiciousblock$data = new PlayerDataLifecycle();

    @Override
    public ArchaeologyJournalState unsuspiciousblock$getArchaeologyJournalState() {
        return unsuspiciousblock$data.journal();
    }

    @Override
    public CatFavorState unsuspiciousblock$getCatFavorState() {
        return unsuspiciousblock$data.catFavor();
    }

    @Override
    public @Nullable CompoundTag unsuspiciousblock$peekLegacyJournalLogTag() {
        return unsuspiciousblock$data.legacyLog().peek();
    }

    @Override
    public void unsuspiciousblock$acknowledgeLegacyJournalLogMigration() {
        unsuspiciousblock$data.legacyLog().acknowledge();
    }

    @Inject(method = "addAdditionalSaveData", at = @At("TAIL"))
    private void unsuspiciousblock$saveData(CompoundTag tag, CallbackInfo ci) {
        unsuspiciousblock$data.save(tag);
    }

    @Inject(method = "readAdditionalSaveData", at = @At("TAIL"))
    private void unsuspiciousblock$loadData(CompoundTag tag, CallbackInfo ci) {
        unsuspiciousblock$data.load(tag);
    }
}
