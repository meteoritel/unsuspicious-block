package com.meteorite.unsuspiciousblock.loottable.condition;

import com.meteorite.unsuspiciousblock.Constants;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.minecraft.world.level.storage.loot.predicates.LootItemConditionType;

import java.util.function.Supplier;

/**
 * 模组战利品条件类型注册中心——集中管理所有自定义 {@link LootItemConditionType}。
 */
public final class ModLootConditions {

    // 泥地打捞附魔条件——检查玩家钓鱼竿附魔、群系与概率
    public static final Supplier<LootItemConditionType> MUD_DREDGING = register(
            "mud_dredging", MudDredgingCondition.CODEC);

    private ModLootConditions() {
    }

    // 重置注册状态（供测试或重载场景使用）
    public static void register() {
        // 类加载时已通过静态字段注册；方法体保留以支持显式调用
    }

    private static Supplier<LootItemConditionType> register(String name, MapCodec<? extends LootItemCondition> codec) {
        LootItemConditionType type = Registry.register(
                BuiltInRegistries.LOOT_CONDITION_TYPE,
                ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, name),
                new LootItemConditionType(codec));
        return () -> type;
    }
}