package com.meteorite.unsuspiciousblock.core.effect;

import net.minecraft.core.Holder;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.gameevent.GameEvent;
import org.jetbrains.annotations.Nullable;

/**
 * 喵步静音与反幽匿振动判定服务——集中处理一级（非疾跑静音）与二级（含疾跑完全静音）规则，
 * 供公共 Mixin 薄入口统一调用。
 */
public final class PurrmintEffectService {
    private static final int STRONG_AMPLIFIER = 1;

    private PurrmintEffectService() {
    }

    /**
     * 判断该实体当前的移动、跳跃与落地是否应保持完全静音。
     *
     * @param entity 待检测实体（允许为空，空值直接返回 false）
     * @return 一级在非疾跑状态返回 true；二级及以上在任意移动状态（含疾跑）均返回 true
     */
    public static boolean shouldSilenceMovement(@Nullable Entity entity) {
        if (ModEffects.CATFOOTED == null || !(entity instanceof LivingEntity living)) {
            return false;
        }
        MobEffectInstance effect = living.getEffect(ModEffects.CATFOOTED);
        if (effect == null) {
            return false;
        }
        if (effect.getAmplifier() >= STRONG_AMPLIFIER) {
            return true;
        }
        return !living.isSprinting();
    }

    /**
     * 判断该实体触发的游戏振动事件是否应被压制，从而不触发幽匿感测体与监守者。
     *
     * @param sourceEntity 产生振动的来源实体
     * @param gameEvent    待传播的振动事件
     * @return 当来源实体处于静音状态且事件属于移动、跳跃着陆或涉水振动时返回 true
     */
    public static boolean shouldSilenceVibration(@Nullable Entity sourceEntity, Holder<GameEvent> gameEvent) {
        return shouldSilenceMovement(sourceEntity) && isMovementVibration(gameEvent);
    }

    private static boolean isMovementVibration(Holder<GameEvent> gameEvent) {
        return gameEvent.is(GameEvent.STEP.key())
                || gameEvent.is(GameEvent.HIT_GROUND.key())
                || gameEvent.is(GameEvent.SWIM.key())
                || gameEvent.is(GameEvent.SPLASH.key())
                || gameEvent.is(GameEvent.FLAP.key())
                || gameEvent.is(GameEvent.ELYTRA_GLIDE.key());
    }
}
