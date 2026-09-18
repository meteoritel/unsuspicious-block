package com.meteorite.unsuspiciousblock.entity;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 实体注册清单 —— 沿用 ModItems 的 Registry Manifest 模式
 */
public class ModEntities {

    // 平台侧在注册时回写为 Supplier，运行时通过 .get() 取 EntityType，永不为 null
    public static Supplier<EntityType<MessengerCat>> MESSENGER_CAT;
    public static Supplier<EntityType<SwordsmanCat>> SWORDSMAN_CAT;
    public static Supplier<EntityType<MerchantCat>> MERCHANT_CAT;
    public static Supplier<EntityType<LanternPet>> LANTERN_PET;
    // 各变体的实体类型按具体子类声明：EntityType 的泛型不协变，写入时类型精确匹配，无需强制转换。
    // 读取方（变体注册表、渲染器清单）自行以 EntityType<? extends ShimmerEntity> 接收。
    public static Supplier<EntityType<WaterShimmerEntity>> SHIMMER;
    public static Supplier<EntityType<GlimmerEntity>> GLIMMER;

    /**
     * 实体注册清单条目
     *
     * @param name       实体资源名
     * @param factory    实体类型工厂（延迟创建，避免类加载顺序问题）
     * @param setter     注册后回写 common 静态字段（Supplier 形式，消除平台回写时机差异）
     * @param attributes 默认属性工厂（仅生物实体需要，非生物实体为 null）
     */
    public record EntityEntry<T extends Entity>(
            String name,
            Supplier<EntityType<T>> factory,
            Consumer<Supplier<EntityType<T>>> setter,
            @Nullable Supplier<AttributeSupplier.Builder> attributes){}

    // 实体注册清单——新增实体只需在此添加一行
    public static final List<EntityEntry<?>> REGISTRY_MANIFEST = List.of(
            new EntityEntry<>("messenger_cat",
                    ModEntities::createMessengerCatType,
                    supplier -> MESSENGER_CAT = supplier,
                    MessengerCat::createAttributes),
            new EntityEntry<>("swordsman_cat",
                    ModEntities::createSwordsmanCatType,
                    supplier -> SWORDSMAN_CAT = supplier,
                    SwordsmanCat::createAttributes),
            new EntityEntry<>("merchant_cat",
                    ModEntities::createMerchantCatType,
                    supplier -> MERCHANT_CAT = supplier,
                    MerchantCat::createAttributes),
            new EntityEntry<>("soul_lantern_pet",
                    ModEntities::createLanternPetType,
                    supplier -> LANTERN_PET = supplier,
                    LanternPet::createAttributes),
            new EntityEntry<>("shimmer",
                    ModEntities::createShimmerType,
                    supplier -> SHIMMER = supplier,
                    null),
            new EntityEntry<>("glimmer",
                    ModEntities::createGlimmerType,
                    supplier -> GLIMMER = supplier,
                    null)
    );

    // 遍历清单，调用平台回调完成注册
    public static void forEach(EntityRegistrar registrar) {
        for (EntityEntry<?> entry : REGISTRY_MANIFEST) {
            register(entry, registrar);
        }
    }

    // 泛型辅助方法：捕获通配符条目的类型参数 T，避免平台侧强制转换
    private static <T extends Entity> void register(EntityEntry<T> entry, EntityRegistrar registrar) {
        registrar.register(entry.name(), entry.factory(), entry.setter(), entry.attributes());
    }

    public static EntityType<MessengerCat> createMessengerCatType() {
        return EntityType.Builder.of(MessengerCat::new, MobCategory.CREATURE)
                .sized(0.6f, 0.7f)
                .eyeHeight(0.35f)
                .clientTrackingRange(8)
                .build("messenger_cat");
    }

    public static EntityType<LanternPet> createLanternPetType() {
        return EntityType.Builder.of(LanternPet::new, MobCategory.CREATURE)
                .sized(0.6f, 0.9f)
                .eyeHeight(0.5f)
                .clientTrackingRange(8)
                .build("soul_lantern_pet");
    }

    public static EntityType<SwordsmanCat> createSwordsmanCatType() {
        return EntityType.Builder.of(SwordsmanCat::new, MobCategory.CREATURE)
                .sized(0.6f, 0.7f)
                .eyeHeight(0.35f)
                .clientTrackingRange(8)
                .build("swordsman_cat");
    }

    public static EntityType<MerchantCat> createMerchantCatType() {
        return EntityType.Builder.of(MerchantCat::new, MobCategory.CREATURE)
                .sized(0.6f, 0.7f)
                .eyeHeight(0.35f)
                .clientTrackingRange(8)
                .build("merchant_cat");
    }

    // 幽微的光·岩浆变体——包围盒规格与水域变体一致；岩浆没有碰撞箱，海面中央的点可以正常淘洗
    public static EntityType<GlimmerEntity> createGlimmerType() {
        return EntityType.Builder.of(GlimmerEntity::new, MobCategory.MISC)
                .sized(0.9f, 0.9f)
                .clientTrackingRange(10)
                .updateInterval(20)
                .build("glimmer");
    }

    // 闪烁的光·水域变体——无 AI 的液面淘洗点，包围盒覆盖其依附的液面方块，便于准星选中。
    // 包围盒高度不超过一格：依附于完整碰撞箱方块（如冰）的点会被准星射线截断而无法淘洗，
    // 这是刻意保留的行为，改动 .sized(...) 会改变它，详见 docs/dev/panning.md 第 3 节。
    public static EntityType<WaterShimmerEntity> createShimmerType() {
        return EntityType.Builder.of(WaterShimmerEntity::new, MobCategory.MISC)
                .sized(0.9f, 0.9f)
                .clientTrackingRange(10)
                .updateInterval(20)
                .build("shimmer");
    }
}
