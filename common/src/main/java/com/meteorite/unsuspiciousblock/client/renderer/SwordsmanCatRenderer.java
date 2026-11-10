package com.meteorite.unsuspiciousblock.client.renderer;

import com.meteorite.unsuspiciousblock.core.entity.SwordsmanCat;
import net.minecraft.client.model.CatModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.util.FastColor;
import org.jetbrains.annotations.NotNull;

/** 剑士猫的占位灵体渲染器。 */
public class SwordsmanCatRenderer extends SpiritCatRenderer<SwordsmanCat> {
    private static final int SPIRIT_TINT = FastColor.ARGB32.color(190, 220, 245, 255);

    public SwordsmanCatRenderer(EntityRendererProvider.Context context) {
        super(context, new CatModel<>(context.bakeLayer(ModelLayers.CAT)), 0.0F);
    }

    @Override
    protected RenderType spiritRenderType(@NotNull SwordsmanCat entity) {
        return RenderType.entityTranslucentEmissive(getTextureLocation(entity));
    }

    @Override
    protected int spiritTint() {
        return SPIRIT_TINT;
    }

    @Override
    protected float hoverSpeed() {
        return 0.18F;
    }

    @Override
    protected float modelScale() {
        return 0.8F;
    }
}
