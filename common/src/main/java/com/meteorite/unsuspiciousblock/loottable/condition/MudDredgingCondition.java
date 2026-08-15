package com.meteorite.unsuspiciousblock.loottable.condition;

import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.enchantment.ModEnchantments;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.minecraft.world.level.storage.loot.predicates.LootItemConditionType;
import org.jetbrains.annotations.NotNull;

/**
 * 泥地打捞资格条件，只判断当前工具是否具有泥地打捞附魔。
 * {@code swamp} 字段仅为旧数据包兼容保留，不再参与判断。
 */
public record MudDredgingCondition(boolean swamp) implements LootItemCondition, LootItemCondition.Builder {
    public static final MapCodec<MudDredgingCondition> CODEC = RecordCodecBuilder.mapCodec(instance ->
            instance.group(Codec.BOOL.optionalFieldOf("swamp", false).forGetter(MudDredgingCondition::swamp))
                    .apply(instance, MudDredgingCondition::new));
    public static final ResourceKey<LootTable> MUD_DREDGING = ResourceKey.create(Registries.LOOT_TABLE,
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "gameplay/fishing/mud_dredging"));

    @Override
    public boolean test(LootContext context) {
        ItemStack tool = context.getParamOrNull(LootContextParams.TOOL);
        if (tool == null || tool.isEmpty()) {
            return false;
        }
        int level = tool.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY)
                .getLevel(context.getLevel().holderLookup(Registries.ENCHANTMENT)
                        .getOrThrow(ModEnchantments.MUD_DREDGING));
        return level > 0;
    }

    @Override
    public @NotNull LootItemConditionType getType() {
        return ModLootConditions.mudDredging();
    }

    @Override
    public @NotNull LootItemCondition build() {
        return this;
    }
}
