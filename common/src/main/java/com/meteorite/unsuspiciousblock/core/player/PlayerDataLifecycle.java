package com.meteorite.unsuspiciousblock.core.player;

import com.meteorite.unsuspiciousblock.core.cat.state.CatFavorState;
import com.meteorite.unsuspiciousblock.core.cat.state.CatFavorStateHolder;
import com.meteorite.unsuspiciousblock.core.journal.JournalPlayerDataService;
import com.meteorite.unsuspiciousblock.core.journal.migration.PendingLegacyJournalLog;
import com.meteorite.unsuspiciousblock.core.journal.state.ArchaeologyJournalState;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;

/** 玩家领域状态的存取协调；格式与迁移规则由各领域持有。 */
public record PlayerDataLifecycle(ArchaeologyJournalState journal, CatFavorState catFavor,
                                  PendingLegacyJournalLog legacyLog) {
    public PlayerDataLifecycle() {
        this(new ArchaeologyJournalState(), new CatFavorState(), new PendingLegacyJournalLog());
    }

    public void save(CompoundTag tag) {
        JournalPlayerDataService.writePlayerTag(journal, tag);
        catFavor.writePlayerTag(tag);
        legacyLog.writePlayerTag(tag);
    }

    public void load(CompoundTag tag) {
        JournalPlayerDataService.readPlayerTag(journal, tag);
        catFavor.readPlayerTag(tag);
        legacyLog.readPlayerTag(tag);
    }

    public static void copyForRespawn(ServerPlayer oldPlayer, ServerPlayer newPlayer) {
        JournalPlayerDataService.copyForRespawn(oldPlayer, newPlayer);
        if (oldPlayer instanceof CatFavorStateHolder oldState && newPlayer instanceof CatFavorStateHolder newState) {
            newState.unsuspiciousblock$getCatFavorState().copyFrom(oldState.unsuspiciousblock$getCatFavorState());
        }
    }
}
