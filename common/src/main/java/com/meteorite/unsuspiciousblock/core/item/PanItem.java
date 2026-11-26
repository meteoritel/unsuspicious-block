package com.meteorite.unsuspiciousblock.core.item;

import com.meteorite.unsuspiciousblock.core.text.TooltipBuilder;
import com.meteorite.unsuspiciousblock.core.entity.ShimmerEntity;
import com.meteorite.unsuspiciousblock.core.pan.PanningLootService;
import com.meteorite.unsuspiciousblock.core.pan.PanningSession;
import com.meteorite.unsuspiciousblock.platform.services.IPanningConfig;
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

    // 引导文案按物品注册名派生，新增淘盘无需改动代码，只需补语言键；品质特性行按档案语义色逐条追加
    @Override
    public void appendHoverText(@NotNull ItemStack stack, @NotNull TooltipContext context,
                                @NotNull List<Component> tooltipLines, @NotNull TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltipLines, flag);
        TooltipBuilder builder = new TooltipBuilder(tooltipLines);
        builder.intro(this.getDescriptionId() + ".tooltip.use");
        for (PanProfile.QualityNote note : this.profile.qualityNotes()) {
            builder.wrapped(Component.translatable(note.key()).withStyle(note.tone().color()));
        }
    }

    // 原版不施加使用动画，客户端渲染入口负责专属摇洗动作。
    @Override
    public @NotNull UseAnim getUseAnimation(@NotNull ItemStack stack) {
        return UseAnim.NONE;
    }

    @Override
    public int getUseDuration(@NotNull ItemStack stack, @NotNull LivingEntity entity) {
        if (entity instanceof ServerPlayer player) {
            PanningSession session = PanningSession.of(player);
            if (session != null) {
                return session.duration();
            }
        }
        return IPanningConfig.DEFAULT_PAN_DURATION_TICKS;
    }

    // 铁砧修复材料由档案指定
    @Override
    public boolean isValidRepairItem(@NotNull ItemStack stack, @NotNull ItemStack repairCandidate) {
        return repairCandidate.is(this.profile.repairTag()) || super.isValidRepairItem(stack, repairCandidate);
    }

    @Override
    public void onUseTick(@NotNull Level level, @NotNull LivingEntity user, @NotNull ItemStack stack,
                          int remainingUseDuration) {
        if (user instanceof ServerPlayer player) {
            PanningSession session = PanningSession.of(player);
            if (session == null || !session.matches(player, stack)) {
                player.releaseUsingItem();
            }
        }
    }

    // 长按完成一次淘洗：消耗淘洗点次数、消耗 1 点耐久并抽取一次该点的产出
    @Override
    public @NotNull ItemStack finishUsingItem(@NotNull ItemStack stack, @NotNull Level level,
                                              @NotNull LivingEntity entity) {
        if (level instanceof ServerLevel serverLevel && entity instanceof ServerPlayer player) {
            PanningSession session = PanningSession.of(player);
            if (session != null && session.matches(player, stack)) {
                PanningLootService.harvest(serverLevel, player, session, this.profile);
            }
        }
        return super.finishUsingItem(stack, level, entity);
    }

    /** 返回原版方块遮挡范围内命中的淘洗点。 */
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
