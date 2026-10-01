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
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Cat;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.Util;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/*** 参数窗口的坐姿三花猫：只在客户端预览，工具附着于头部骨骼并随鼠标转动。 */
public final class SimulationCatMascot {
    private static final ResourceLocation TEXTURE = ResourceLocation.withDefaultNamespace("textures/entity/cat/calico.png");
    private static final String[] PARTS = {"head", "body", "tail1", "tail2", "left_front_leg",
            "right_front_leg", "left_hind_leg", "right_hind_leg"};
    private final Cat cat;
    private final CatModel<Cat> model;
    private final ModelPart head;
    private final ModelPart root;
    private final ModelPart collarRoot;
    private final CatModel<Cat> collar;
    private static final ResourceLocation COLLAR = ResourceLocation.withDefaultNamespace("textures/entity/cat/cat_collar.png");
    private long petStarted = -1;
    private float headLeft, headTop, headRight, headBottom;

    public SimulationCatMascot(Minecraft minecraft) {
        cat = new Cat(EntityType.CAT, java.util.Objects.requireNonNull(minecraft.level));
        cat.setInSittingPose(true);
        root = minecraft.getEntityModels().bakeLayer(ModelLayers.CAT);
        model = new CatModel<>(root);
        model.young = false;
        collarRoot = minecraft.getEntityModels().bakeLayer(ModelLayers.CAT_COLLAR);
        collar = new CatModel<>(collarRoot);
        collar.young = false;
        head = root.getChild("head");
    }

    // 模型与虚拟猫仅创建一次；每帧只更新姿态，不加入世界、不推进实体 AI。
    public void render(GuiGraphics graphics, int centerX, int bottomY, int size,
                       int mouseX, int mouseY, ItemStack tool) {
        float yaw = (float) Math.atan((centerX - mouseX) / 70.0) * 28.0F;
        // xRot 正方向是低头：鼠标移到猫上方时头要抬起，故取负。
        float pitch = -(float) Math.atan((bottomY - size / 2.0F - mouseY) / 70.0) * 18.0F;
        model.prepareMobModel(cat, 0, 0, 0);
        model.setupAnim(cat, 0, 0, 0, yaw, pitch);
        float pet = petProgress();
        if (pet > 0 && pet < 1) {
            float envelope = (float) Math.sin(pet * Math.PI);
            head.zRot = (float) Math.sin(pet * Math.PI * 5) * 0.23F * envelope;
            head.xRot -= envelope * 0.18F;
            head.yRot *= 1 - envelope;
        }
        for (String part : PARTS) {
            collarRoot.getChild(part).copyFrom(root.getChild(part));
        }
        graphics.flush();
        PoseStack pose = graphics.pose();
        Matrix4f inverseParent = new Matrix4f(pose.last().pose()).invert();
        pose.pushPose();
        Lighting.setupForEntityInInventory();
        try {
            pose.translate(centerX, bottomY, 50);
            pose.scale(size, size, -size);
            pose.mulPose(Axis.YP.rotationDegrees(yaw * 0.35F));
            pose.translate(0, -1.5, 0);
            model.renderToBuffer(pose, graphics.bufferSource().getBuffer(RenderType.entityCutoutNoCull(TEXTURE)),
                    LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, -1);
            collar.renderToBuffer(pose, graphics.bufferSource().getBuffer(RenderType.entityCutoutNoCull(COLLAR)),
                    LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, 0xFF993F35);
            updateHeadBounds(pose, inverseParent);
            if (pet > 0.05F && pet < 0.93F) renderClosedEyes(graphics);
            if (!tool.isEmpty()) renderHeldTool(graphics, tool);
            graphics.flush();
        } finally {
            pose.popPose();
            Lighting.setupFor3DItems();
        }
    }

    // 命中区域取动画后头部立方体的投影，排除身体和旁边的参数列。
    private void updateHeadBounds(PoseStack pose, Matrix4f inverseParent) {
        pose.pushPose();
        head.translateAndRotate(pose);
        Matrix4f transform = inverseParent.mul(pose.last().pose());
        headLeft = headTop = Float.POSITIVE_INFINITY;
        headRight = headBottom = Float.NEGATIVE_INFINITY;
        for (float x : new float[]{-2.5F, 2.5F}) for (float y : new float[]{-3, 2})
            for (float z : new float[]{-4, 2}) {
                Vector3f point = transform.transformPosition(new Vector3f(x / 16, y / 16, z / 16));
                headLeft = Math.min(headLeft, point.x); headRight = Math.max(headRight, point.x);
                headTop = Math.min(headTop, point.y); headBottom = Math.max(headBottom, point.y);
            }
        pose.popPose();
    }

    public boolean isHeadHovered(double x, double y) {
        return x >= headLeft && x < headRight && y >= headTop && y < headBottom;
    }

    public boolean pet(double x, double y) {
        if (!isHeadHovered(x, y)) return false;
        petStarted = Util.getMillis();
        // 原版 ambient 事件自行随机抽取猫叫变体；仅交给当前客户端，不向世界广播。
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(
                SoundEvents.CAT_AMBIENT, 0.95F + cat.getRandom().nextFloat() * 0.1F));
        return true;
    }

    private float petProgress() {
        return petStarted < 0 ? 1 : Math.min(1, (Util.getMillis() - petStarted) / 1250.0F);
    }

    // 眼睑直接附着在头部正面，四倍局部坐标用于绘制细闭眼线，不新增贴图或实体。
    private void renderClosedEyes(GuiGraphics graphics) {
        graphics.flush();
        PoseStack pose = graphics.pose();
        pose.pushPose();
        head.translateAndRotate(pose);
        pose.translate(0, 0, -3.025F / 16);
        pose.scale(1 / 64.0F, 1 / 64.0F, 1 / 64.0F);
        graphics.fill(-10, -4, -2, 0, 0xFF423B32);
        graphics.fill(2, -4, 10, 0, 0xFFE5BE84);
        graphics.fill(-9, -2, -3, -1, 0xFF29251F);
        graphics.fill(3, -2, 9, -1, 0xFF514635);
        graphics.flush();
        pose.popPose();
    }

    // 工具挂在 head 骨骼上，随头部一起转动。translateAndRotate 之后坐标系单位是 block
    // （它把 pivot 除以 16），口鼻立方体在头部局部 y∈[0,2]、z∈[-4,-2] 模型像素处，
    // 即 block 空间的 y≈0.12、z≈-0.25——咬点取在鼻下前缘。
    // 用 NONE 显示上下文：GROUND 的 translation/scale 会把物品推离该点。
    private void renderHeldTool(GuiGraphics graphics, ItemStack tool) {
        PoseStack pose = graphics.pose();
        pose.pushPose();
        try {
            head.translateAndRotate(pose);
            pose.translate(0.0F, 1.8F / 16, -4.3F / 16);
            pose.mulPose(Axis.XP.rotationDegrees(180));
            // 把贴图左下到右上的柄轴转成水平，略向前倾以露出物品厚度。
            pose.mulPose(Axis.YP.rotationDegrees(-12));
            pose.mulPose(Axis.ZP.rotationDegrees(-45));
            pose.scale(0.38F, 0.38F, 0.38F);
            // 常规手持工具的柄位于贴图左下，咬住柄而非将整幅贴图中心放在鼻子上。
            pose.translate(0.25F, 0.25F, 0);
            Minecraft.getInstance().getItemRenderer().renderStatic(tool, ItemDisplayContext.NONE,
                    LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, pose, graphics.bufferSource(), cat.level(), 0);
        } finally { pose.popPose(); }
    }
}
