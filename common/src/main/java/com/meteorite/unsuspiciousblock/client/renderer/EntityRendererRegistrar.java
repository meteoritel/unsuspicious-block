package com.meteorite.unsuspiciousblock.client.renderer;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.client.renderer.entity.EntityRendererProvider;

/**
 * 实体渲染器注册回调 —— 由各平台客户端实现，描述“如何注册单个实体渲染器”
 * <p>
 * 客户端专属：仅在客户端环境加载，不会被服务端引用。
 * 采用泛型方法捕获类型参数 T，平台侧实现无需强制转换；泛型方法须用匿名内部类实现。
 */
@FunctionalInterface
public interface EntityRendererRegistrar {

    // 为指定实体类型注册渲染器提供者
    <T extends Entity> void register(EntityType<T> type, EntityRendererProvider<T> provider);
}
