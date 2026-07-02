package com.meteorite.unsuspiciousblock.client.renderer;

import com.meteorite.unsuspiciousblock.entity.LanternPet;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.NotNull;

/**
 * 灵魂提灯宠物渲染器 —— 直接绘制原版灵魂灯笼方块模型作为实体外观。
 * <p>
 * 实体本身不携带贴图文件（复用方块图集），通过 {@link BlockRenderDispatcher#renderSingleBlock}
 * 把当前 soul_lantern 状态烘焙渲染为实体。叠加正弦上下浮动与朝运动方向轻微倾斜，
 * 让宠物在悬停时也保持灵动感。
 */
public class LanternPetRenderer extends EntityRenderer<LanternPet> {

    private static final float BOB_SPEED = 0.15F;
    private static final float BOB_AMPLITUDE = 0.08F;
    private static final float MODEL_SCALE = 0.7F;
    // 提灯悬挂点偏移：让方块底部悬在实体坐标下方
    private static final float VERTICAL_OFFSET = -0.3F;

    private final BlockRenderDispatcher blockRenderer;
    private final BlockState soulLanternState;

    public LanternPetRenderer(EntityRendererProvider.Context ctx) {
        super(ctx);
        this.blockRenderer = ctx.getBlockRenderDispatcher();
        this.soulLanternState = Blocks.SOUL_LANTERN.defaultBlockState();
        this.shadowRadius = 0.3F;
    }

    @Override
    public @NotNull ResourceLocation getTextureLocation(@NotNull LanternPet entity) {
        // 复用方块图集；实际渲染走 BlockRenderDispatcher，不依赖本贴图
        return InventoryMenu.BLOCK_ATLAS;
    }

    @Override
    public void render(@NotNull LanternPet entity, float entityYaw, float partialTicks,
                       @NotNull PoseStack poseStack, @NotNull MultiBufferSource bufferSource,
                       int packedLight) {
        poseStack.pushPose();

        // 上下浮动：与实体 aiStep 中施加的速度同相
        float bob = Mth.sin((entity.tickCount + partialTicks) * BOB_SPEED) * BOB_AMPLITUDE;
        poseStack.translate(0.0F, bob + VERTICAL_OFFSET, 0.0F);

        // 朝向：跟随实体 yRot 旋转，xRot 微倾以表现飞行姿态
        float yRot = Mth.lerp(partialTicks, entity.yRotO, entity.getYRot());
        float xRot = Mth.lerp(partialTicks, entity.xRotO, entity.getXRot()) * 0.3F;
        poseStack.mulPose(Axis.YP.rotationDegrees(180.0F - yRot));
        poseStack.mulPose(Axis.XP.rotationDegrees(xRot));

        // 缩放：将方块渲染为略小于原版灯笼尺寸
        poseStack.scale(MODEL_SCALE, MODEL_SCALE, MODEL_SCALE);

        // 灯笼方块原点居中
        poseStack.translate(-0.5F, 0.0F, -0.5F);

        // 渲染灵魂灯笼方块模型；使用 translucent 类型让灵魂火苗贴图正常透出
        int packedOverlay = OverlayTexture.NO_OVERLAY;
        this.blockRenderer.renderSingleBlock(
                this.soulLanternState, poseStack, bufferSource,
                packedLight, packedOverlay);

        poseStack.popPose();
        super.render(entity, entityYaw, partialTicks, poseStack, bufferSource, packedLight);
    }

    @Override
    protected boolean shouldShowName(@NotNull LanternPet entity) {
        return false;
    }

    @Override
    protected int getBlockLightLevel(@NotNull LanternPet entity, @NotNull BlockPos pos) {
        // 灵魂灯笼自带 15 级光照，让实体本身成为光源
        return 15;
    }
}
