package com.meteorite.unsuspiciousblock.mixin.interaction;

import com.meteorite.unsuspiciousblock.loottable.ArchaeologyLootInjectors;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BrushableBlockEntity;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Fabric 平台考古战利品注入入口——拦截 BrushableBlockEntity.unpackLootTable 中的
 * LootTable.getRandomItems 调用，通过 {@link ArchaeologyLootInjectors} 执行注入。
 * <p>
 * 考古战利品表只允许产出 1 个物品，BrushableBlockEntity 在物品数 &gt; 1 时仅保留首个并丢弃其余。
 * 因此不能通过 LootTableEvents.MODIFY 追加 pool（会导致注入物品被丢弃），
 * 必须在战利品生成后直接替换列表内容。NeoForge 端通过 GlobalLootModifier 实现等价语义。
 * <p>
 * 注入逻辑由 {@code FabricArchaeologyLootInjector} 统一实现，本 mixin 仅作为调用入口；
 * 概率模拟器同样调用注入器，确保模拟与实际游戏流程行为一致，使考古笔记能正确记录注入物品。
 */
@Mixin(BrushableBlockEntity.class)
public abstract class BrushableBlockEntityMixin {

    @Shadow
    @Nullable
    private ResourceKey<LootTable> lootTable;

    @Redirect(
            method = "unpackLootTable",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/storage/loot/LootTable;getRandomItems(Lnet/minecraft/world/level/storage/loot/LootParams;J)Lit/unimi/dsi/fastutil/objects/ObjectArrayList;")
    )
    private ObjectArrayList<ItemStack> unsuspiciousblock$redirectGetRandomItems(LootTable table, LootParams params, long seed) {
        ObjectArrayList<ItemStack> items = table.getRandomItems(params, seed);
        if (this.lootTable != null) {
            // 用 seed 构造确定性随机源，保持同一可疑方块的判定结果稳定（避免 save/load 后变化）
            ArchaeologyLootInjectors.get().maybeReplace(this.lootTable.location(), items, RandomSource.create(seed));
        }
        return items;
    }
}
