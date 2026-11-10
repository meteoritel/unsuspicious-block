package com.meteorite.unsuspiciousblock.client.renderer;

import com.meteorite.unsuspiciousblock.core.blockentity.ModBlockEntities;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;

import java.util.List;
import java.util.function.Supplier;

/** 方块实体渲染器的客户端清单。 */
public final class ModBlockEntityRenderers {
    /** 方块实体类型延迟求值，交给平台注册时再解析。 */
    public record RendererEntry<T extends BlockEntity>(
            Supplier<BlockEntityType<T>> type,
            BlockEntityRendererProvider<T> provider) {
    }

    public static final List<RendererEntry<?>> MANIFEST = List.of(
            new RendererEntry<>(() -> ModBlockEntities.POTTERY_WHEEL.get(), PotteryWheelRenderer::new)
    );

    private ModBlockEntityRenderers() {
    }

    /** 在平台渲染器注册事件中遍历类型与工厂。 */
    public static void forEach(RendererRegistrar registrar) {
        for (RendererEntry<?> entry : MANIFEST) {
            register(entry, registrar);
        }
    }

    private static <T extends BlockEntity> void register(RendererEntry<T> entry, RendererRegistrar registrar) {
        registrar.register(entry.type().get(), entry.provider());
    }

    /** 保留方块实体类型与 renderer provider 的泛型对应关系。 */
    @FunctionalInterface
    public interface RendererRegistrar {
        /** 将同类型的方块实体与渲染工厂交给平台。 */
        <T extends BlockEntity> void register(BlockEntityType<T> type, BlockEntityRendererProvider<T> provider);
    }
}
