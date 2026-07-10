package com.meteorite.unsuspiciousblock.cat;

import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.cat.state.CatFavorState;
import com.meteorite.unsuspiciousblock.effect.ModEffects;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.trading.MerchantOffer;

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
    public static final int FLAG_CAT_COMPANION = 1 << 3;
    public static final int FLAG_SOFT_PAWS = 1 << 4;

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

    // 九命无敌窗口（tick）= 猫之恩惠 buff 时长：15 秒
    public static final int NINE_LIVES_INVULN_TICKS = 300;
    // 九命增益持续（tick）：15 秒
    public static final int NINE_LIVES_BUFF_TICKS = 300;

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
        updateStepHeight(player, hasHand && favor >= CatFavorAbility.SOFT_PAWS.threshold());
        updateNightVision(player, state, hasHand, favor);
        detectRaidVictory(player, state);
        grantNineLivesOnCap(state, player, hasHand, favor);
    }

    // 计算并缓存能力位掩码（仅缓存供高频 mixin 查询的能力）
    private static void updateAbilityMask(CatFavorState state, boolean hasHand, int favor) {
        int mask = 0;
        if (hasHand) {
            if (CatFavorAbility.DETERRENCE.isUnlockedAt(favor) && !state.isDeterrenceDisabled()) {
                mask |= FLAG_DETERRENCE;
            }
            if (CatFavorAbility.LIGHT_STEP.isUnlockedAt(favor)) {
                mask |= FLAG_LIGHT_STEP_TRAMPLE;
                if (state.isLightStepPressurePrevented()) {
                    mask |= FLAG_LIGHT_STEP_NO_PRESSURE;
                }
            }
            if (CatFavorAbility.CAT_COMPANION.isUnlockedAt(favor)) {
                mask |= FLAG_CAT_COMPANION;
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

    // 「猫之九命」授予：恩惠首次封顶时给予 1 命；封顶后入睡加命在 CatRelaxOnOwnerGoalMixin 处理
    private static void grantNineLivesOnCap(CatFavorState state, ServerPlayer player, boolean hasHand, int favor) {
        if (hasHand && CatFavorAbility.NINE_LIVES.isUnlockedAt(favor) && state.getNineLivesCount() == 0) {
            state.setNineLivesCount(1);
            // 同步给客户端（HUD/tooltip 需要显示新命数）
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

    // 处理「轻步」压力板开关切换，返回切换后是否阻止压力板触发
    public static boolean onLightStepToggle(ServerPlayer player) {
        CatFavorState state = CatFavorManager.getState(player);
        if (state == null) {
            return true;
        }
        boolean prevented = state.toggleLightStepPressure();
        // 立即刷新位掩码
        boolean hasHand = CatFavorManager.hasHandOfCatInInventory(player);
        updateAbilityMask(state, hasHand, state.getFavor());
        return prevented;
    }

    // ========== 供 mixin 查询的能力判定 ==========

    // 苦力怕是否应惧怕该玩家（猫的威慑，favor≥20 且未关闭）
    public static boolean hasActiveDeterrence(Player player) {
        CatFavorState state = CatFavorManager.getState(player);
        return state != null && (state.getAbilityMask() & FLAG_DETERRENCE) != 0;
    }

    // 玩家是否拥有轻步（favor≥35），用于耕地不退化
    public static boolean hasLightStep(Player player) {
        CatFavorState state = CatFavorManager.getState(player);
        return state != null && (state.getAbilityMask() & FLAG_LIGHT_STEP_TRAMPLE) != 0;
    }

    // 玩家是否应忽略压力板/绊线（favor≥35 且开关启用）
    public static boolean hasLightStepPressurePlateIgnored(Player player) {
        CatFavorState state = CatFavorManager.getState(player);
        return state != null && (state.getAbilityMask() & FLAG_LIGHT_STEP_NO_PRESSURE) != 0;
    }

    // 玩家是否拥有猫的陪伴（favor≥50），幻翼不再以此玩家为目标
    public static boolean hasCatCompanion(Player player) {
        CatFavorState state = CatFavorManager.getState(player);
        return state != null && (state.getAbilityMask() & FLAG_CAT_COMPANION) != 0;
    }

    // 玩家是否拥有柔软肉垫（favor≥70），用于摔落减伤
    public static boolean hasSoftPaws(Player player) {
        CatFavorState state = CatFavorManager.getState(player);
        return state != null && (state.getAbilityMask() & FLAG_SOFT_PAWS) != 0;
    }

    // 玩家当前是否处于猫之九命无敌窗口（由「猫之恩惠」buff 驱动）
    public static boolean isNineLivesInvulnerable(Player player) {
        return player.hasEffect(ModEffects.CAT_FAVOR);
    }

    // 玩家是否满足古国往礼条件（favor≥90，鲜见路径，直接校验）
    public static boolean canSummonAncientGift(Player player) {
        if (!CatFavorManager.hasHandOfCatInInventory(player)) {
            return false;
        }
        CatFavorState state = CatFavorManager.getState(player);
        return state != null && CatFavorAbility.ANCIENT_GIFT.isUnlockedAt(state.getFavor());
    }

    // 玩家是否满足古国往礼交易折扣条件（favor≥90）
    public static boolean hasTradeDiscount(Player player) {
        if (!CatFavorManager.hasHandOfCatInInventory(player)) {
            return false;
        }
        CatFavorState state = CatFavorManager.getState(player);
        return state != null && CatFavorAbility.ANCIENT_GIFT.isUnlockedAt(state.getFavor());
    }

    /**
     * 对村民/流浪商人的交易应用古国往礼折扣（打八折）。
     * 每次交互时先重置 specialPriceDiff 再应用，避免累积污染村民持久状态。
     * 须在 villager 发送 offers 给客户端之前调用（即玩家右键交互事件中）。
     */
    public static void tryApplyTradeDiscount(ServerPlayer player, AbstractVillager villager) {
        if (!hasTradeDiscount(player)) {
            return;
        }
        for (MerchantOffer offer : villager.getOffers()) {
            // 先重置，确保从干净状态开始（避免上次未清理的折扣累积）
            offer.resetSpecialPriceDiff();
            int baseCount = offer.getBaseCostA().getCount();
            // 打八折：减去 20%，最低减 1 兜底
            int discount = Math.max(1, (int) Math.floor(baseCount * 0.2));
            offer.addToSpecialPriceDiff(-discount);
        }
    }

    // 玩家是否可触发猫之九命（持有猫之手且命数>0）
    public static boolean canTriggerNineLives(Player player) {
        if (!CatFavorManager.hasHandOfCatInInventory(player)) {
            return false;
        }
        CatFavorState state = CatFavorManager.getState(player);
        return state != null && state.getNineLivesCount() > 0;
    }

    // ========== 猫之九命执行 ==========

    /**
     * 触发猫之九命：满血复活、授予「猫之恩惠」buff（15 秒无敌，虚空除外）、
     * 力量 II + 速度 II 15 秒。消耗一条命；若消耗后命数归零，恩惠值清空。
     * 调用方需先确认图腾未触发且玩家满足条件。
     */
    public static void triggerNineLives(ServerPlayer player) {
        CatFavorState state = CatFavorManager.getState(player);
        if (state == null || !state.consumeOneLife()) {
            return;
        }
        player.setHealth(player.getMaxHealth());
        player.removeAllEffects();
        // 猫之恩惠：15s 无敌窗口（须在 removeAllEffects 之后授予，否则会被清除）
        player.addEffect(new MobEffectInstance(ModEffects.CAT_FAVOR,
                NINE_LIVES_INVULN_TICKS, 0, true, true, true));
        player.addEffect(new MobEffectInstance(MobEffects.DAMAGE_BOOST, NINE_LIVES_BUFF_TICKS, 1, true, true, true));
        player.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, NINE_LIVES_BUFF_TICKS, 1, true, true, true));
        // 最后一条命消失时恩惠清空
        if (state.getNineLivesCount() == 0) {
            state.setFavor(0);
        }
        CatFavorManager.sync(player);
    }

    // 满恩惠时与猫一同入睡：增加一条命（上限 9），返回是否成功增加
    public static boolean tryAddLifeOnSleep(ServerPlayer player) {
        CatFavorState state = CatFavorManager.getState(player);
        if (state == null) {
            return false;
        }
        if (!CatFavorManager.hasHandOfCatInInventory(player)
                || !CatFavorAbility.NINE_LIVES.isUnlockedAt(state.getFavor())) {
            return false;
        }
        if (state.getNineLivesCount() >= 9) {
            return false;
        }
        state.addOneLife();
        CatFavorManager.sync(player);
        return true;
    }
}
