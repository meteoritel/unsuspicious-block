package com.meteorite.unsuspiciousblock.client.model;

import com.meteorite.unsuspiciousblock.entity.MessengerCat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

/**
 * 猫猫信使职业装饰模型 —— 邮差帽与邮包，贴图 64x64
 * <p>
 * 仅含 head / body 两根骨骼，枢轴与原版 CatModel 完全对齐（head 位于 (0,15,-9)，
 * body 位于 (0,12,-10) 并绕 X 轴旋转 90°），因此模型自身不做任何动画，
 * 而是每帧由 syncPose 从原版猫骨骼拷贝姿态，从而自动兼容行走、坐下、躺卧等全部原版猫动画。
 */
public class MessengerCatClothesModel extends EntityModel<MessengerCat> {

    // 模型层注册位置：mod 命名空间 + "main" 层名
    public static final ModelLayerLocation LAYER_LOCATION =
            new ModelLayerLocation(
                    ResourceLocation.fromNamespaceAndPath("unsuspiciousblock", "messenger_cat_clothes"),
                    "main");

    private final ModelPart head;
    private final ModelPart body;

    public MessengerCatClothesModel(ModelPart root) {
        super(RenderType::entityCutoutNoCull);
        this.head = root.getChild("head");
        this.body = root.getChild("body");
    }

    // 构建装饰模型层级与 UV 映射
    public static LayerDefinition createBodyLayer() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition partdefinition = mesh.getRoot();

        // 邮差帽：帽檐、帽冠、后脑翻边与左侧羽毛
        partdefinition.addOrReplaceChild("head", CubeListBuilder.create()
                .texOffs(1, 11).addBox(-2.7F, -2.3F, -3.1F, 5.4F, 0.3F, 2.9F)
                .texOffs(38, 1).addBox(-2.8F, -2.9F, -3.0F, 5.6F, 0.6F, 2.8F)
                .texOffs(45, 11).addBox(-2.4F, -3.2F, -2.6F, 4.8F, 0.3F, 2.4F)
                .texOffs(19, 11).addBox(-0.8F, -2.7F, -0.2F, 1.6F, 0.6F, 2.6F)
                .texOffs(41, 20).addBox(-2.7F, -2.5F, 2.2F, 5.4F, 0.5F, 0.3F)
                .texOffs(31, 11).addBox(2.2F, -2.6F, -0.2F, 0.6F, 0.5F, 2.4F)
                .texOffs(38, 11).addBox(-2.8F, -2.6F, -0.2F, 0.6F, 0.5F, 2.4F)
                .texOffs(42, 16).addBox(-2.5F, -2.2F, -4.1F, 5.0F, 0.2F, 1.2F)
                .texOffs(1, 22).addBox(-2.0F, -2.2F, -4.4F, 4.0F, 0.2F, 0.4F)
                .texOffs(54, 20).addBox(-0.3F, -3.4F, -1.5F, 0.6F, 0.2F, 0.5F)
                .texOffs(11, 22).addBox(-0.5F, -2.9F, -3.1F, 1.0F, 0.5F, 0.1F)
                .texOffs(17, 22).addBox(-0.3F, -2.8F, -3.2F, 0.6F, 0.3F, 0.1F)
                .texOffs(29, 11).addBox(-2.6F, -5.4F, -1.0F, 0.2F, 2.8F, 0.2F)
                .texOffs(4, 20).addBox(-3.1F, -4.1F, -1.0F, 0.6F, 0.8F, 0.2F)
                .texOffs(60, 16).addBox(-3.4F, -4.8F, -1.0F, 0.7F, 0.8F, 0.2F)
                .texOffs(1, 20).addBox(-3.6F, -5.5F, -1.0F, 0.6F, 0.8F, 0.2F)
                .texOffs(39, 20).addBox(-3.5F, -6.1F, -1.0F, 0.3F, 0.7F, 0.2F)
                .texOffs(58, 20).addBox(-2.8F, -3.1F, -1.1F, 0.4F, 0.5F, 0.2F),
                PartPose.offset(0.0F, 15.0F, -9.0F));

