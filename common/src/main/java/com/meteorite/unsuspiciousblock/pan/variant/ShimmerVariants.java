package com.meteorite.unsuspiciousblock.pan.variant;

import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.entity.ModEntities;
import com.mojang.serialization.Codec;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.level.storage.loot.LootTable;

import java.util.LinkedHashMap;
import java.util.Map;

/***
 * 淘洗点变体注册表——按 id 索引全部变体，新增变体只需在此追加一条数据。
 * <p>
 * 表内数据只引用原版常量与延迟求值的实体类型，因此客户端与服务端都能安全地静态初始化；
 * 地物配置与实体 NBT 都以变体 id 为引用键。
 */
public final class ShimmerVariants {
    public static final ResourceLocation WATER_ID =
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "water");

    // 水域变体——依附水与普通冰，沿河流群系与海平面生成
    public static final ShimmerVariant WATER = new ShimmerVariant(
            WATER_ID,
            new WaterAnchor(),
            new RiverDomain(),
            new GlowStyle(
                    new GlowStyle.Rgb(255, 223, 115),
                    new GlowStyle.Rgb(255, 249, 213),
                    ParticleTypes.END_ROD,
                    ParticleTypes.SPLASH,
                    ParticleTypes.FISHING,
                    SoundEvents.BUCKET_FILL,
                    SoundEvents.AMETHYST_BLOCK_CHIME,
                    SoundEvents.WATER_AMBIENT),
            ResourceKey.create(Registries.LOOT_TABLE,
                    ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "gameplay/panning/river")),
            () -> ModEntities.SHIMMER.get());

    private static final Map<ResourceLocation, ShimmerVariant> BY_ID = createIndex();

    // 地物配置与账本条目以变体 id 持久化，读取时经由此编解码器还原为变体数据
    public static final Codec<ShimmerVariant> CODEC =
            ResourceLocation.CODEC.xmap(ShimmerVariants::get, ShimmerVariant::id);

    private ShimmerVariants() {
    }

    private static Map<ResourceLocation, ShimmerVariant> createIndex() {
        Map<ResourceLocation, ShimmerVariant> index = new LinkedHashMap<>();
        register(index, WATER);
        return Map.copyOf(index);
    }

    // 未登记的变体 id 属于数据包或存档错误，直接抛出以便在加载阶段暴露
    public static ShimmerVariant get(ResourceLocation id) {
        ShimmerVariant variant = BY_ID.get(id);
        if (variant == null) {
            throw new IllegalArgumentException("Unknown shimmer variant: " + id);
        }
        return variant;
    }

    private static void register(Map<ResourceLocation, ShimmerVariant> index, ShimmerVariant variant) {
        ShimmerVariant previous = index.put(variant.id(), variant);
        if (previous != null) {
            throw new IllegalStateException("Duplicate shimmer variant id: " + variant.id());
        }
    }
}
