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
 * 全局战利品修改器——按指定概率将单个物品追加到目标战利品表生成结果。
 * <p>
 * 配合 {@code neoforge:loot_table_id} condition 精准匹配目标表，
 * 触发判定使用 {@link LootContext#getRandom()}，确保与原版战利品随机性一致。
 */
public class AddItemLootModifier extends LootModifier {

    public static final MapCodec<AddItemLootModifier> CODEC = RecordCodecBuilder.mapCodec(inst ->
            inst.group(
                    IGlobalLootModifier.LOOT_CONDITIONS_CODEC.fieldOf("conditions").forGetter(m -> m.conditions),
                    BuiltInRegistries.ITEM.byNameCodec().fieldOf("item").forGetter(m -> m.item),
                    Codec.floatRange(0.0F, 1.0F)
                            .optionalFieldOf("chance", 1.0F)
                            .forGetter(m -> m.chance)
            ).apply(inst, AddItemLootModifier::new));

    private final Item item;
    private final float chance;

    public AddItemLootModifier(LootItemCondition[] conditionsIn, Item item, float chance) {
        super(conditionsIn);
        this.item = item;
        this.chance = chance;
    }

    @Override
    protected @NotNull ObjectArrayList<ItemStack> doApply(@NotNull ObjectArrayList<ItemStack> generatedLoot, LootContext context) {
        if (context.getRandom().nextFloat() < chance) {
            generatedLoot.add(new ItemStack(item));
        }
        return generatedLoot;
    }

    @Override
    public @NotNull MapCodec<? extends IGlobalLootModifier> codec() {
        return CODEC;
    }
}
