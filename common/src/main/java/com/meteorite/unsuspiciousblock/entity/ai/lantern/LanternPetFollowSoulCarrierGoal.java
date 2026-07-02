package com.meteorite.unsuspiciousblock.entity.ai.lantern;

import com.meteorite.unsuspiciousblock.entity.LanternPet;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;
import java.util.List;
import java.util.function.Predicate;

/**
 * 提灯宠物跟随灵魂携带者目标。
 * <p>
 * 扫描范围内手持灵魂类物品的玩家，飞向其头顶偏前方位置。
 * 灵魂物品：灵魂沙、灵魂土、灵魂灯笼、灵魂火把。
 * <p>
 * 灵魂灯笼的"火种"会吸引提灯宠物靠近，营造"灯随魂走"的视觉效果。
 */
public class LanternPetFollowSoulCarrierGoal extends Goal {

    // 距离阈值（格）
    private static final double FOLLOW_DIST_SQR_THRESHOLD = 2.5D * 2.5D;
    // 跟随悬停点：玩家头顶后上方偏移
    private static final double HOVER_OFFSET_X = 0.0D;
    private static final double HOVER_OFFSET_Y = 1.8D;
    private static final double HOVER_OFFSET_Z = 0.0D;

    private static final Predicate<ItemStack> SOUL_ITEM = stack ->
            stack.is(Items.SOUL_SAND)
                    || stack.is(Items.SOUL_SOIL)
                    || stack.is(Items.SOUL_LANTERN)
                    || stack.is(Items.SOUL_TORCH);

    private static final TargetingConditions SCAN_CONDITIONS =
            TargetingConditions.forNonCombat().range(16.0D).selector(
                    LanternPetFollowSoulCarrierGoal::holdsSoulItem);

    private final LanternPet pet;
    private final double speedModifier;
    private final double searchRadius;
    @Nullable
    private Player target;

    public LanternPetFollowSoulCarrierGoal(LanternPet pet, double searchRadius, double speedModifier) {
        this.pet = pet;
        this.searchRadius = searchRadius;
        this.speedModifier = speedModifier;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        AABB box = AABB.unitCubeFromLowerCorner(pet.position()).inflate(searchRadius);
        List<Player> nearby = pet.level().getNearbyPlayers(
                SCAN_CONDITIONS, pet, box);
        if (nearby.isEmpty()) {
            this.target = null;
            return false;
        }
        // 取距离最近者
        this.target = nearest(nearby);
        return this.target != null;
    }

    @Override
    public boolean canContinueToUse() {
        if (this.target == null || !this.target.isAlive()) {
            return false;
        }
        if (this.pet.distanceToSqr(this.target) > searchRadius * searchRadius) {
            return false;
        }
        return holdsSoulItem(this.target);
    }

    @Override
    public void start() {
        if (this.target != null) {
            moveToTarget();
        }
    }

    @Override
    public void tick() {
        if (this.target == null) {
            return;
        }
        this.pet.getLookControl().setLookAt(this.target, 30.0F, 30.0F);
        // 接近后悬停，不再推进
        if (this.pet.distanceToSqr(this.target) > FOLLOW_DIST_SQR_THRESHOLD) {
            moveToTarget();
        } else {
            this.pet.getNavigation().stop();
        }
    }

    @Override
    public void stop() {
        this.target = null;
        this.pet.getNavigation().stop();
    }

    // 朝目标头顶悬停点导航
    private void moveToTarget() {
        if (this.target == null) {
            return;
        }
        double tx = this.target.getX() + HOVER_OFFSET_X;
        double ty = this.target.getY() + HOVER_OFFSET_Y;
        double tz = this.target.getZ() + HOVER_OFFSET_Z;
        this.pet.getNavigation().moveTo(tx, ty, tz, this.speedModifier);
    }

    // 在玩家列表中找到距离最近者
    @Nullable
    private Player nearest(List<Player> players) {
        Player best = null;
        double bestSqr = Double.MAX_VALUE;
        for (Player p : players) {
            double d = this.pet.distanceToSqr(p);
            if (d < bestSqr) {
                bestSqr = d;
                best = p;
            }
        }
        return best;
    }

    private static boolean holdsSoulItem(LivingEntity entity) {
        if (!(entity instanceof Player player)) {
            return false;
        }
        return SOUL_ITEM.test(player.getMainHandItem())
                || SOUL_ITEM.test(player.getOffhandItem());
    }
}
