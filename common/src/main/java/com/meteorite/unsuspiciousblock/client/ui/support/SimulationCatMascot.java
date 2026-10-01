package com.meteorite.unsuspiciousblock.client.ui.support;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.model.CatModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Cat;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/*** 参数窗口的坐姿三花猫：只在客户端预览，工具附着于头部骨骼并随鼠标转动。 */
public final class SimulationCatMascot {
    private static final ResourceLocation TEXTURE = ResourceLocation.withDefaultNamespace("textures/entity/cat/calico.png");
    private final Cat cat;
    private final CatModel<Cat> model;
    private final ModelPart head;

    public SimulationCatMascot(Minecraft minecraft) {
        cat = new Cat(EntityType.CAT, java.util.Objects.requireNonNull(minecraft.level));
        cat.setInSittingPose(true);
        ModelPart root = minecraft.getEntityModels().bakeLayer(ModelLayers.CAT);
        model = new CatModel<>(root);
        head = root.getChild("head");
    }

    // 模型与虚拟猫仅创建一次；每帧只更新姿态，不加入世界、不推进实体 AI。
    public void render(GuiGraphics graphics, int centerX, int bottomY, int size,
                       int mouseX, int mouseY, ItemStack tool) {
        float yaw = (float) Math.atan((centerX - mouseX) / 70.0) * 28.0F;
        float pitch = (float) Math.atan((bottomY - size / 2.0F - mouseY) / 70.0) * 18.0F;
        model.prepareMobModel(cat, 0, 0, 0);
        model.setupAnim(cat, 0, 0, 0, yaw, pitch);
        graphics.flush();
        PoseStack pose = graphics.pose();
        pose.pushPose();
        Lighting.setupForEntityInInventory();
        try {
            pose.translate(centerX, bottomY, 50);
            pose.scale(size, size, -size);
            pose.mulPose(Axis.YP.rotationDegrees(yaw * 0.35F));
            pose.translate(0, -1.5, 0);
            model.renderToBuffer(pose, graphics.bufferSource().getBuffer(RenderType.entityCutoutNoCull(TEXTURE)),
                    LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, -1);
            if (!tool.isEmpty()) renderHeldTool(graphics, tool);
            graphics.flush();
        } finally {
            pose.popPose();
            Lighting.setupFor3DItems();
        }
    }

    private void renderHeldTool(GuiGraphics graphics, ItemStack tool) {
        PoseStack pose = graphics.pose();
        pose.pushPose();
        try {
            head.translateAndRotate(pose);
            pose.translate(0.04, 0.10, -0.30);
            pose.mulPose(Axis.XP.rotationDegrees(180));
            pose.mulPose(Axis.ZP.rotationDegrees(-35));
            pose.scale(0.75F, 0.75F, 0.75F);
            Minecraft.getInstance().getItemRenderer().renderStatic(tool, ItemDisplayContext.GROUND,
                    LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, pose, graphics.bufferSource(), cat.level(), 0);
        } finally { pose.popPose(); }
    }
}
