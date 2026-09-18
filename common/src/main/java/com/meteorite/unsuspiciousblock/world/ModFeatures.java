package com.meteorite.unsuspiciousblock.world;

import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.pan.ShimmerFeature;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/*** 跨平台地物注册清单；动态配置与放置规则由数据包提供。 */
public final class ModFeatures {
    public static final ResourceKey<PlacedFeature> RIVER_SHIMMER = ResourceKey.create(
            Registries.PLACED_FEATURE, ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "river_shimmer"));

    /*** 地物类型名称及其延迟工厂。 */
    public record FeatureEntry(String name, Supplier<Feature<?>> factory) {}

    public static final List<FeatureEntry> REGISTRY_MANIFEST = List.of(
            new FeatureEntry("shimmer", ShimmerFeature::new));

    private ModFeatures() {}

    // 平台分别提供即时注册或延迟注册回调。
    public static void forEach(BiConsumer<String, Supplier<Feature<?>>> registrar) {
        for (FeatureEntry entry : REGISTRY_MANIFEST) {
            registrar.accept(entry.name(), entry.factory());
        }
    }
}
