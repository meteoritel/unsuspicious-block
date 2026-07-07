package com.meteorite.unsuspiciousblock.loot;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.neoforged.neoforge.common.loot.IGlobalLootModifier;
import net.neoforged.neoforge.common.loot.LootModifier;
import org.jetbrains.annotations.NotNull;

/**
 * 全局战利品修改器——按指定概率向战利品表生成结果追加目标物品（不替换原版战利品）。
 * <p>
 * 配合 {@code neoforge:loot_table_id} condition 精准匹配目标表，
 * 触发判定使用 {@link LootContext#getRandom()}，确保与原版战利品随机性一致。
 * <p>
 * 与 {@link AddItemLootModifier} 的"替换"语义不同，本修改器保留原版所有掉落，
 * 仅在末尾追加 {@code count} 个目标物品，适用于普通战利品箱（如埋藏的宝藏）等
 * 允许多物品产出的场景。
 */
public class InjectItemLootModifier extends LootModifier {

    public static final MapCodec<InjectItemLootModifier> CODEC = RecordCodecBuilder.mapCodec(inst ->
            inst.group(
                    IGlobalLootModifier.LOOT_CONDITIONS_CODEC.fieldOf("conditions").forGetter(m -> m.conditions),
                    BuiltInRegistries.ITEM.byNameCodec().fieldOf("item").forGetter(m -> m.item),
                    Codec.intRange(1, 64)
                            .optionalFieldOf("count", 1)
                            .forGetter(m -> m.count),
                    Codec.floatRange(0.0F, 1.0F)
                            .optionalFieldOf("chance", 1.0F)
                            .forGetter(m -> m.chance)
            ).apply(inst, InjectItemLootModifier::new));

    private final Item item;
    private final int count;
    private final float chance;

    public InjectItemLootModifier(LootItemCondition[] conditionsIn, Item item, int count, float chance) {
        super(conditionsIn);
        this.item = item;
        this.count = count;
        this.chance = chance;
    }

    @Override
    protected @NotNull ObjectArrayList<ItemStack> doApply(@NotNull ObjectArrayList<ItemStack> generatedLoot, LootContext context) {
        if (context.getRandom().nextFloat() < chance) {
            // 追加目标物品，保留原版战利品
            generatedLoot.add(new ItemStack(item, count));
        }
        return generatedLoot;
    }

    @Override
    public @NotNull MapCodec<? extends IGlobalLootModifier> codec() {
        return CODEC;
    }
}
