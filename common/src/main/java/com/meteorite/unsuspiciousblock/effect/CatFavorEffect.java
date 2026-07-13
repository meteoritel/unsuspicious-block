package com.meteorite.unsuspiciousblock.effect;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import org.jetbrains.annotations.NotNull;

/**
 * 猫之恩惠效果——九命触发的 15 秒无敌窗口，以正面 buff 形式注册。
 *
 * <p>当前功能（由外部驱动，不在 effect 内实现）：
 * <ul>
 *   <li>完全屏蔽玩家受到的伤害（虚空除外）——由 {@code PlayerHurtInvulnMixin} 检测本 buff 存在性拦截</li>
 *   <li>客户端渲染半透明金色保护罩——由 {@code CatFavorShieldRenderer} 检测本 buff 存在性绘制</li>
 * </ul>
 *
 * <p>内置增益（每 20 tick 刷新）：
 * <ul>
 *   <li>力量 II（+6 攻击伤害）</li>
 *   <li>速度 II（+40% 移动速度）</li>
 * </ul>
 *
 * <p>预留扩展接口（未来新增功能时在此实现）：
 * <ul>
 *   <li>{@link #onEffectStarted}——效果启动钩子，可加粒子/音效</li>
 *   <li>{@link #onMobHurt}——受击钩子，可加反击/驱散等</li>
 * </ul>
 */
public class CatFavorEffect extends MobEffect {

    // 暖金色（与 HUD 猫爪图标、范围扫描框主题色一致）
    private static final int COLOR = 0xFFD580;

    // 内部增益刷新间隔（tick）
    private static final int APPLY_INTERVAL = 20;
    // 内部增益持续时长（tick），略大于刷新间隔以保证无缝覆盖
    private static final int SUB_BUFF_DURATION = 25;

    public CatFavorEffect() {
        super(MobEffectCategory.BENEFICIAL, COLOR);
    }

    @Override
    public boolean shouldApplyEffectTickThisTick(int duration, int amplifier) {
        return true;
    }

    @Override
    public boolean applyEffectTick(@NotNull LivingEntity entity, int amplifier) {
        entity.addEffect(new MobEffectInstance(MobEffects.DAMAGE_BOOST,
                SUB_BUFF_DURATION, 1, true, true, true));
        entity.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED,
                SUB_BUFF_DURATION, 1, true, true, true));
        return true;
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
