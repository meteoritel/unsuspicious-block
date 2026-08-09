package com.meteorite.unsuspiciousblock.client.renderer;

import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.builders.LayerDefinition;

import java.util.List;
import java.util.function.Supplier;

/**
 * 实体模型层注册清单 —— 沿用 ModEntityRenderers 的 Registry Manifest 模式
 * <p>
 * 客户端专属。ModelLayerLocation 与 LayerDefinition 均为 vanilla 类，
 * 可安全放在 common 中。各平台客户端遍历清单调用各自注册器 API。
 */
public class ModModelLayers {

    /**
     * 模型层清单条目
     *
     * @param location 模型层位置（vanilla 类，跨平台通用）
     * @param supplier LayerDefinition 供给者（延迟创建，避免类加载顺序问题）
     */
    public record LayerEntry(
            ModelLayerLocation location,
            Supplier<LayerDefinition> supplier) {}

    // 模型层注册清单——新增模型层只需在此添加一行
    public static final List<LayerEntry> REGISTRY_MANIFEST = List.of(
            new LayerEntry(LanternPetModel.LAYER_LOCATION, LanternPetModel::createBodyLayer),
            new LayerEntry(PotteryWheelModel.TURNTABLE_LAYER, PotteryWheelModel::createTurntableLayer),
            new LayerEntry(PotteryWheelModel.CLAY_LAYER, PotteryWheelModel::createClayLayer)
    );

    // 遍历清单，调用平台回调完成注册
    public static void forEach(LayerRegistrar registrar) {
        for (LayerEntry entry : REGISTRY_MANIFEST) {
            registrar.register(entry.location(), entry.supplier());
        }
    }

    /**
     * 模型层注册回调 —— 由各平台客户端实现，描述"如何注册单个模型层"
     * <p>
     * 客户端专属：仅在客户端环境加载，不会被服务端引用。
     */
    @FunctionalInterface
    public interface LayerRegistrar {
        void register(ModelLayerLocation location, Supplier<LayerDefinition> supplier);
    }
}
