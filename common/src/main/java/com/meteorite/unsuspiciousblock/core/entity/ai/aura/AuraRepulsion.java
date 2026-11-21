package com.meteorite.unsuspiciousblock.core.entity.ai.aura;

import com.meteorite.unsuspiciousblock.core.cat.CatPassiveAbilities;
import com.meteorite.unsuspiciousblock.core.world.aura.AuraEffect;
import com.meteorite.unsuspiciousblock.core.world.aura.BlockAuras;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Phantom;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.Set;

/** 光环驱避的统一决策；原版 AI Adapter 负责提供候选与执行时机。 */
public final class AuraRepulsion {
    private AuraRepulsion() {
    }

    /** 仅对已接入地面或幻翼移动规则的服务端怪物生效。 */
    public static boolean isRepelled(LivingEntity entity) {
        return !entity.level().isClientSide && (entity instanceof Creeper || entity instanceof Phantom);
    }

    /** 受保护实体按脚部所在格判定，保持原版非战斗观察与猫族恩惠独立。 */
    public static boolean isProtectedTarget(LivingEntity attacker, @Nullable LivingEntity target) {
        return isRepelled(attacker) && target != null
                && BlockAuras.hasEffect(target.level(), target.blockPosition(), AuraEffect.MONSTER_REPULSION);
    }

    /** 生成流程独立于索敌，按被选玩家的脚部位置组合空间保护与猫之威慑。 */
    public static boolean hasPhantomSpawnProtection(ServerPlayer player) {
        return CatPassiveAbilities.hasPhantomDeterrence(player)
                || BlockAuras.hasEffect(player.level(), player.blockPosition(), AuraEffect.MONSTER_REPULSION);
    }

    /** 移动目的地须在区外，已处于区内的怪物仍可沿合法边自行离开。 */
    public static boolean canMoveTo(Mob mob, BlockPos target) {
        return !isRepelled(mob)
                || !BlockAuras.hasEffect(mob.level(), target, AuraEffect.MONSTER_REPULSION);
    }

    /** 检查实际移动段，包含区外端点之间穿越区域的情况。 */
    public static boolean blocksMovement(Mob mob, Vec3 destination) {
        return isRepelled(mob) && BlockAuras.blocksEntry(mob.level(), mob.position(), destination);
    }

    /** 下一次寻路、飞行或动作决策发现目标受保护时，统一解除追踪。 */
    public static boolean clearProtectedTarget(Mob mob) {
        if (!isProtectedTarget(mob, mob.getTarget())) {
            return false;
        }
        mob.setTarget(null);
        mob.setAggressive(false);
        stopMovement(mob);
        if (mob instanceof Creeper creeper) {
            stopNaturalSwell(creeper);
        }
        return true;
    }

    /** Goal 的缓存可晚于 Mob 目标清空；单独复核并终止指向受保护实体的自然蓄爆。 */
    public static boolean shouldStopSwell(Creeper creeper, @Nullable LivingEntity cachedTarget) {
        if (!isProtectedTarget(creeper, cachedTarget)) {
            return false;
        }
        clearProtectedTarget(creeper);
        stopNaturalSwell(creeper);
        return true;
    }

    private static void stopNaturalSwell(Creeper creeper) {
        if (!creeper.isIgnited()) {
            creeper.setSwellDir(-1);
        }
    }

    /** 停止旧路径与待执行移动，防止 waypoint 已被撤销后继续主动行走。 */
    public static void stopMovement(Mob mob) {
        mob.getNavigation().stop();
        mob.getMoveControl().setWantedPosition(mob.getX(), mob.getY(), mob.getZ(), 0.0);
        mob.setSpeed(0.0F);
        mob.setZza(0.0F);
        mob.setXxa(0.0F);
    }

    /** 保留原目标集合，只有实际出现受保护位置时才复制。 */
    public static Set<BlockPos> filterDestinations(Mob mob, Set<BlockPos> targets) {
        if (!isRepelled(mob)) {
            return targets;
        }
        Set<BlockPos> allowed = null;
        for (BlockPos target : targets) {
            if (!canMoveTo(mob, target)) {
                if (allowed == null) {
                    allowed = new HashSet<>(targets);
                }
                allowed.remove(target);
            }
        }
        return allowed == null ? targets : allowed;
    }

    /** 过滤 A* 候选边而非封死区域内节点，使区内起点能寻找出口。 */
    public static int filterNeighbors(Mob mob, Node origin, Node[] neighbors, int count) {
        if (!isRepelled(mob)) {
            return count;
        }
        Vec3 from = new Vec3(origin.x + 0.5, origin.y, origin.z + 0.5);
        int accepted = 0;
        for (int index = 0; index < count; index++) {
            Node next = neighbors[index];
            Vec3 to = new Vec3(next.x + 0.5, next.y, next.z + 0.5);
            if (!BlockAuras.blocksEntry(mob.level(), from, to)) {
                neighbors[accepted++] = next;
            }
        }
        return accepted;
    }
}
