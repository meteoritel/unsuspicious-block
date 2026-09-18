package com.meteorite.unsuspiciousblock.item;

import com.meteorite.unsuspiciousblock.client.tooltip.TooltipBuilder;
import com.meteorite.unsuspiciousblock.entity.ShimmerEntity;
import com.meteorite.unsuspiciousblock.pan.PanningLootService;
import com.meteorite.unsuspiciousblock.pan.ShimmerSpawnService;
import com.meteorite.unsuspiciousblock.platform.Services;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/***
 * 淘盘基类——全部淘洗工具共用长按淘洗流程，差异全部由 {@link PanProfile} 提供。
 * <p>
 * 使用流程采用长按淘洗机制：对准淘洗点按住右键，达到配置时长后完成一次淘洗，
 * 消耗 1 点耐久并抽取一次该点的产出；准星离开目标、提前松手或目标变体不被本工具支持时立即中断，
 * 中断不消耗任何东西。按住期间的移动迟缓与无法冲刺直接复用原版「正在使用物品」的表现——客户端
 * {@code LocalPlayer.aiStep} 会对移动输入统一按 0.2 缩放并重置冲刺，与进食、拉弓完全一致。
 * <p>
 * 工具本身就是「是淘盘」的判据，可采目标由档案与点的变体求交集，因此新增淘盘无需改动实体侧代码。
 */
public class PanItem extends Item {
    private final PanProfile profile;

    public PanItem(Properties properties, PanProfile profile) {
        super(properties);
        this.profile = profile;
    }

    public PanProfile profile() {
        return this.profile;
    }

    // 引导文案按物品注册名派生，新增淘盘无需改动代码，只需补语言键；品质暗示行仅在档案登记时追加
    @Override
    public void appendHoverText(@NotNull ItemStack stack, @NotNull TooltipContext context,
                                @NotNull List<Component> tooltipLines, @NotNull TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltipLines, flag);
        TooltipBuilder builder = new TooltipBuilder(tooltipLines);
        builder.intro(this.getDescriptionId() + ".tooltip.use");
        if (this.profile.qualityTooltipKey() != null) {
            builder.intro(this.profile.qualityTooltipKey());
        }
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

    // 铁砧修复材料由档案指定
    @Override
    public boolean isValidRepairItem(@NotNull ItemStack stack, @NotNull ItemStack repairCandidate) {
        return repairCandidate.is(this.profile.repairTag()) || super.isValidRepairItem(stack, repairCandidate);
    }

    // 每 tick 校验准星是否仍指向本工具可采的淘洗点；离开或变体不受支持即中断，不消耗耐久也不产出战利品
    // 移动迟缓不在这里处理：只要处于「正在使用物品」状态，客户端即按原版规则自行减速
    @Override
    public void onUseTick(@NotNull Level level, @NotNull LivingEntity user, @NotNull ItemStack stack,
                          int remainingUseDuration) {
        if (!(user instanceof Player player)) {
            user.releaseUsingItem();
            return;
        }
        ShimmerEntity target = findTargetedShimmer(player);
        if (target == null || !this.canHarvest(target)) {
            player.releaseUsingItem();
            return;
        }
        if (!level.isClientSide()) {
            target.markPanning(player);
        }
    }

    // 长按完成一次淘洗：消耗淘洗点次数、消耗 1 点耐久并抽取一次该点的产出
    @Override
    public @NotNull ItemStack finishUsingItem(@NotNull ItemStack stack, @NotNull Level level,
                                              @NotNull LivingEntity entity) {
        if (level instanceof ServerLevel serverLevel && entity instanceof ServerPlayer player) {
            ShimmerEntity target = findTargetedShimmer(player);
            if (target != null && this.canHarvest(target) && target.consumePanUse()) {
                PanningLootService.grantPanningLoot(serverLevel, player, target.getAnchorPos(),
                        target.getVariant(), stack);
                if (target.isNaturalSpawn()) {
                    ShimmerSpawnService.tryRegenerateAfterHarvest(serverLevel, target,
                            this.profile.regenerationChance().getAsDouble());
                }
                stack.hurtAndBreak(1, serverLevel, player, item -> {});
            }
        }
        return super.finishUsingItem(stack, level, entity);
    }

    // 工具与点的能力交集：工具可采该点的变体，且该点当前仍处于可淘洗状态
    public boolean canHarvest(ShimmerEntity target) {
        return this.profile.supports(target.getVariant().id()) && target.canPan();
    }

    // 准星命中判定：只有指向闪烁的光时才视为有效淘洗目标
    @Nullable
    public static ShimmerEntity findTargetedShimmer(Player player) {
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
