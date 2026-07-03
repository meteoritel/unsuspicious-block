package com.meteorite.unsuspiciousblock.client.renderer;

import com.meteorite.unsuspiciousblock.entity.LanternPet;
import net.minecraft.client.model.HierarchicalModel;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.NotNull;

/**
 * 灵魂提灯宠物模型 —— 多部件自定义飞行提灯
 * <p>
 * 部件层级：顶环 → 链条 → 顶盖 → 灯笼骨架(4 角柱 + 4 玻璃面 + 内部灵魂火苗) → 底盖 → 底坠。
 * 整体高度约 15 像素，贴图尺寸 64x64。
 * 当前仅火苗做轻微浮动呼吸动画，其余动作后续扩展。
 */
public class LanternPetModel extends HierarchicalModel<LanternPet> {

    // 模型层注册位置：mod 命名空间 + "main" 层名
    public static final ModelLayerLocation LAYER_LOCATION =
            new ModelLayerLocation(
                    ResourceLocation.fromNamespaceAndPath("unsuspiciousblock", "soul_lantern_pet"),
                    "main");

    private final ModelPart root;
    private final ModelPart flame;

    public LanternPetModel(ModelPart root) {
        super(RenderType::entityCutoutNoCull);
        this.root = root.getChild("root");
        this.flame = this.root.getChild("lantern_body").getChild("flame");
    }

    @Override
    public @NotNull ModelPart root() {
        return this.root;
    }

    // 构建模型层级与 UV 映射
    public static LayerDefinition createBodyLayer() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition rootDef = mesh.getRoot();

        PartDefinition root = rootDef.addOrReplaceChild("root",
                CubeListBuilder.create(),
                PartPose.offset(0.0F, 22.0F, 0.0F));

        // 顶环
        root.addOrReplaceChild("ring",
                CubeListBuilder.create().texOffs(0, 0)
                        .addBox(-3.0F, -1.0F, -3.0F, 6.0F, 1.0F, 6.0F),
                PartPose.offset(0.0F, -8.0F, 0.0F));

        // 链条
        root.addOrReplaceChild("chain",
                CubeListBuilder.create().texOffs(28, 0)
                        .addBox(-0.5F, 0.0F, -0.5F, 1.0F, 2.0F, 1.0F),
                PartPose.offset(0.0F, -7.0F, 0.0F));

        // 顶盖
        root.addOrReplaceChild("cap_top",
                CubeListBuilder.create().texOffs(0, 8)
                        .addBox(-3.5F, 0.0F, -3.5F, 7.0F, 1.0F, 7.0F),
                PartPose.offset(0.0F, -5.0F, 0.0F));

        // 灯笼主体骨架（含角柱、玻璃、火苗）
        PartDefinition body = root.addOrReplaceChild("lantern_body",
                CubeListBuilder.create(),
                PartPose.offset(0.0F, -4.0F, 0.0F));

        // 4 根角柱
        body.addOrReplaceChild("post_nw",
                CubeListBuilder.create().texOffs(0, 17)
                        .addBox(-0.5F, 0.0F, -0.5F, 1.0F, 8.0F, 1.0F),
                PartPose.offset(-3.0F, 0.0F, -3.0F));
        body.addOrReplaceChild("post_ne",
                CubeListBuilder.create().texOffs(5, 17)
                        .addBox(-0.5F, 0.0F, -0.5F, 1.0F, 8.0F, 1.0F),
                PartPose.offset(3.0F, 0.0F, -3.0F));
        body.addOrReplaceChild("post_sw",
                CubeListBuilder.create().texOffs(10, 17)
                        .addBox(-0.5F, 0.0F, -0.5F, 1.0F, 8.0F, 1.0F),
                PartPose.offset(-3.0F, 0.0F, 3.0F));
        body.addOrReplaceChild("post_se",
                CubeListBuilder.create().texOffs(15, 17)
                        .addBox(-0.5F, 0.0F, -0.5F, 1.0F, 8.0F, 1.0F),
                PartPose.offset(3.0F, 0.0F, 3.0F));

        // 4 面玻璃（深度 0 薄面板，Z 方向略微内缩避免与角柱 Z-fighting）
        body.addOrReplaceChild("glass_n",
                CubeListBuilder.create().texOffs(0, 27)
                        .addBox(-3.0F, 0.0F, -0.5F, 6.0F, 8.0F, 0.0F),
                PartPose.offset(0.0F, 0.0F, -2.9F));
        body.addOrReplaceChild("glass_s",
                CubeListBuilder.create().texOffs(13, 27)
                        .addBox(-3.0F, 0.0F, -0.5F, 6.0F, 8.0F, 0.0F),
                PartPose.offset(0.0F, 0.0F, 2.9F));
        body.addOrReplaceChild("glass_e",
                CubeListBuilder.create().texOffs(26, 27)
                        .addBox(-0.5F, 0.0F, -3.0F, 0.0F, 8.0F, 6.0F),
                PartPose.offset(2.9F, 0.0F, 0.0F));
        body.addOrReplaceChild("glass_w",
                CubeListBuilder.create().texOffs(39, 27)
                        .addBox(-0.5F, 0.0F, -3.0F, 0.0F, 8.0F, 6.0F),
                PartPose.offset(-2.9F, 0.0F, 0.0F));

        // 内部灵魂火苗
        body.addOrReplaceChild("flame",
                CubeListBuilder.create().texOffs(0, 36)
                        .addBox(-1.5F, 0.0F, -1.5F, 3.0F, 5.0F, 3.0F),
                PartPose.offset(0.0F, 1.0F, 0.0F));

        // 底盖
        root.addOrReplaceChild("cap_bottom",
                CubeListBuilder.create().texOffs(0, 45)
                        .addBox(-3.5F, 0.0F, -3.5F, 7.0F, 1.0F, 7.0F),
                PartPose.offset(0.0F, 4.0F, 0.0F));

        // 底坠
        root.addOrReplaceChild("tassel",
                CubeListBuilder.create().texOffs(30, 45)
                        .addBox(-1.0F, 0.0F, -1.0F, 2.0F, 3.0F, 2.0F),
                PartPose.offset(0.0F, 6.0F, 0.0F));

        return LayerDefinition.create(mesh, 64, 64);
    }

    @Override
    public void setupAnim(@NotNull LanternPet entity, float limbSwing, float limbSwingAmount,
                          float ageInTicks, float netHeadYaw, float headPitch) {
        // 先重置所有部件到初始姿态
        this.root().getAllParts().forEach(ModelPart::resetPose);
        // 火苗轻微 y 方向浮动
        float flameBob = Mth.sin(ageInTicks * 0.3F) * 0.3F;
        this.flame.y = 1.0F + flameBob;
        // 火苗轻微缩放呼吸
        float flameScale = 1.0F + Mth.sin(ageInTicks * 0.2F) * 0.05F;
        this.flame.xScale = flameScale;
        this.flame.zScale = flameScale;

        // TODO: 链条根据移动速度摇摆（用 limbSwingAmount 驱动 chain.xRot，模拟物理钟摆）
        // TODO: 主体根据飞行方向倾斜（用 entity.yRotO → yRot 差值驱动 root.zRot，表现转向侧倾）
        // TODO: 底坠物理摆动（基于 chain 摆动相位延迟，tassel 滞后于 chain 形成链式跟随）
        // TODO: 火苗根据移动速度拉长（高速时 flame.yScale 增大，模拟风对火焰的拉伸效果）
    }
}
