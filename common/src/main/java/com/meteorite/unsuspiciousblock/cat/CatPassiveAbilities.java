package com.meteorite.unsuspiciousblock.cat;

import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.cat.state.CatFavorState;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;

/**
 * 猫之手被动能力中枢——服务端每玩家每 tick 评估恩惠阈值派生的各项被动能力，
 * 集中处理：属性修饰符增减（步伐）、能力位掩码缓存（供 mixin 廉价查询）、
 * 夜视刷新/淡出、击退袭击上升沿检测。所有判定均为服务端权威。
 */
public final class CatPassiveAbilities {

    // ========== 恩惠阈值 ==========
    public static final int DETERRENCE_THRESHOLD = 20;
    public static final int STEP_THRESHOLD = 40;
    public static final int SOFT_PAWS_THRESHOLD = 60;
    public static final int ANCIENT_GIFT_THRESHOLD = 80;
    public static final int NINE_LIVES_THRESHOLD = 100;

    // ========== 能力位掩码（缓存在玩家状态中，供 mixin 免库存扫描查询） ==========
    public static final int FLAG_DETERRENCE = 1;
    public static final int FLAG_SOFT_PAWS = 1 << 1;

    // 「猫的步伐」步高修饰符：基础 0.6 + 0.65 ≈ 1.25 格
    private static final ResourceLocation STEP_MODIFIER_ID =
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "cat_step");
    private static final double STEP_BONUS = 0.65;

    // 夜视刷新时长（tick）：足够长以避免画面闪烁
    private static final int NIGHT_VISION_REFRESH_TICKS = 220;
    // 离开黑暗后夜视淡出延迟（tick）：2 秒
    private static final int NIGHT_VISION_FADE_TICKS = 40;

    // 九命无敌窗口（tick）：5 秒
    public static final int NINE_LIVES_INVULN_TICKS = 100;
    // 九命增益持续（tick）：15 秒
    public static final int NINE_LIVES_BUFF_TICKS = 300;
    // 九命触发后恩惠回落值
    public static final int NINE_LIVES_RESET_FAVOR = 50;

    private CatPassiveAbilities() {
    }

    /** 服务端每玩家每 tick 主驱动 */
    public static void serverTick(ServerPlayer player) {
        CatFavorState state = CatFavorManager.getState(player);
        if (state == null) {
            return;
        }
        boolean hasHand = CatFavorManager.hasHandOfCatInInventory(player);
        int favor = state.getFavor();

        updateAbilityMask(state, hasHand, favor);
        updateStepHeight(player, hasHand && favor >= STEP_THRESHOLD);
        updateNightVision(player, state, hasHand);
        detectRaidVictory(player, state);
    }

    // 计算并缓存能力位掩码（仅缓存供高频 mixin 查询的能力）
    private static void updateAbilityMask(CatFavorState state, boolean hasHand, int favor) {
        int mask = 0;
        if (hasHand) {
            if (favor >= DETERRENCE_THRESHOLD && !state.isDeterrenceDisabled()) {
                mask |= FLAG_DETERRENCE;
            }
            if (favor >= SOFT_PAWS_THRESHOLD) {
                mask |= FLAG_SOFT_PAWS;
            }
        }
        state.setAbilityMask(mask);
    }

    // 「猫的步伐」：幂等增减步高属性修饰符
    private static void updateStepHeight(ServerPlayer player, boolean want) {
        AttributeInstance instance = player.getAttribute(Attributes.STEP_HEIGHT);
        if (instance == null) {
            return;
        }
        boolean has = instance.getModifier(STEP_MODIFIER_ID) != null;
        if (want && !has) {
            instance.addTransientModifier(new AttributeModifier(
                    STEP_MODIFIER_ID, STEP_BONUS, AttributeModifier.Operation.ADD_VALUE));
        } else if (!want && has) {
            instance.removeModifier(STEP_MODIFIER_ID);
        }
    }

    // 「猫的眼」夜视：黑暗中持续刷新，离开黑暗 2 秒后移除
    private static void updateNightVision(ServerPlayer player, CatFavorState state, boolean hasHand) {
        boolean wantNightVision = state.isNightVisionRequested() && hasHand;
        if (wantNightVision) {
            state.setNightVisionFadeTicks(0);
            player.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION,
                    NIGHT_VISION_REFRESH_TICKS, 0, true, false, false));
            return;
        }
        // 持有物品丢失但仍在请求态：视作离开黑暗，开始淡出
        if (state.isNightVisionRequested()) {
            state.setNightVisionRequested(false);
            state.setNightVisionFadeTicks(NIGHT_VISION_FADE_TICKS);
        }
        int fade = state.getNightVisionFadeTicks();
        if (fade > 0) {
            fade--;
            state.setNightVisionFadeTicks(fade);
            if (fade == 0) {
                player.removeEffect(MobEffects.NIGHT_VISION);
            }
        }
    }

    // 「击退袭击」：检测「村庄英雄」效果由无→有的上升沿
    private static void detectRaidVictory(ServerPlayer player, CatFavorState state) {
        boolean hasHero = player.hasEffect(MobEffects.HERO_OF_THE_VILLAGE);
        if (hasHero && !state.hadHeroEffect()) {
            CatFavorManager.tryAccumulate(player, CatFavorAction.REPEL_RAID);
        }
        state.setHadHeroEffect(hasHero);
    }

    // ========== 客户端请求处理（C2S） ==========

    // 处理夜视开关请求：active=true 进入黑暗请求夜视；false 离开黑暗开始淡出
    public static void onNightVisionRequest(ServerPlayer player, boolean active) {
        CatFavorState state = CatFavorManager.getState(player);
        if (state == null) {
            return;
        }
        if (active) {
            state.setNightVisionRequested(true);
            state.setNightVisionFadeTicks(0);
        } else {
            state.setNightVisionRequested(false);
            state.setNightVisionFadeTicks(NIGHT_VISION_FADE_TICKS);
        }
    }

    // 处理「猫的威慑」开关切换，返回切换后是否已关闭
    public static boolean onDeterrenceToggle(ServerPlayer player) {
        CatFavorState state = CatFavorManager.getState(player);
        if (state == null) {
            return false;
        }
        return state.toggleDeterrence();
    }

    // ========== 供 mixin 查询的能力判定 ==========

    // 苦力怕是否应惧怕该玩家（猫的威慑，favor≥20 且未关闭）
    public static boolean hasActiveDeterrence(Player player) {
        CatFavorState state = CatFavorManager.getState(player);
        return state != null && (state.getAbilityMask() & FLAG_DETERRENCE) != 0;
    }

    // 玩家是否拥有柔软肉垫（favor≥60）
    public static boolean hasSoftPaws(Player player) {
        CatFavorState state = CatFavorManager.getState(player);
        return state != null && (state.getAbilityMask() & FLAG_SOFT_PAWS) != 0;
    }

    // 玩家当前是否处于猫之九命无敌窗口
    public static boolean isNineLivesInvulnerable(Player player) {
        CatFavorState state = CatFavorManager.getState(player);
        if (state == null) {
            return false;
        }
        return player.level().getGameTime() < state.getNineLivesInvulnUntil();
    }

    // 玩家是否满足古国往礼条件（favor≥80，鲜见路径，直接校验）
    public static boolean canSummonAncientGift(Player player) {
        if (!CatFavorManager.hasHandOfCatInInventory(player)) {
            return false;
        }
        CatFavorState state = CatFavorManager.getState(player);
        return state != null && state.getFavor() >= ANCIENT_GIFT_THRESHOLD;
    }

    // 玩家是否满足猫之九命条件（favor=100，鲜见路径，直接校验）
    public static boolean canTriggerNineLives(Player player) {
        if (!CatFavorManager.hasHandOfCatInInventory(player)) {
            return false;
        }
        CatFavorState state = CatFavorManager.getState(player);
        return state != null && state.getFavor() >= NINE_LIVES_THRESHOLD;
    }

    // ========== 猫之九命执行 ==========

    /**
     * 触发猫之九命：满血复活、5 秒无敌、力量 II + 速度 II 15 秒、恩惠回落至 50。
     * 调用方需先确认图腾未触发且玩家满足 favor=100 条件。
     */
    public static void triggerNineLives(ServerPlayer player) {
        CatFavorState state = CatFavorManager.getState(player);
        if (state == null) {
            return;
        }
        player.setHealth(player.getMaxHealth());
        player.removeAllEffects();
        long now = player.level().getGameTime();
        state.setNineLivesInvulnUntil(now + NINE_LIVES_INVULN_TICKS);
        player.addEffect(new MobEffectInstance(MobEffects.DAMAGE_BOOST, NINE_LIVES_BUFF_TICKS, 1, true, true, true));
        player.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, NINE_LIVES_BUFF_TICKS, 1, true, true, true));
        state.setFavor(NINE_LIVES_RESET_FAVOR);
        CatFavorManager.sync(player);
    }
}
