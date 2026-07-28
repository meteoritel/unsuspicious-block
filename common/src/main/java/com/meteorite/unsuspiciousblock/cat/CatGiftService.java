package com.meteorite.unsuspiciousblock.cat;

import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.entity.MessengerCat;
import com.meteorite.unsuspiciousblock.entity.ModEntities;
import com.meteorite.unsuspiciousblock.entity.ai.spiritcat.MorningGiftBehavior;
import com.meteorite.unsuspiciousblock.entity.ai.spiritcat.MessengerCatPositioning;
import com.meteorite.unsuspiciousblock.cat.state.CatFavorState;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.animal.Cat;
import net.minecraft.world.level.storage.loot.LootTable;

/**
 * 古国往礼服务——当满足条件的玩家触发猫的晨礼时，召唤幽灵猫送来更丰厚的礼物。
 */
public final class CatGiftService {

    // 幽灵猫礼物战利品表
    public static final ResourceKey<LootTable> GHOST_GIFT_LOOT_TABLE = ResourceKey.create(
            Registries.LOOT_TABLE,
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "gameplay/cat/ghost_gift"));

    private CatGiftService() {
    }

    /**
     * 尝试为送出晨礼的猫的主人召唤幽灵猫送礼。
     * 仅当主人持有猫之手且恩惠≥80 时生效。
     */
    public static void tryGhostGift(ServerPlayer owner, Cat cat) {
        if (!CatPassiveAbilities.canSummonAncientGift(owner)) {
            return;
        }
        if (!(owner.level() instanceof ServerLevel level)) {
            return;
        }
        CatFavorState state = CatFavorManager.getState(owner);
        if (state == null) {
            return;
        }
        if (state.getActiveMessengerUuid() != null
                && level.getEntity(state.getActiveMessengerUuid()) instanceof MessengerCat existing
                && !existing.isRemoved()) {
            return;
        }
        MessengerCat ghost = ModEntities.MESSENGER_CAT.get().create(level);
        if (ghost == null) {
            return;
        }
        MessengerCatPositioning.placeNearTarget(level, ghost, owner);
        ghost.setYRot(owner.getRandom().nextFloat() * 360.0F);
        // 引礼者只影响开场注视；配送目标始终为玩家。
        MorningGiftBehavior behavior = new MorningGiftBehavior(
                owner.getUUID(), cat.getUUID(), GHOST_GIFT_LOOT_TABLE, true);
        ghost.assignBehavior(behavior);
        ghost.activateDuty(behavior.getMaxLifetime());
        if (level.addFreshEntity(ghost)) {
            state.setActiveMessengerUuid(ghost.getUUID());
        }
    }
}
