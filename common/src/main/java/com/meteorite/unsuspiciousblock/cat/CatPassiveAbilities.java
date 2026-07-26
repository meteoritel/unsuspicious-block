package com.meteorite.unsuspiciousblock.cat;

import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.cat.state.CatFavorState;
import com.meteorite.unsuspiciousblock.effect.ModEffects;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

/**
 * 猫之手被动能力中枢——服务端每玩家每 tick 评估恩惠阈值派生的各项被动能力，
 * 集中处理：属性修饰符增减（步伐）、能力位掩码缓存（供 mixin 廉价查询）、
 * 夜视刷新/淡出、击退袭击上升沿检测、九命授予与触发。所有判定均为服务端权威。
 */
public final class CatPassiveAbilities {

    // ========== 能力位掩码（缓存在玩家状态中，供 mixin 免库存扫描查询） ==========
    public static final int FLAG_DETERRENCE = 1;
    public static final int FLAG_LIGHT_STEP_TRAMPLE = 1 << 1;
    public static final int FLAG_LIGHT_STEP_NO_PRESSURE = 1 << 2;
    public static final int FLAG_SOFT_PAWS = 1 << 3;

    // 「猫的步伐」步高修饰符：基础 0.6 + 0.65 ≈ 1.25 格
    private static final ResourceLocation STEP_MODIFIER_ID =
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "cat_step");
    private static final double STEP_BONUS = 0.65;

    // 「猫的眼」夜视：服务端分级检测，降低高频亮度查询开销
    // 触发夜视的亮度阈值（环境亮度低于此值视为黑暗）
    private static final int DARKNESS_THRESHOLD = 9;
    // 夜视持续时长（tick）：16 秒，刷新窗口留足余量避免画面闪烁
    private static final int NIGHT_VISION_DURATION = 320;
    // 空闲态检查间隔（tick）：1 秒一次，用于侦测进入黑暗
    private static final int NIGHT_VISION_CHECK_IDLE = 20;
    // 激活态刷新间隔（tick）：5 秒一次，已授予夜视后续期刷新
    private static final int NIGHT_VISION_CHECK_ACTIVE = 100;

    // 九命触发后的纯无敌缓冲：2 秒。
    public static final int NINE_LIVES_INVULN_TICKS = 40;

    private CatPassiveAbilities() {
    }

    /** 服务端每玩家每 tick 主驱动 */
    public static void serverTick(ServerPlayer player) {
        CatFavorState state = CatFavorManager.getState(player);
        if (state == null) {
            return;
        }
        boolean hasHand = CatFavorManager.hasHandOfCatInInventory(player);
        int favor = state.getCatBond();

        updateAbilityMask(state, hasHand, favor);
        updateStepHeight(player, hasHand && favor >= CatFavorAbility.SOFT_PAWS.threshold());
        updateNightVision(player, state, hasHand, favor);
        detectRaidVictory(player, state);
        grantNineLivesOnCap(state, player, favor);
    }

    // 计算并缓存能力位掩码（仅缓存供高频 mixin 查询的能力）
    private static void updateAbilityMask(CatFavorState state, boolean hasHand, int favor) {
        int mask = 0;
        if (hasHand) {
            if (CatFavorAbility.DETERRENCE.isUnlockedAt(favor) && !state.isDeterrenceDisabled()) {
                mask |= FLAG_DETERRENCE;
            }
            if (CatFavorAbility.LIGHT_STEP.isUnlockedAt(favor) && !state.isLightStepDisabled()) {
                mask |= FLAG_LIGHT_STEP_TRAMPLE;
                mask |= FLAG_LIGHT_STEP_NO_PRESSURE;
            }
            if (CatFavorAbility.SOFT_PAWS.isUnlockedAt(favor)) {
                mask |= FLAG_SOFT_PAWS;
            }
        }
        state.setAbilityMask(mask);
    }

    // 「猫的步伐」：幂等增减步高属性修饰符；按住shift潜行时不应用，避免影响从方块边缘下落
    private static void updateStepHeight(ServerPlayer player, boolean want) {
        AttributeInstance instance = player.getAttribute(Attributes.STEP_HEIGHT);
        if (instance == null) {
            return;
        }
        // 按住shift潜行时不应用步高，保留从方块边缘潜行下落的能力
        boolean effectiveWant = want && !player.isShiftKeyDown();
        boolean has = instance.getModifier(STEP_MODIFIER_ID) != null;
        if (effectiveWant && !has) {
            instance.addTransientModifier(new AttributeModifier(
                    STEP_MODIFIER_ID, STEP_BONUS, AttributeModifier.Operation.ADD_VALUE));
        } else if (!effectiveWant && has) {
            instance.removeModifier(STEP_MODIFIER_ID);
        }
    }

    // 「猫的眼」夜视：服务端分级检测亮度
    // 空闲态每 1s 侦测一次进入黑暗；激活后每 5s 刷新一次夜视，刷新时若条件不再满足则回到空闲态
    private static void updateNightVision(ServerPlayer player, CatFavorState state,
                                          boolean hasHand, int favor) {
        int cooldown = state.getNightVisionCheckCooldown();
        if (cooldown > 0) {
            state.setNightVisionCheckCooldown(cooldown - 1);
            return;
        }
        boolean eligible = hasHand
                && CatFavorAbility.CAT_EYE.isUnlockedAt(favor)
                && player.level().getMaxLocalRawBrightness(player.blockPosition()) < DARKNESS_THRESHOLD;
        if (eligible) {
            // 授予/刷新 16s 夜视；MC 仅在新时长更长时覆盖，剩余约 20tick 时刷新到 120tick 平滑续期
            player.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION,
                    NIGHT_VISION_DURATION, 0, true, false, false));
            state.setNightVisionCheckCooldown(NIGHT_VISION_CHECK_ACTIVE - 1);
        } else {
            // 条件不满足：不再续期，夜视自然过期；回到空闲态高频侦测
            state.setNightVisionCheckCooldown(NIGHT_VISION_CHECK_IDLE - 1);
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

    // 玩家首次成为猫国挚友时永久记录并授予 1 条命。
    private static void grantNineLivesOnCap(CatFavorState state, ServerPlayer player, int favor) {
        if (CatFavorAbility.NINE_LIVES.isUnlockedAt(favor)
                && !state.isInitialBestFriendLifeGranted()) {
            state.markInitialBestFriendLifeGranted();
            state.setNineLivesCount(Math.max(1, state.getNineLivesCount()));
            CatFavorManager.sync(player);
        }
    }

    // ========== 客户端请求处理（C2S） ==========

    // 处理「猫的威慑」开关切换，返回切换后是否已关闭
    public static boolean onDeterrenceToggle(ServerPlayer player) {
        CatFavorState state = CatFavorManager.getState(player);
        if (state == null) {
            return false;
        }
        return state.toggleDeterrence();
    }

    // 处理轻步总开关切换，返回切换后是否启用。
    public static boolean onLightStepToggle(ServerPlayer player) {
        CatFavorState state = CatFavorManager.getState(player);
        if (state == null) {
            return true;
        }
        boolean enabled = state.toggleLightStepPressure();
        // 立即刷新位掩码
        boolean hasHand = CatFavorManager.hasHandOfCatInInventory(player);
        updateAbilityMask(state, hasHand, state.getCatBond());
        return enabled;
    }

    // ========== 供 mixin 查询的能力判定 ==========

    // 苦力怕与幻翼是否应回避该玩家（猫之威慑，羁绊至少 20 且未关闭）。
    public static boolean hasActiveDeterrence(Player player) {
        CatFavorState state = CatFavorManager.getState(player);
        return state != null && (state.getAbilityMask() & FLAG_DETERRENCE) != 0;
    }

    // 玩家是否拥有已开启的轻步，用于耕地不退化。
    public static boolean hasLightStep(Player player) {
        CatFavorState state = CatFavorManager.getState(player);
        return state != null && (state.getAbilityMask() & FLAG_LIGHT_STEP_TRAMPLE) != 0;
    }

    // 玩家是否应忽略压力板与绊线。
    public static boolean hasLightStepPressurePlateIgnored(Player player) {
        CatFavorState state = CatFavorManager.getState(player);
        return state != null && (state.getAbilityMask() & FLAG_LIGHT_STEP_NO_PRESSURE) != 0;
    }

    // 幻翼生成与索敌复用猫之威慑总开关。
    public static boolean hasPhantomDeterrence(Player player) {
        return hasActiveDeterrence(player);
    }

    // 玩家是否拥有柔软肉垫（羁绊至少 60），用于摔落减伤。
    public static boolean hasSoftPaws(Player player) {
        CatFavorState state = CatFavorManager.getState(player);
        return state != null && (state.getAbilityMask() & FLAG_SOFT_PAWS) != 0;
    }

    // 玩家当前是否处于猫之九命无敌窗口（由「猫之恩惠」buff 驱动）
    public static boolean isNineLivesInvulnerable(Player player) {
        return player.hasEffect(ModEffects.CAT_FAVOR);
    }

    // 玩家是否满足猫国往礼条件（羁绊至少 80 且携带本人信物）。
    public static boolean canSummonAncientGift(Player player) {
        if (!CatFavorManager.hasHandOfCatInInventory(player)) {
            return false;
        }
        CatFavorState state = CatFavorManager.getState(player);
        return state != null && CatFavorAbility.ANCIENT_GIFT.isUnlockedAt(state.getCatBond());
    }

    // 玩家是否可触发猫之九命（持有猫之手且命数>0）
    public static boolean canTriggerNineLives(Player player) {
        if (!CatFavorManager.hasHandOfCatInInventory(player)) {
            return false;
        }
        CatFavorState state = CatFavorManager.getState(player);
        return state != null
                && state.getCatBond() == CatFavorState.MAX_FAVOR
                && state.getNineLivesCount() > 0;
    }

    // ========== 猫之九命执行 ==========

    // 触发九命：恢复满血、清除负面效果、提供 2 秒纯无敌并召唤剑士猫猫。
    public static void triggerNineLives(ServerPlayer player, net.minecraft.world.damagesource.DamageSource source) {
        CatFavorState state = CatFavorManager.getState(player);
        if (state == null || !state.consumeOneLife()) {
            return;
        }
        player.setHealth(player.getMaxHealth());
        for (MobEffectInstance effect : java.util.List.copyOf(player.getActiveEffects())) {
            if (effect.getEffect().value().getCategory() == MobEffectCategory.HARMFUL) {
                player.removeEffect(effect.getEffect());
            }
        }
        player.addEffect(new MobEffectInstance(ModEffects.CAT_FAVOR,
                NINE_LIVES_INVULN_TICKS, 0, true, true, true));
        LivingEntity preferredTarget = source.getEntity() instanceof LivingEntity living ? living : null;
        SwordsmanCatService.summonOrRefresh(
                player, preferredTarget, SwordsmanCatService.DEFAULT_LIFETIME_TICKS);
        CatFavorManager.sync(player);
    }
}
