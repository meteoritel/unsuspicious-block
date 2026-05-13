package com.meteorite.unsuspiciousblock.enchantment.scanner;

import com.meteorite.unsuspiciousblock.enchantment.ModEnchantments;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** 透视扫描附魔服务：提供扫描半径与球形位置收集。 */
public final class PenetratingScanService {
    private static final int LEVEL_ONE_RADIUS = 2;
    private static final int LEVEL_TWO_RADIUS = 4;

    private PenetratingScanService() {
    }

    public static int getScanRadius(RegistryAccess registryAccess, ItemStack readerStack) {
        int level = ModEnchantments.getEnchantmentLevel(registryAccess, readerStack,
                ModEnchantments.PENETRATING_SCAN);
        if (level >= 2) {
            return LEVEL_TWO_RADIUS;
        }
        if (level == 1) {
            return LEVEL_ONE_RADIUS;
        }
        return 0;
    }

    public static List<BlockPos> collectNearbyTargets(BlockPos center, int radius) {
        if (radius <= 0) {
            return List.of();
        }

        List<BlockPos> targets = new ArrayList<>();
        int maxDistanceSquared = radius * radius;
        for (BlockPos currentPos : BlockPos.betweenClosed(center.offset(-radius, -radius, -radius),
                center.offset(radius, radius, radius))) {
            if (currentPos.equals(center) || center.distSqr(currentPos) > maxDistanceSquared) {
                continue;
            }
            targets.add(currentPos.immutable());
        }
        return targets;
    }
}
