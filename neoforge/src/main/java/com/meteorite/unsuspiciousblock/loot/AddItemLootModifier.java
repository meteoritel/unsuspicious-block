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
 * 全局战利品修改器——按指定概率将目标物品替换进考古战利品表生成结果。
 * <p>
 * 配合 {@code neoforge:loot_table_id} condition 精准匹配目标表，
 * 触发判定使用 {@link LootContext#getRandom()}，确保与原版战利品随机性一致。
 * <p>
 * 考古战利品表（如 {@code minecraft:archaeology/trail_ruins_rare}）只允许产出 1 个物品：
 * {@link net.minecraft.world.level.block.entity.BrushableBlockEntity} 在物品数 &gt; 1 时
 * 仅保留首个并丢弃其余。因此本修改器采用"替换"语义——触发时清空原版结果并放入目标物品，
 * 确保注入物品可被实际获得。
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
            // 考古表只允许 1 个物品：清空原版结果后放入目标物品，避免被 BrushableBlockEntity 丢弃
            generatedLoot.clear();
            generatedLoot.add(new ItemStack(item));
        }
        return generatedLoot;
    }

    @Override
    public @NotNull MapCodec<? extends IGlobalLootModifier> codec() {
        return CODEC;
    }
}
