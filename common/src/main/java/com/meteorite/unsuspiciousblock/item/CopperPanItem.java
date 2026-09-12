package com.meteorite.unsuspiciousblock.item;

import com.meteorite.unsuspiciousblock.entity.ShimmerEntity;
import com.meteorite.unsuspiciousblock.pan.PanningLootService;
import com.meteorite.unsuspiciousblock.platform.Services;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/***
 * 淘盘——在闪烁的光处淘洗水中沉积物的铜制工具。
 * <p>
 * 使用流程沿用原版刷子的长按机制：对准闪烁的光按住右键，达到配置时长后完成一次淘洗，
 * 消耗 1 点耐久并抽取一次淘洗战利品；准星离开目标或提前松手会立即中断，中断不消耗任何东西。
 * 按住期间玩家移动迟缓，与进食类似。对普通水体、其它实体或空气使用不会产生任何效果。
 * <p>
 * 耐久 32，可在铁砧上用铜锭修复，并兼容耐久、经验修补等通用附魔。
 */
public class CopperPanItem extends Item {
    // 使用期间的移动迟缓：每 5 刻刷新一次 10 刻时长的缓慢效果，停止使用时自动过期
    private static final int SLOW_REFRESH_INTERVAL_TICKS = 5;
    private static final int SLOW_DURATION_TICKS = 10;
    private static final int SLOW_AMPLIFIER = 2;

    public CopperPanItem(Properties properties) {
        super(properties);
    }

    @Override
    public void appendHoverText(@NotNull ItemStack stack, @NotNull TooltipContext context,
                                @NotNull List<Component> tooltipLines, @NotNull TooltipFlag flag) {
        tooltipLines.add(Component.translatable("item.unsuspiciousblock.copper_pan.tooltip_use")
                .withStyle(ChatFormatting.GRAY));
        super.appendHoverText(stack, context, tooltipLines, flag);
    }

    // 使用原版刷子的手臂动画，长按过程有明显动作反馈
    @Override
    public @NotNull UseAnim getUseAnimation(@NotNull ItemStack stack) {
        return UseAnim.BRUSH;
    }

    @Override
    public int getUseDuration(@NotNull ItemStack stack, @NotNull LivingEntity entity) {
        return Services.PANNING_CONFIG.getPanDurationTicks();
    }

    // 铁砧修复材料——铜锭
    @Override
    public boolean isValidRepairItem(@NotNull ItemStack stack, @NotNull ItemStack repairCandidate) {
        return repairCandidate.is(Items.COPPER_INGOT) || super.isValidRepairItem(stack, repairCandidate);
    }

    // 每 tick 校验准星是否仍指向闪烁的光；离开即中断，不消耗耐久也不产出战利品
    @Override
    public void onUseTick(@NotNull Level level, @NotNull LivingEntity user, @NotNull ItemStack stack,
                          int remainingUseDuration) {
        if (!(user instanceof Player player)) {
            user.releaseUsingItem();
            return;
        }
        ShimmerEntity target = findTargetedShimmer(player);
        if (target == null || target.getPanRemaining() <= 0) {
            player.releaseUsingItem();
            return;
        }
        if (level.isClientSide()) {
            return;
        }
        // 迟缓：模拟进食时的移动减速，效果时长很短，停止淘洗后自动失效
        if (player.tickCount % SLOW_REFRESH_INTERVAL_TICKS == 0) {
            player.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, SLOW_DURATION_TICKS,
                    SLOW_AMPLIFIER, false, false, false));
        }
    }

    // 长按完成一次淘洗：消耗淘洗点次数、消耗 1 点耐久并抽取一次产出
    @Override
    public @NotNull ItemStack finishUsingItem(@NotNull ItemStack stack, @NotNull Level level,
                                              @NotNull LivingEntity entity) {
        if (level instanceof ServerLevel serverLevel && entity instanceof ServerPlayer player) {
            ShimmerEntity target = findTargetedShimmer(player);
            if (target != null && target.consumePanUse()) {
                PanningLootService.grantPanningLoot(serverLevel, player, target.getAnchorPos(), stack);
                stack.hurtAndBreak(1, serverLevel, player, item -> {});
            }
        }
        return super.finishUsingItem(stack, level, entity);
    }

    // 准星命中判定：只有指向闪烁的光时才视为有效淘洗目标
    @Nullable
    private static ShimmerEntity findTargetedShimmer(Player player) {
        HitResult hitResult = ProjectileUtil.getHitResultOnViewVector(player,
                entity -> entity instanceof ShimmerEntity shimmer && !shimmer.isRemoved(),
                player.entityInteractionRange());
        if (hitResult instanceof EntityHitResult entityHitResult
                && entityHitResult.getEntity() instanceof ShimmerEntity shimmer) {
            return shimmer;
        }
        return null;
    }
}
