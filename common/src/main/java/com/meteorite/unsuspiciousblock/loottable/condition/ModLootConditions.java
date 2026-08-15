package com.meteorite.unsuspiciousblock.loottable.condition;

import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.loottable.analysis.LootConditionHandler;
import com.meteorite.unsuspiciousblock.loottable.analysis.LootConditionHandlers;
import com.meteorite.unsuspiciousblock.loottable.analysis.LootConditionInfo;
import com.mojang.serialization.MapCodec;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
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
    public static final MapCodec<ToolEnchantmentChanceCondition> TOOL_ENCHANTMENT_CHANCE_CODEC =
            ToolEnchantmentChanceCondition.CODEC;

    private static Supplier<LootItemConditionType> mudDredgingType;
    private static Supplier<LootItemConditionType> toolEnchantmentChanceType;

    private ModLootConditions() {
    }

    // 由平台代码在注册完成后调用，设置已注册的类型持有者
    public static void setMudDredgingType(Supplier<LootItemConditionType> type) {
        mudDredgingType = type;
    }

    // 获取已注册的泥地打捞战利品条件类型
    public static LootItemConditionType mudDredging() {
        return mudDredgingType.get();
    }

    // 由平台设置工具附魔概率条件类型
    public static void setToolEnchantmentChanceType(Supplier<LootItemConditionType> type) {
        toolEnchantmentChanceType = type;
    }

    // 获取工具附魔概率条件类型
    public static LootItemConditionType toolEnchantmentChance() {
        return toolEnchantmentChanceType.get();
    }

    // 注册自定义条件的静态描述
    public static void registerAnalysisHandlers() {
        ResourceLocation mudDredging = ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "mud_dredging");
        ResourceLocation toolChance = ResourceLocation.fromNamespaceAndPath(
                Constants.MOD_ID, "random_chance_with_tool_enchantment");
        LootConditionHandlers.register(mudDredging, new DescriptionHandler("mud_dredging",
                LootConditionHandler.UncertaintyLevel.RUNTIME));
        LootConditionHandlers.register(toolChance, new DescriptionHandler("tool_enchantment_chance",
                LootConditionHandler.UncertaintyLevel.PROBABILISTIC));
    }

    /** 自定义条件的轻量 tooltip 描述处理器。 */
    private record DescriptionHandler(String key, LootConditionHandler.UncertaintyLevel level)
            implements LootConditionHandler {
        @Override
        public LootConditionInfo analyze(net.minecraft.world.level.storage.loot.predicates.LootItemCondition condition) {
            ResourceLocation id = net.minecraft.core.registries.BuiltInRegistries.LOOT_CONDITION_TYPE
                    .getKey(condition.getType());
            return new LootConditionInfo(id, Component.translatable(
                    "screen.unsuspiciousblock.archaeology_journal.condition." + this.key), null);
        }

        @Override
        public boolean addsUncertainty() {
            return true;
        }

        @Override
        public LootConditionHandler.UncertaintyLevel uncertaintyLevel() {
            return this.level;
        }
    }
}
