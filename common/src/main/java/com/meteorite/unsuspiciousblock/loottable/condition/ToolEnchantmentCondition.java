package com.meteorite.unsuspiciousblock.loottable.condition;

import com.google.common.collect.ImmutableSet;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.item.enchantment.LevelBasedValue;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.parameters.LootContextParam;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.minecraft.world.level.storage.loot.predicates.LootItemConditionType;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;
import java.util.Set;

/**
 * 工具附魔条件——要求工具带有指定附魔，可选等级门槛与"按该附魔等级变化的通过概率"。
 * <p>
 * 一个类型表达完整的"资格 + 掷骰"语义：只给 {@code enchantment} 时是纯资格门槛，
 * 附带 {@code chance} 时再额外掷一次概率。{@code min_level} 缺省为
 * {@link #DEFAULT_MIN_LEVEL}，即"至少 I 级"——附魔等级必须大于 0 是该类型的固有语义。
 * <p>
 * 与 {@code random_chance} 等原版条件不同，这里按工具实际等级取概率，因此适用于
 * fishing 这类不提供攻击实体的上下文。
 */
public record ToolEnchantmentCondition(Holder<Enchantment> enchantment, int minLevel,
                                       Optional<LevelBasedValue> chance)
        implements LootItemCondition, LootItemCondition.Builder {

    /** 缺省等级门槛——"带有该附魔"即通过，不做额外等级要求。 */
    public static final int DEFAULT_MIN_LEVEL = 1;

    public static final MapCodec<ToolEnchantmentCondition> CODEC = RecordCodecBuilder.mapCodec(instance ->
            instance.group(
                    Enchantment.CODEC.fieldOf("enchantment")
                            .forGetter(ToolEnchantmentCondition::enchantment),
                    Codec.intRange(DEFAULT_MIN_LEVEL, Enchantment.MAX_LEVEL)
                            .optionalFieldOf("min_level", DEFAULT_MIN_LEVEL)
                            .forGetter(ToolEnchantmentCondition::minLevel),
                    LevelBasedValue.CODEC.optionalFieldOf("chance")
                            .forGetter(ToolEnchantmentCondition::chance))
                    .apply(instance, ToolEnchantmentCondition::new));

    // 字段约束由 Codec 表达，构造器只做防御性检查，兼顾代码侧直接构造
    public ToolEnchantmentCondition {
        if (minLevel < DEFAULT_MIN_LEVEL) {
            throw new IllegalArgumentException("min_level must be >= " + DEFAULT_MIN_LEVEL);
        }
    }

    @Override
    public boolean test(LootContext context) {
        ItemStack tool = context.getParamOrNull(LootContextParams.TOOL);
        if (tool == null || tool.isEmpty()) {
            return false;
        }
        int level = tool.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY)
                .getLevel(this.enchantment);
        if (level < this.minLevel) {
            return false;
        }
        return this.chance.isEmpty()
                || context.getRandom().nextFloat() < this.chance.get().calculate(level);
    }

    @Override
    public @NotNull Set<LootContextParam<?>> getReferencedContextParams() {
        return ImmutableSet.of(LootContextParams.TOOL);
    }

    @Override
    public @NotNull LootItemConditionType getType() {
        return ModLootConditions.toolEnchantment();
    }

    @Override
    public @NotNull LootItemCondition build() {
        return this;
    }
}
