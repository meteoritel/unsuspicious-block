package com.meteorite.unsuspiciousblock.client.pan;

import com.meteorite.unsuspiciousblock.item.CopperPanItem;
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

/*** 淘盘摇洗动画：盘面前倾，水平绕圈并轻微抖动；两端共用并支持左右手。 */
public final class PanningAnimation {
    private PanningAnimation() {
    }

    // 返回是否接管本次持物渲染；仅影响正在使用的淘盘。
    public static boolean renderFirstPerson(ItemInHandRenderer renderer, AbstractClientPlayer player,
            InteractionHand hand, ItemStack stack, float partialTick, float equipProgress,
            PoseStack pose, MultiBufferSource buffers, int light) {
        if (player == null || player.isScoping() || !player.isUsingItem()
                || player.getUsedItemHand() != hand || !(stack.getItem() instanceof CopperPanItem)) {
            return false;
        }
        HumanoidArm arm = hand == InteractionHand.MAIN_HAND ? player.getMainArm() : player.getMainArm().getOpposite();
        int side = arm == HumanoidArm.RIGHT ? 1 : -1;
        float elapsed = player.getTicksUsingItem() + partialTick;
        float blend = Mth.clamp(elapsed / 6.0F, 0.0F, 1.0F);
        float phase = elapsed * Mth.TWO_PI / 20.0F;
        float sway = Mth.sin(phase);
        float dip = Mth.cos(phase);
        pose.pushPose();
        try {
            pose.translate(side * (0.56F - blend * 0.18F + blend * sway * 0.09F),
                    -0.52F - equipProgress * 0.6F + blend * (0.12F + dip * 0.025F),
                    -0.72F - blend * (0.1F + dip * 0.035F));
            pose.mulPose(Axis.XP.rotationDegrees(blend * (-62.0F + dip * 4.0F)));
            pose.mulPose(Axis.YP.rotationDegrees(side * blend * sway * 5.0F));
            pose.mulPose(Axis.ZP.rotationDegrees(side * blend * (sway * 4.0F + Mth.sin(phase * 2))));
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
                || !(entity.getUseItem().getItem() instanceof CopperPanItem)) {
            return;
        }
        HumanoidArm arm = entity.getUsedItemHand() == InteractionHand.MAIN_HAND
                ? entity.getMainArm() : entity.getMainArm().getOpposite();
        ModelPart part = arm == HumanoidArm.RIGHT ? model.rightArm : model.leftArm;
        int side = arm == HumanoidArm.RIGHT ? 1 : -1;
        float phase = (entity.getTicksUsingItem() + ageInTicks - entity.tickCount) * Mth.TWO_PI / 20.0F;
        part.xRot = -0.9F + Mth.cos(phase) * 0.08F;
        part.yRot = -side * 0.22F + Mth.sin(phase) * 0.14F;
        part.zRot = side * (0.08F + Mth.sin(phase) * 0.07F);
    }
}
