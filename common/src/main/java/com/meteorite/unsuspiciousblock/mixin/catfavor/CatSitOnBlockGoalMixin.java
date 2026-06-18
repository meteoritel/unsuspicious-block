package com.meteorite.unsuspiciousblock.mixin.catfavor;

import com.meteorite.unsuspiciousblock.cat.CatFavorAction;
import com.meteorite.unsuspiciousblock.cat.CatFavorManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.CatSitOnBlockGoal;
import net.minecraft.world.entity.ai.goal.MoveToBlockGoal;
import net.minecraft.world.entity.animal.Cat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 注入原版「驯服猫坐在床/箱子/燃烧熔炉上」AI（CatSitOnBlockGoal）：
 * 当猫到达并持续坐定满 30 秒且中途未被打断时，为其主人累积一次恩惠。
 * <p>
 * 继承自 {@link MoveToBlockGoal}，使 isReachedTarget()（声明于 MoveToBlockGoal）
 * 以真实继承成员方式访问，避免 @Shadow 无法解析父类成员的问题。
 */
@Mixin(CatSitOnBlockGoal.class)
public abstract class CatSitOnBlockGoalMixin extends MoveToBlockGoal {

    @Shadow
    @org.spongepowered.asm.mixin.Final
    private Cat cat;

    // 仅为满足编译器对父类构造器的要求，Mixin 运行时不会使用此构造器
    protected CatSitOnBlockGoalMixin(PathfinderMob mob, double speedModifier, int searchRange) {
        super(mob, speedModifier, searchRange);
    }

    // 坐定持续 30 秒 = 600 tick
    @Unique
    private static final int UNSUSPICIOUSBLOCK_SIT_REQUIRED_TICKS = 600;

    // 连续坐定计时
    @Unique
    private int unsuspiciousblock$sitTicks;
    // 本次坐定是否已发放恩惠
    @Unique
    private boolean unsuspiciousblock$awarded;

    // 每 tick 统计坐定时长，达标且未发放时为主人累积恩惠
    @Inject(method = "tick", at = @At("TAIL"))
    private void unsuspiciousblock$trackSitting(CallbackInfo ci) {
        if (!this.isReachedTarget()) {
            this.unsuspiciousblock$sitTicks = 0;
            return;
        }
        this.unsuspiciousblock$sitTicks++;
        if (this.unsuspiciousblock$sitTicks >= UNSUSPICIOUSBLOCK_SIT_REQUIRED_TICKS && !this.unsuspiciousblock$awarded) {
            LivingEntity owner = this.cat.getOwner();
            if (owner instanceof ServerPlayer serverPlayer) {
                CatFavorManager.tryAccumulate(serverPlayer, CatFavorAction.SIT_ON_BLOCK);
            }
            this.unsuspiciousblock$awarded = true;
        }
    }

    // 行为结束（被打断或正常停止）时重置计时与发放标记
    @Inject(method = "stop", at = @At("TAIL"))
    private void unsuspiciousblock$resetSitting(CallbackInfo ci) {
        this.unsuspiciousblock$sitTicks = 0;
        this.unsuspiciousblock$awarded = false;
    }
}
