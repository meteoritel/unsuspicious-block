package com.meteorite.unsuspiciousblock.cat;

import com.meteorite.unsuspiciousblock.cat.state.CatFavorState;
import com.meteorite.unsuspiciousblock.entity.ModEntities;
import com.meteorite.unsuspiciousblock.entity.SwordsmanCat;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import org.jetbrains.annotations.Nullable;

/**
 * 剑士猫猫召唤服务——保证每名玩家同时最多一只，并在再次触发九命时刷新寿命。
 */
public final class SwordsmanCatService {
    public static final int DEFAULT_LIFETIME_TICKS = 300;

    private SwordsmanCatService() {
    }

    // 召唤或刷新剑士猫猫，优先目标可以为空或不是敌对生物。
    public static void summonOrRefresh(ServerPlayer owner, @Nullable LivingEntity preferredTarget,
                                       int lifetimeTicks) {
        if (!(owner.level() instanceof ServerLevel level)) {
            return;
        }
        CatFavorState state = CatFavorManager.getState(owner);
        if (state == null) {
            return;
        }
        if (state.getActiveSwordsmanUuid() != null
                && level.getEntity(state.getActiveSwordsmanUuid()) instanceof SwordsmanCat existing
                && !existing.isRemoved()) {
            existing.moveTo(owner.getX(), owner.getY() + 1.0, owner.getZ(), owner.getYRot(), 0.0F);
            existing.freezeSpawnY();
            existing.configureProtection(owner.getUUID(), preferredTarget, lifetimeTicks);
            return;
        }

        SwordsmanCat swordsman = ModEntities.SWORDSMAN_CAT.get().create(level);
        if (swordsman == null) {
            return;
        }
        double angle = owner.getRandom().nextDouble() * Math.PI * 2.0;
        double radius = 1.5;
        swordsman.moveTo(
                owner.getX() + Math.cos(angle) * radius,
                owner.getY() + 0.5,
                owner.getZ() + Math.sin(angle) * radius,
                owner.getYRot(), 0.0F);
        swordsman.freezeSpawnY();
        swordsman.configureProtection(owner.getUUID(), preferredTarget, lifetimeTicks);
        level.addFreshEntity(swordsman);
        state.setActiveSwordsmanUuid(swordsman.getUUID());
    }
}
