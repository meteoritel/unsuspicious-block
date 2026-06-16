package com.meteorite.unsuspiciousblock.client.renderer.layer;

import com.meteorite.unsuspiciousblock.entity.GhostCat;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.CatModel;
import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

/**
 * 幽灵猫项圈层 —— 复刻原版 CatCollarLayer，适配 GhostCat 泛型
 */
public class GhostCatCollarLayer extends RenderLayer<GhostCat, CatModel<GhostCat>> {

    private static final ResourceLocation CAT_COLLAR_LOCATION = ResourceLocation.withDefaultNamespace("textures/entity/cat/cat_collar.png");
    private final CatModel<GhostCat> collarModel;

    public GhostCatCollarLayer(RenderLayerParent<GhostCat, CatModel<GhostCat>> parent, EntityModelSet modelSet) {
        super(parent);
        this.collarModel = new CatModel<>(modelSet.bakeLayer(ModelLayers.CAT_COLLAR));
    }

    @Override
    public void render(@NotNull PoseStack poseStack, @NotNull MultiBufferSource bufferSource, int packedLight,
                       GhostCat entity, float limbSwing, float limbSwingAmount,
                       float partialTick, float ageInTicks, float netHeadYaw, float headPitch) {
        if (entity.isTame()) {
            int collarColor = entity.getCollarColor().getTextureDiffuseColor();
            coloredCutoutModelCopyLayerRender(
                    this.getParentModel(),
                    this.collarModel,
                    CAT_COLLAR_LOCATION,
                    poseStack,
                    bufferSource,
                    packedLight,
                    entity,
                    limbSwing,
                    limbSwingAmount,
                    ageInTicks,
                    netHeadYaw,
                    headPitch,
                    partialTick,
                    collarColor
            );
        }
    }
}
