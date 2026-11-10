package com.meteorite.unsuspiciousblock.client.renderer;

import com.meteorite.unsuspiciousblock.core.entity.MerchantCat;
import net.minecraft.client.model.CatModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.util.FastColor;
import org.jetbrains.annotations.NotNull;

/** 猫猫商人的占位灵体渲染器。 */
public class MerchantCatRenderer extends SpiritCatRenderer<MerchantCat> {
    private static final int SPIRIT_TINT = FastColor.ARGB32.color(190, 224, 245, 255);

    public MerchantCatRenderer(EntityRendererProvider.Context context) {
        super(context, new CatModel<>(context.bakeLayer(ModelLayers.CAT)), 0.0F);
    }

    @Override
    protected RenderType spiritRenderType(@NotNull MerchantCat entity) {
        return RenderType.entityTranslucent(getTextureLocation(entity));
    }

    @Override
    protected int spiritTint() {
        return SPIRIT_TINT;
    }

    @Override
    protected float modelScale() {
        return 0.9F;
    }
}