        // 邮包：斜挎背带、右侧封条与左侧挎包
        partdefinition.addOrReplaceChild("body", CubeListBuilder.create()
                .texOffs(33, 20).addBox(1.4F, 7.4F, -2.0F, 0.8F, 0.7F, 0.2F)
                .texOffs(36, 20).addBox(0.8F, 7.9F, -2.0F, 0.8F, 0.7F, 0.2F)
                .texOffs(18, 20).addBox(0.2F, 8.4F, -2.0F, 0.8F, 0.7F, 0.2F)
                .texOffs(21, 20).addBox(-0.4F, 8.9F, -2.0F, 0.8F, 0.7F, 0.2F)
                .texOffs(24, 20).addBox(-1.0F, 9.4F, -2.0F, 0.8F, 0.7F, 0.2F)
                .texOffs(27, 20).addBox(-1.6F, 9.9F, -2.0F, 0.8F, 0.7F, 0.2F)
                .texOffs(30, 20).addBox(-2.2F, 10.4F, -2.0F, 0.8F, 0.7F, 0.2F)
                .texOffs(31, 16).addBox(2.0F, 8.6F, -8.0F, 0.2F, 0.7F, 0.9F)
                .texOffs(27, 16).addBox(2.0F, 8.4F, -7.2F, 0.2F, 0.7F, 0.9F)
                .texOffs(35, 16).addBox(2.0F, 8.2F, -6.4F, 0.2F, 0.7F, 0.9F)
                .texOffs(15, 16).addBox(2.0F, 8.0F, -5.6F, 0.2F, 0.7F, 0.9F)
                .texOffs(19, 16).addBox(2.0F, 7.8F, -4.8F, 0.2F, 0.7F, 0.9F)
                .texOffs(23, 16).addBox(2.0F, 7.6F, -4.0F, 0.2F, 0.7F, 0.9F)
                .texOffs(7, 16).addBox(2.0F, 7.4F, -3.2F, 0.2F, 0.7F, 1.4F)
                .texOffs(7, 20).addBox(-2.3F, 8.6F, -8.1F, 4.5F, 0.7F, 0.2F)
                .texOffs(56, 16).addBox(-2.3F, 9.0F, -8.1F, 0.3F, 0.4F, 0.7F)
                .texOffs(61, 11).addBox(-2.3F, 9.2F, -7.8F, 0.3F, 1.7F, 0.4F)
                .texOffs(29, 1).addBox(-2.3F, 10.7F, -7.8F, 0.3F, 0.8F, 3.5F)
                .texOffs(56, 1).addBox(-2.3F, 10.7F, -4.4F, 0.3F, 0.8F, 2.6F)
                .texOffs(1, 1).addBox(-3.6F, 7.6F, -7.5F, 1.4F, 4.8F, 3.8F)
                .texOffs(19, 1).addBox(-3.7F, 7.5F, -7.6F, 1.5F, 5.0F, 0.4F)
                .texOffs(13, 1).addBox(-3.9F, 7.4F, -5.3F, 0.3F, 5.2F, 1.7F)
                .texOffs(24, 1).addBox(-3.9F, 7.5F, -3.7F, 1.8F, 5.1F, 0.2F)
                .texOffs(1, 16).addBox(-4.0F, 9.7F, -6.2F, 0.1F, 0.6F, 2.0F)
                .texOffs(39, 16).addBox(-4.1F, 9.6F, -5.8F, 0.1F, 0.8F, 0.6F)
                .texOffs(61, 20).addBox(-4.2F, 9.8F, -5.7F, 0.1F, 0.4F, 0.3F)
                .texOffs(12, 16).addBox(-4.0F, 11.0F, -4.9F, 0.1F, 1.1F, 0.8F)
                .texOffs(15, 22).addBox(-4.1F, 11.4F, -4.7F, 0.1F, 0.3F, 0.3F),
                PartPose.offsetAndRotation(0.0F, 12.0F, -10.0F, (float) (Math.PI / 2), 0.0F, 0.0F));

        return LayerDefinition.create(mesh, 64, 64);
    }

    // 从原版猫骨骼拷贝 head / body 的位置、旋转与缩放，使装饰跟随原版猫的全部动画
    public void syncPose(ModelPart catHead, ModelPart catBody) {
        this.head.copyFrom(catHead);
        this.body.copyFrom(catBody);
        this.head.visible = catHead.visible;
        this.body.visible = catBody.visible;
    }

    @Override
    public void setupAnim(@NotNull MessengerCat entity, float limbSwing, float limbSwingAmount,
                          float ageInTicks, float netHeadYaw, float headPitch) {
        // 姿态完全由原版猫骨骼驱动，装饰模型自身不做动画
    }

    @Override
    public void renderToBuffer(@NotNull PoseStack poseStack, @NotNull VertexConsumer vertexConsumer,
                               int packedLight, int packedOverlay, int color) {
        this.head.render(poseStack, vertexConsumer, packedLight, packedOverlay, color);
        this.body.render(poseStack, vertexConsumer, packedLight, packedOverlay, color);
    }
}
