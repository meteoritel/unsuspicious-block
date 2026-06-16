package com.meteorite.unsuspiciousblock.cat;

import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.entity.GhostCat;
import com.meteorite.unsuspiciousblock.entity.ModEntities;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
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
        if (!(owner.level() instanceof ServerLevel level) || ModEntities.GHOST_CAT == null) {
            return;
        }
        GhostCat ghost = ModEntities.GHOST_CAT.create(level);
        if (ghost == null) {
            return;
        }
        RandomSource random = owner.getRandom();
        double x = owner.getX() + (random.nextInt(7) - 3);
        double y = owner.getY();
        double z = owner.getZ() + (random.nextInt(7) - 3);
        ghost.moveTo(x, y, z, random.nextFloat() * 360.0F, 0.0F);
        ghost.assignGift(owner.getUUID(), GHOST_GIFT_LOOT_TABLE);
        level.addFreshEntity(ghost);
    }
}
