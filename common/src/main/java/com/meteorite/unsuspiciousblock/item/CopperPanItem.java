package com.meteorite.unsuspiciousblock.item;

import com.meteorite.unsuspiciousblock.entity.ShimmerEntity;
import com.meteorite.unsuspiciousblock.pan.PanningLootService;
import com.meteorite.unsuspiciousblock.platform.Services;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
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
 * 使用流程采用长按淘洗机制：对准闪烁的光按住右键，达到配置时长后完成一次淘洗，
 * 消耗 1 点耐久并抽取一次淘洗战利品；准星离开目标或提前松手会立即中断，中断不消耗任何东西。
 * 按住期间的移动迟缓与无法冲刺直接复用原版「正在使用物品」的表现——客户端
 * {@code LocalPlayer.aiStep} 会对移动输入统一按 0.2 缩放并重置冲刺，与进食、拉弓完全一致，
 * 因此本物品不施加任何药水效果。对普通水体、其它实体或空气使用不会产生任何效果。
 * <p>
 * 耐久 32，可在铁砧上用铜锭修复，并兼容耐久、经验修补等通用附魔。
 */
public class CopperPanItem extends Item {

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

    // 原版不施加使用动画，客户端渲染入口负责专属摇洗动作。
    @Override
    public @NotNull UseAnim getUseAnimation(@NotNull ItemStack stack) {
        return UseAnim.NONE;
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
    // 移动迟缓不在这里处理：只要处于「正在使用物品」状态，客户端即按原版规则自行减速
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
        if (!level.isClientSide()) {
            target.markPanning();
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
