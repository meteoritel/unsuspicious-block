package com.meteorite.unsuspiciousblock.entity.ai.spiritcat;

import com.meteorite.unsuspiciousblock.entity.MessengerCat;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * 猫猫信使显现位置选择器，优先寻找玩家可见且没有方块碰撞的位置。
 */
public final class MessengerCatPositioning {
    private static final double MIN_RADIUS = 6.0;
    private static final double MAX_RADIUS = 10.0;
    private static final int POSITION_ATTEMPTS = 12;

    private MessengerCatPositioning() {
    }

    // 将信使放到目标玩家周围；优先可见位置，找不到时使用首个开放位置。
    public static boolean placeNearTarget(ServerLevel level, MessengerCat cat, Player target) {
        Vec3 firstOpen = null;
        for (int attempt = 0; attempt < POSITION_ATTEMPTS; attempt++) {
            double angle = cat.getRandom().nextDouble() * Math.PI * 2.0;
            double radius = MIN_RADIUS + cat.getRandom().nextDouble() * (MAX_RADIUS - MIN_RADIUS);
            double yOffset = 0.8 + cat.getRandom().nextDouble() * 1.2;
            Vec3 candidate = target.position().add(
                    Math.cos(angle) * radius, yOffset, Math.sin(angle) * radius);
            cat.setPos(candidate.x, candidate.y, candidate.z);
            if (!level.noCollision(cat)) {
                continue;
            }
            if (firstOpen == null) {
                firstOpen = candidate;
            }
            if (target.hasLineOfSight(cat)) {
                return true;
            }
        }
        if (firstOpen != null) {
            cat.setPos(firstOpen.x, firstOpen.y, firstOpen.z);
            return true;
        }
        Vec3 look = target.getLookAngle();
        Vec3 horizontal = new Vec3(look.x, 0.0, look.z);
        if (horizontal.lengthSqr() < 0.0001) {
            horizontal = new Vec3(0.0, 0.0, 1.0);
        }
        Vec3 fallback = target.position().add(horizontal.normalize().scale(MIN_RADIUS)).add(0.0, 1.2, 0.0);
        cat.setPos(fallback.x, fallback.y, fallback.z);
        return false;
    }
}
