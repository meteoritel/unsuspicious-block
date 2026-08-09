package com.meteorite.unsuspiciousblock.client.renderer;

import com.meteorite.unsuspiciousblock.Constants;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.resources.ResourceLocation;

/** 纹饰陶轮台的动态转盘与湿黏土模型层。 */
public final class PotteryWheelModel {
    public static final ModelLayerLocation TURNTABLE_LAYER = new ModelLayerLocation(
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "pottery_wheel_turntable"), "main");
    public static final ModelLayerLocation CLAY_LAYER = new ModelLayerLocation(
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "pottery_wheel_clay"), "main");

    private PotteryWheelModel() {
    }

    // 根据新模型 turntable 分组创建 8x2x8 的动态转盘。
    public static LayerDefinition createTurntableLayer() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();
        root.addOrReplaceChild("turntable",
                CubeListBuilder.create().texOffs(0, 0)
                        .addBox(-4.0F, -2.0F, -4.0F, 8.0F, 2.0F, 8.0F),
                PartPose.ZERO);
        return LayerDefinition.create(mesh, 32, 16);
    }

    // 黏土沿用原有材质与体积，并放置在新转盘的上表面。
    public static LayerDefinition createClayLayer() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();
        root.addOrReplaceChild("clay",
                CubeListBuilder.create().texOffs(0, 0)
                        .addBox(-1.5F, 0.0F, -1.5F, 3.0F, 2.0F, 3.0F),
                PartPose.ZERO);
        return LayerDefinition.create(mesh, 16, 16);
    }

    // 提取已烘焙的动态转盘部件。
    public static ModelPart turntable(ModelPart root) {
        return root.getChild("turntable");
    }

    // 提取已烘焙的湿黏土部件。
    public static ModelPart clay(ModelPart root) {
        return root.getChild("clay");
    }
}
