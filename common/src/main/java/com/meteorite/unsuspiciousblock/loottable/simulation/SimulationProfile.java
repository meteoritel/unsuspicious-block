package com.meteorite.unsuspiciousblock.loottable.simulation;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.minecraft.world.phys.Vec3;

import java.util.Map;

/**
 * 单次战利品模拟使用的不可变场景配置。
 * <p>
 * profile 一方面为 LootParams 提供工具、方块状态、伤害源等具体值，另一方面声明哪些
 * 场景门槛按“满足条件时”统计。后者不修改真实世界天气、时间或计分板，输出的是
 * 资格条件调整后的单值估算，不表示群系、天气等场景本身的自然发生概率。
 */
public record SimulationProfile(
        Vec3 origin,
        ItemStack tool,
        BlockState blockState,
        DamageSource damageSource,
        float explosionRadius,
        float luck,
        boolean fishingContext,
        Map<String, Boolean> conditionOutcomes,
        Map<ResourceLocation, Boolean> conditionTypeDefaults
) {
    public SimulationProfile {
        tool = tool.copy();
        conditionOutcomes = Map.copyOf(conditionOutcomes);
        conditionTypeDefaults = Map.copyOf(conditionTypeDefaults);
    }

    // 创建当前目录概率统计使用的“满足获取条件”场景
    public static SimulationProfile eligibleConditions(ServerLevel level, ResourceLocation tableId) {
        boolean fishing = tableId.getPath().contains("fishing");
        ItemStack defaultTool = new ItemStack(fishing ? Items.FISHING_ROD : Items.DIAMOND_PICKAXE);
        return new SimulationProfile(
                Vec3.ZERO,
                defaultTool,
                Blocks.AIR.defaultBlockState(),
                level.damageSources().generic(),
                0.0F,
                0.0F,
                fishing, Map.of(), Map.of());
    }

    // 精确条件结果优先；类型默认值只用于解析期看不到的运行时注入条件
    public Boolean conditionOutcome(LootItemCondition condition) {
        Boolean exact = this.conditionOutcomes.get(LootConditionFingerprint.ofRaw(condition));
        if (exact != null) {
            return exact;
        }
        ResourceLocation conditionId = BuiltInRegistries.LOOT_CONDITION_TYPE.getKey(condition.getType());
        return conditionId == null ? null : this.conditionTypeDefaults.get(conditionId);
    }

    // 创建仅替换条件结果的新 profile
    public SimulationProfile withConditionOutcomes(Map<String, Boolean> outcomes,
                                                   Map<ResourceLocation, Boolean> typeDefaults) {
        return new SimulationProfile(this.origin, this.tool, this.blockState, this.damageSource,
                this.explosionRadius, this.luck, this.fishingContext, outcomes, typeDefaults);
    }

    // 创建使用指定工具的新 profile
    public SimulationProfile withTool(ItemStack newTool) {
        return new SimulationProfile(this.origin, newTool, this.blockState, this.damageSource,
                this.explosionRadius, this.luck, this.fishingContext,
                this.conditionOutcomes, this.conditionTypeDefaults);
    }
}
