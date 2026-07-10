package com.meteorite.unsuspiciousblock.effect;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.entity.LivingEntity;
import org.jetbrains.annotations.NotNull;

/**
 * 猫之恩惠效果--九命触发的 15 秒无敌窗口，以正面 buff 形式注册。
 *
 * <p>当前功能（由外部驱动，不在 effect 内实现）：
 * <ul>
 *   <li>完全屏蔽玩家受到的伤害（虚空除外）--由 {@code PlayerHurtInvulnMixin} 检测本 buff 存在性拦截</li>
 *   <li>客户端渲染半透明金色保护罩--由 {@code CatFavorShieldRenderer} 检测本 buff 存在性绘制</li>
 * </ul>
 *
 * <p>预留扩展接口（未来新增功能时在此实现）：
 * <ul>
 *   <li>{@link #onEffectStarted} --效果启动钩子，可加粒子/音效</li>
 *   <li>{@link #onMobHurt} --受击钩子，可加反击/驱散等</li>
 *   <li>若需每 tick 行为（治疗、光环等），覆盖 {@code shouldApplyEffectTickThisTick} + {@code applyEffectTick}</li>
 * </ul>
 */
public class CatFavorEffect extends MobEffect {

    // 暖金色（与 HUD 猫爪图标、范围扫描框主题色一致）
    private static final int COLOR = 0xFFD580;

    public CatFavorEffect() {
        super(MobEffectCategory.BENEFICIAL, COLOR);
    }

    // 预留：效果启动时的行为（未来可扩展粒子、音效等）
    @Override
    public void onEffectStarted(@NotNull LivingEntity entity, int amplifier) {
    }

    // 预留：受击行为钩子（当前无敌由 PlayerHurtInvulnMixin 在 hurt HEAD 拦截，
    // 此回调在伤害结算后触发；未来可用于反击、驱散等扩展）
    @Override
    public void onMobHurt(@NotNull LivingEntity entity, int amplifier, @NotNull DamageSource source, float amount) {
    }
}
