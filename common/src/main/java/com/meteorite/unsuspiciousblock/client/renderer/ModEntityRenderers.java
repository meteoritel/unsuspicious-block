package com.meteorite.unsuspiciousblock.client.renderer;

import com.meteorite.unsuspiciousblock.entity.ModEntities;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.client.renderer.entity.EntityRendererProvider;

import java.util.List;
import java.util.function.Supplier;

/**
 * 实体渲染器注册清单 —— 沿用 ModEntities 的 Registry Manifest 模式
 * <p>
 * 客户端专属。新增实体渲染器只需在此添加一行，两平台客户端自动遍历注册，
 * 无需再手动改动平台客户端代码。
 */
public class ModEntityRenderers {

    /**
     * 渲染器清单条目
     *
     * @param type     实体类型（用 Supplier 延迟取值，因为类型在注册阶段后才赋值）
     * @param provider 渲染器提供者
     */
    public record RendererEntry<T extends Entity>(
            Supplier<EntityType<T>> type,
            EntityRendererProvider<T> provider) {}

    // 渲染器注册清单——新增实体渲染器只需在此添加一行
    // GHOST_CAT 本身已是 Supplier，二次包装为 lambda 以延迟类加载顺序下的字段读取
    public static final List<RendererEntry<?>> REGISTRY_MANIFEST = List.of(
            new RendererEntry<>(() -> ModEntities.GHOST_CAT.get(), GhostCatRenderer::new),
            new RendererEntry<>(() -> ModEntities.SWORDSMAN_CAT.get(), SwordsmanCatRenderer::new),
            new RendererEntry<>(() -> ModEntities.CAT_MERCHANT.get(), CatMerchantRenderer::new),
            new RendererEntry<>(() -> ModEntities.LANTERN_PET.get(), LanternPetRenderer::new)
    );

    // 遍历清单，调用平台回调完成注册
    public static void forEach(EntityRendererRegistrar registrar) {
        for (RendererEntry<?> entry : REGISTRY_MANIFEST) {
            register(entry, registrar);
        }
    }

    // 泛型辅助方法：捕获通配符条目的类型参数 T，避免平台侧强制转换
    private static <T extends Entity> void register(RendererEntry<T> entry, EntityRendererRegistrar registrar) {
        registrar.register(entry.type().get(), entry.provider());
    }
}
