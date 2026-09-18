package com.meteorite.unsuspiciousblock.pan.variant;

import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.entity.ModEntities;
import com.mojang.serialization.Codec;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
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
    public static final ResourceLocation GLIMMER_ID =
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "glimmer");

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
                    SoundEvents.WATER_AMBIENT,
                    PanningMedium.WATER),
            ResourceKey.create(Registries.LOOT_TABLE,
                    ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "gameplay/panning/river")),
            () -> ModEntities.SHIMMER.get());

    // 幽微的光变体——依附下界岩浆，紫色波光与岩浆音效，产出走独立的岩浆表
    public static final ShimmerVariant GLIMMER = new ShimmerVariant(
            GLIMMER_ID,
            new LavaAnchor(),
            new NetherDomain(),
            new GlowStyle(
                    new GlowStyle.Rgb(200, 120, 255),
                    new GlowStyle.Rgb(240, 205, 255),
                    ParticleTypes.WITCH,
                    ParticleTypes.LAVA,
                    ParticleTypes.WITCH,
                    SoundEvents.BUCKET_FILL_LAVA,
                    SoundEvents.LAVA_POP,
                    SoundEvents.LAVA_AMBIENT,
                    PanningMedium.LAVA),
            ResourceKey.create(Registries.LOOT_TABLE,
                    ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "gameplay/panning/lava")),
            () -> ModEntities.GLIMMER.get());

    private static final Map<ResourceLocation, ShimmerVariant> BY_ID = createIndex();

    // 变体路径名清单——调试指令用不带命名空间的短名输入与补全
    private static final List<String> PATHS = BY_ID.keySet().stream().map(ResourceLocation::getPath).toList();

    // 地物配置与账本条目以变体 id 持久化，读取时经由此编解码器还原为变体数据
    public static final Codec<ShimmerVariant> CODEC =
            ResourceLocation.CODEC.xmap(ShimmerVariants::get, ShimmerVariant::id);

    private ShimmerVariants() {
    }

    private static Map<ResourceLocation, ShimmerVariant> createIndex() {
        Map<ResourceLocation, ShimmerVariant> index = new LinkedHashMap<>();
        register(index, WATER);
        register(index, GLIMMER);
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

    // 按路径名查找——命令输入用；未登记时返回 null，由调用方给出可读的失败提示
    @Nullable
    public static ShimmerVariant byPath(String path) {
        return BY_ID.get(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, path));
    }

    // 全部变体的路径名，供调试指令补全
    public static List<String> paths() {
        return PATHS;
    }

    private static void register(Map<ResourceLocation, ShimmerVariant> index, ShimmerVariant variant) {
        ShimmerVariant previous = index.put(variant.id(), variant);
        if (previous != null) {
            throw new IllegalStateException("Duplicate shimmer variant id: " + variant.id());
        }
    }
}
