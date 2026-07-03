package com.meteorite.unsuspiciousblock.entity;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 实体注册清单 —— 沿用 ModItems 的 Registry Manifest 模式
 */
public class ModEntities {

    // 平台侧在注册时回写为 Supplier，运行时通过 .get() 取 EntityType，永不为 null
    public static Supplier<EntityType<GhostCat>> GHOST_CAT;
    public static Supplier<EntityType<LanternPet>> LANTERN_PET;

    /**
     * 实体注册清单条目
     *
     * @param name       实体资源名
     * @param factory    实体类型工厂（延迟创建，避免类加载顺序问题）
     * @param setter     注册后回写 common 静态字段（Supplier 形式，消除平台回写时机差异）
     * @param attributes 默认属性工厂（仅 LivingEntity 需要）
     */
    public record EntityEntry<T extends LivingEntity>(
            String name,
            Supplier<EntityType<T>> factory,
            Consumer<Supplier<EntityType<T>>> setter,
            Supplier<AttributeSupplier.Builder> attributes){}

    // 实体注册清单——新增实体只需在此添加一行
    public static final List<EntityEntry<?>> REGISTRY_MANIFEST = List.of(
            new EntityEntry<>("ghost_cat",
                    ModEntities::createGhostCatType,
                    supplier -> GHOST_CAT = supplier,
                    GhostCat::createAttributes),
            new EntityEntry<>("soul_lantern_pet",
                    ModEntities::createLanternPetType,
                    supplier -> LANTERN_PET = supplier,
                    LanternPet::createAttributes)
    );

    // 遍历清单，调用平台回调完成注册
    public static void forEach(EntityRegistrar registrar) {
        for (EntityEntry<?> entry : REGISTRY_MANIFEST) {
            register(entry, registrar);
        }
    }

    // 泛型辅助方法：捕获通配符条目的类型参数 T，避免平台侧强制转换
    private static <T extends LivingEntity> void register(EntityEntry<T> entry, EntityRegistrar registrar) {
        registrar.register(entry.name(), entry.factory(), entry.setter(), entry.attributes());
    }

    public static EntityType<GhostCat> createGhostCatType() {
        return EntityType.Builder.of(GhostCat::new, MobCategory.CREATURE)
                .sized(0.6f, 0.7f)
                .eyeHeight(0.35f)
                .clientTrackingRange(8)
                .build("ghost_cat");
    }

    public static EntityType<LanternPet> createLanternPetType() {
        return EntityType.Builder.of(LanternPet::new, MobCategory.CREATURE)
                .sized(0.6f, 0.9f)
                .eyeHeight(0.5f)
                .clientTrackingRange(8)
                .build("soul_lantern_pet");
    }
}
