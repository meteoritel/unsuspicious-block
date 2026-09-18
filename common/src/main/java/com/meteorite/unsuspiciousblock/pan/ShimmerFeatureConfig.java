package com.meteorite.unsuspiciousblock.pan;

import com.meteorite.unsuspiciousblock.pan.variant.ShimmerVariant;
import com.meteorite.unsuspiciousblock.pan.variant.ShimmerVariants;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.world.level.levelgen.feature.configurations.FeatureConfiguration;

/***
 * 淘洗点地物配置——携带变体 id，使同一个地物类型按数据服务于全部依附介质。
 * <p>
 * 地物类型注册名固定为 {@code unsuspiciousblock:shimmer}，具体介质由数据包的 configured_feature 指定。
 */
public record ShimmerFeatureConfig(ShimmerVariant variant) implements FeatureConfiguration {
    public static final Codec<ShimmerFeatureConfig> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            ShimmerVariants.CODEC.fieldOf("variant").forGetter(ShimmerFeatureConfig::variant)
    ).apply(instance, ShimmerFeatureConfig::new));
}
