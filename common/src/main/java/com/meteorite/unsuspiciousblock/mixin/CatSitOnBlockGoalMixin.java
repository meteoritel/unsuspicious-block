package com.meteorite.unsuspiciousblock.mixin;

import com.meteorite.unsuspiciousblock.cat.CatFavorAction;
import com.meteorite.unsuspiciousblock.cat.CatFavorManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.CatSitOnBlockGoal;
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
 */
@Mixin(CatSitOnBlockGoal.class)
public abstract class CatSitOnBlockGoalMixin {

    @Shadow
    @org.spongepowered.asm.mixin.Final
    private Cat cat;

    // 是否已到达目标方块（继承自 MoveToBlockGoal）
    @Shadow
    protected abstract boolean isReachedTarget();

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
