package com.meteorite.unsuspiciousblock.client.renderer;

import com.meteorite.unsuspiciousblock.client.renderer.layer.MessengerCatClothesLayer;
import com.meteorite.unsuspiciousblock.client.renderer.layer.MessengerCatCollarLayer;
import com.meteorite.unsuspiciousblock.core.entity.MessengerCat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.model.CatModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.util.FastColor;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.NotNull;

/** 信使猫的灵体表现与职业装饰。 */
public class MessengerCatRenderer extends SpiritCatRenderer<MessengerCat> {
    private static final int GHOST_TINT = FastColor.ARGB32.color(153, 170, 221, 255);

    public MessengerCatRenderer(EntityRendererProvider.Context context) {
        this(context, context.bakeLayer(ModelLayers.CAT));
    }

    private MessengerCatRenderer(EntityRendererProvider.Context context, ModelPart catRoot) {
        super(context, new CatModel<>(catRoot), 0.4F);
        addLayer(new MessengerCatClothesLayer(this, context.getModelSet(), catRoot));
        addLayer(new MessengerCatCollarLayer(this, context.getModelSet()));
    }

    @Override
    protected RenderType spiritRenderType(@NotNull MessengerCat entity) {
        return RenderType.entityTranslucentEmissive(getTextureLocation(entity));
    }

    @Override
    protected int spiritTint() {
        return GHOST_TINT;
    }

    @Override
    protected float modelScale() {
        return 0.8F;
    }

    @Override
    protected void setupRotations(@NotNull MessengerCat entity, @NotNull PoseStack poseStack, float bob,
                                  float yBodyRot, float partialTick, float scale) {
        super.setupRotations(entity, poseStack, bob, yBodyRot, partialTick, scale);
        float lieDown = entity.getLieDownAmount(partialTick);
        if (lieDown <= 0.0F) {
            return;
        }
        poseStack.translate(0.4F * lieDown, 0.15F * lieDown, 0.1F * lieDown);
        poseStack.mulPose(Axis.ZP.rotationDegrees(Mth.rotLerp(lieDown, 0.0F, 90.0F)));
        BlockPos pos = entity.blockPosition();
        for (Player player : entity.level().getEntitiesOfClass(Player.class, new AABB(pos).inflate(2.0, 2.0, 2.0))) {
            if (player.isSleeping()) {
                poseStack.translate(0.15F * lieDown, 0.0F, 0.0F);
                break;
            }
        }
    }

    @Override
    protected boolean shouldShowName(@NotNull MessengerCat entity) {
        return false;
    }
}
