package com.meteorite.unsuspiciousblock.loottable.condition;

import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.loottable.analysis.LootConditionHandler;
import com.meteorite.unsuspiciousblock.loottable.analysis.LootConditionHandlers;
import com.meteorite.unsuspiciousblock.loottable.analysis.LootConditionInfo;
import com.mojang.serialization.MapCodec;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.enchantment.LevelBasedValue;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.minecraft.world.level.storage.loot.predicates.LootItemConditionType;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 模组战利品条件类型注册中心——暴露 codec 并持有平台注册后的 {@link LootItemConditionType}。
 * <p>
 * 只注册唯一的规范名 {@link #TOOL_ENCHANTMENT}：它同时覆盖"工具附魔资格"与"按附魔等级掷概率"，
 * 旧的两个注册名与 legacy 字段已随内置资源一并移除，不承担跨版本兼容。
 * <p>
 * 实际注册由平台代码完成：NeoForge 使用 {@code DeferredRegister}，
 * Fabric 使用 {@code Registry.register()}。
 */
public final class ModLootConditions {

    /** 规范条件名——"工具附魔门槛 + 可选按等级变化的概率"。 */
    public static final ResourceLocation TOOL_ENCHANTMENT =
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "tool_enchantment");

    public static final MapCodec<ToolEnchantmentCondition> TOOL_ENCHANTMENT_CODEC =
            ToolEnchantmentCondition.CODEC;

    private static Supplier<LootItemConditionType> toolEnchantmentType;

    private ModLootConditions() {
    }

    // 由平台代码在注册完成后调用，设置已注册的类型持有者
    public static void setToolEnchantmentType(Supplier<LootItemConditionType> type) {
        toolEnchantmentType = type;
    }

    // 获取已注册的工具附魔条件类型
    public static LootItemConditionType toolEnchantment() {
        return toolEnchantmentType.get();
    }

    /**
     * 注册该条件的静态描述。
     * <p>
     * 父行是附魔的展示名（取自附魔自带的 description 组件，无需手工拼 key）；
     * 等级门槛仅在 {@code min_level > 1} 时出子行，概率子行仅在给出 {@code chance} 时出现，
     * 避免"需要等级 ≥ 1"与"无额外概率"这类噪音行。
     */
    public static void registerAnalysisHandlers() {
        LootConditionHandlers.register(TOOL_ENCHANTMENT, new ToolEnchantmentDescriptionHandler());
    }

    /**
     * 工具附魔条件的展示处理器。
     * <p>
     * {@code Constant} 与 {@code Linear} 的取值能在一行内准确表达；其余 {@link LevelBasedValue}
     * 变体不保证单调递增，因此用中性文案并标记 {@code partial}，不声称概率随等级提升。
     */
    private static final class ToolEnchantmentDescriptionHandler implements LootConditionHandler {
        @Override
        public LootConditionInfo analyze(LootItemCondition condition) {
            if (!(condition instanceof ToolEnchantmentCondition toolCondition)) {
                return null;
            }
            List<LootConditionInfo> children = new ArrayList<>();
            if (toolCondition.minLevel() > ToolEnchantmentCondition.DEFAULT_MIN_LEVEL) {
                children.add(row("tool_enchantment_min_level", toolCondition.minLevel()));
            }
            Optional<LevelBasedValue> chance = toolCondition.chance();
            if (chance.isPresent()) {
                children.add(chanceRow(chance.get()));
            }
            return new LootConditionInfo(TOOL_ENCHANTMENT,
                    toolCondition.enchantment().value().description(), null, children);
        }

        private LootConditionInfo chanceRow(LevelBasedValue chance) {
            if (chance instanceof LevelBasedValue.Constant(float value)) {
                return row("tool_enchantment_chance_constant", Math.round(value * 100));
            }
            if (chance instanceof LevelBasedValue.Linear(float base, float perLevelAboveFirst)) {
                return row("tool_enchantment_chance_linear",
                        Math.round(base * 100), Math.round(perLevelAboveFirst * 100));
            }
            return LootConditionHandlers.partial(row("tool_enchantment_chance_dynamic"));
        }

        private LootConditionInfo row(String key, Object... args) {
            return new LootConditionInfo(TOOL_ENCHANTMENT,
                    Component.translatable(LootConditionHandlers.I18N_PREFIX + key, args), null);
        }

        @Override
        public boolean addsUncertainty() {
            return true;
        }

        @Override
        public LootConditionHandler.UncertaintyLevel uncertaintyLevel() {
            // 资格的成立依赖工具运行时状态（附魔等级），概率也只在场景假定的等级下成立
            return LootConditionHandler.UncertaintyLevel.RUNTIME;
        }
    }
}
