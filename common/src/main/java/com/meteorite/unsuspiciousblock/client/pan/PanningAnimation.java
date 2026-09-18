package com.meteorite.unsuspiciousblock.client.pan;

import com.meteorite.unsuspiciousblock.item.PanItem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/*** 淘盘演出：下探装水、抬盘，再随水面帧左右摇洗；两端共用并支持左右手。 */
public final class PanningAnimation {
    private PanningAnimation() {
    }

    // 返回是否接管本次持物渲染；仅影响正在使用的淘盘。
    public static boolean renderFirstPerson(ItemInHandRenderer renderer, AbstractClientPlayer player,
            InteractionHand hand, ItemStack stack, float partialTick, float equipProgress,
            PoseStack pose, MultiBufferSource buffers, int light) {
        if (player == null || player.isScoping() || !player.isUsingItem()
                || player.getUsedItemHand() != hand || !(stack.getItem() instanceof PanItem)) {
            return false;
        }
        HumanoidArm arm = hand == InteractionHand.MAIN_HAND ? player.getMainArm() : player.getMainArm().getOpposite();
        int side = arm == HumanoidArm.RIGHT ? 1 : -1;
        float elapsed = player.getTicksUsingItem() + partialTick;
        float blend = PanningVisuals.smooth(elapsed / PanningVisuals.fillTicks(player));
        float scoop = Mth.sin(blend * Mth.PI);
        float wash = PanningVisuals.washBlend(player, elapsed);
        float phase = PanningVisuals.phase(player, elapsed);
        float sway = Mth.sin(phase) * wash;
        float dip = Mth.sin(phase * 2.0F) * wash;
        pose.pushPose();
        try {
            pose.translate(side * (0.56F - blend * 0.18F + sway * 0.09F),
                    -0.52F - equipProgress * 0.6F + blend * 0.12F - scoop * 0.10F + dip * 0.018F,
                    -0.72F - blend * 0.1F - scoop * 0.08F + dip * 0.025F);
            pose.mulPose(Axis.XP.rotationDegrees(blend * -62.0F - scoop * 10.0F + dip * 3.0F));
            pose.mulPose(Axis.YP.rotationDegrees(side * sway * 5.0F));
            pose.mulPose(Axis.ZP.rotationDegrees(side * (sway * 5.0F + dip)));
            // 使用模型原始盘面，避免 FIRST_PERSON 自带的侧转与摇洗旋转叠加成竖盘。
            // generated 模型盘面位于 XY 平面，绕 X 轴前倾后形成横向托盘姿态。
            pose.scale(0.75F, 0.75F, 0.75F);
            renderer.renderItem(player, stack, ItemDisplayContext.NONE, false, pose, buffers, light);
        } finally {
            pose.popPose();
        }
        return true;
    }

    // 在原版模型完成姿势后调整持盘手臂；非玩家模型与另一只手保持原版行为。
    public static void poseThirdPerson(HumanoidModel<?> model, LivingEntity entity, float ageInTicks) {
        if (!(entity instanceof Player) || !entity.isUsingItem()
                || !(entity.getUseItem().getItem() instanceof PanItem)) {
            return;
        }
        HumanoidArm arm = entity.getUsedItemHand() == InteractionHand.MAIN_HAND
                ? entity.getMainArm() : entity.getMainArm().getOpposite();
        ModelPart part = arm == HumanoidArm.RIGHT ? model.rightArm : model.leftArm;
        int side = arm == HumanoidArm.RIGHT ? 1 : -1;
        float elapsed = entity.getTicksUsingItem() + ageInTicks - entity.tickCount;
        float blend = PanningVisuals.smooth(elapsed / PanningVisuals.fillTicks(entity));
        float phase = PanningVisuals.phase(entity, elapsed);
        float wash = PanningVisuals.washBlend(entity, elapsed);
        part.xRot = Mth.lerp(blend, -0.55F, -0.9F) + Mth.sin(phase * 2.0F) * wash * 0.06F;
        part.yRot = -side * (0.22F - Mth.sin(phase) * wash * 0.14F);
        part.zRot = side * (0.08F + Mth.sin(phase) * wash * 0.07F);
    }
}
