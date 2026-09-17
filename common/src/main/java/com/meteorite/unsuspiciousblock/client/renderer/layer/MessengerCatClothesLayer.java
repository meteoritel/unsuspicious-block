package com.meteorite.unsuspiciousblock.client.renderer.layer;

import com.meteorite.unsuspiciousblock.client.model.MessengerCatClothesModel;
import com.meteorite.unsuspiciousblock.entity.MessengerCat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.CatModel;
import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

/**
 * 猫猫信使职业装饰层 —— 邮差帽与邮包
 * <p>
 * 装饰模型与本渲染器的原版 CatModel 共享同一份烘焙骨骼实例：层持有原版猫根部件，
 * 每帧把 head / body 的姿态拷贝到装饰模型上，故原版猫行走、坐下、躺卧等动画自动生效，
 * 无需在装饰模型内复刻任何动画逻辑。
 */
public class MessengerCatClothesLayer extends RenderLayer<MessengerCat, CatModel<MessengerCat>> {

    private static final ResourceLocation CLOTHES_TEXTURE =
            ResourceLocation.fromNamespaceAndPath("unsuspiciousblock", "textures/entity/cat/messenger_cat_clothes.png");

    private final MessengerCatClothesModel clothesModel;
    private final ModelPart catHead;
    private final ModelPart catBody;

    // catRoot 须为本渲染器 CatModel 使用的同一份根部件实例，否则拿不到动画后的姿态
    public MessengerCatClothesLayer(RenderLayerParent<MessengerCat, CatModel<MessengerCat>> parent,
                                    EntityModelSet modelSet, ModelPart catRoot) {
        super(parent);
        this.clothesModel = new MessengerCatClothesModel(modelSet.bakeLayer(MessengerCatClothesModel.LAYER_LOCATION));
        this.catHead = catRoot.getChild("head");
        this.catBody = catRoot.getChild("body");
    }

    @Override
    public void render(@NotNull PoseStack poseStack, @NotNull MultiBufferSource bufferSource, int packedLight,
                       @NotNull MessengerCat entity, float limbSwing, float limbSwingAmount,
                       float partialTick, float ageInTicks, float netHeadYaw, float headPitch) {
        if (entity.isInvisible()) {
            return;
        }
        // 与原版猫渲染器同用自发光半透明类型，装饰与灵体本体观感一致；
        // 顶点色与 alpha 由渲染器的 GhostlyBufferSource 统一施加
        this.clothesModel.syncPose(this.catHead, this.catBody);
        VertexConsumer consumer = bufferSource.getBuffer(RenderType.entityTranslucentEmissive(CLOTHES_TEXTURE));
        this.clothesModel.renderToBuffer(poseStack, consumer, packedLight,
                LivingEntityRenderer.getOverlayCoords(entity, 0.0F), -1);
    }
}
