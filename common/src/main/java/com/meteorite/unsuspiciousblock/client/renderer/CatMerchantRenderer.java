package com.meteorite.unsuspiciousblock.client.renderer;

import com.meteorite.unsuspiciousblock.entity.MerchantCat;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.CatModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

/**
 * 猫猫商人占位渲染器——暂用原版猫模型，职业装饰由后续正式模型替换。
 */
public class CatMerchantRenderer extends MobRenderer<MerchantCat, CatModel<MerchantCat>> {
    public CatMerchantRenderer(EntityRendererProvider.Context context) {
        super(context, new CatModel<>(context.bakeLayer(ModelLayers.CAT)), 0.0F);
    }

    @Override
    public @NotNull ResourceLocation getTextureLocation(MerchantCat entity) {
        return entity.getTextureId();
    }

    @Override
    protected RenderType getRenderType(@NotNull MerchantCat entity, boolean bodyVisible,
                                       boolean translucent, boolean outline) {
        return RenderType.entityTranslucent(this.getTextureLocation(entity));
    }

    @Override
    protected void scale(@NotNull MerchantCat entity, @NotNull PoseStack poseStack, float partialTick) {
        poseStack.scale(0.9F, 0.9F, 0.9F);
    }

    @Override
    protected float getShadowRadius(@NotNull MerchantCat entity) {
        return 0.0F;
    }
}
