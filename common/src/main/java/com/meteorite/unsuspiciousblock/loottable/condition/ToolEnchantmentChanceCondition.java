package com.meteorite.unsuspiciousblock.loottable.condition;

import com.google.common.collect.ImmutableSet;
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

import java.util.Set;

/**
 * 按工具上指定附魔的等级计算随机通过概率，适用于 fishing 等不提供攻击实体的上下文。
 */
public record ToolEnchantmentChanceCondition(Holder<Enchantment> enchantment, LevelBasedValue chance)
        implements LootItemCondition {
    public static final MapCodec<ToolEnchantmentChanceCondition> CODEC = RecordCodecBuilder.mapCodec(instance ->
            instance.group(
                    Enchantment.CODEC.fieldOf("enchantment")
                            .forGetter(ToolEnchantmentChanceCondition::enchantment),
                    LevelBasedValue.CODEC.fieldOf("chance")
                            .forGetter(ToolEnchantmentChanceCondition::chance))
                    .apply(instance, ToolEnchantmentChanceCondition::new));

    @Override
    public boolean test(LootContext context) {
        ItemStack tool = context.getParamOrNull(LootContextParams.TOOL);
        if (tool == null || tool.isEmpty()) {
            return false;
        }
        int level = tool.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY)
                .getLevel(this.enchantment);
        return level > 0 && context.getRandom().nextFloat() < this.chance.calculate(level);
    }

    @Override
    public Set<LootContextParam<?>> getReferencedContextParams() {
        return ImmutableSet.of(LootContextParams.TOOL);
    }

    @Override
    public @NotNull LootItemConditionType getType() {
        return ModLootConditions.toolEnchantmentChance();
    }
}
