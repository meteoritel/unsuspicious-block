package com.meteorite.unsuspiciousblock.loottable.condition;

import com.mojang.serialization.MapCodec;
import net.minecraft.world.level.storage.loot.predicates.LootItemConditionType;

import java.util.function.Supplier;

/**
 * 模组战利品条件类型注册中心——暴露 codec 并持有平台注册后的 {@link LootItemConditionType}。
 * <p>
 * 实际注册由平台代码完成：NeoForge 使用 {@code DeferredRegister}，
 * Fabric 使用 {@code Registry.register()}。
 */
public final class ModLootConditions {

    public static final MapCodec<MudDredgingCondition> MUD_DREDGING_CODEC = MudDredgingCondition.CODEC;

    private static Supplier<LootItemConditionType> mudDredgingType;

    private ModLootConditions() {
    }

    /** 由平台代码在注册完成后调用，设置已注册的类型持有者 */
    public static void setMudDredgingType(Supplier<LootItemConditionType> type) {
        mudDredgingType = type;
    }

    /** 获取已注册的泥地打捞战利品条件类型 */
    public static LootItemConditionType mudDredging() {
        return mudDredgingType.get();
    }
}