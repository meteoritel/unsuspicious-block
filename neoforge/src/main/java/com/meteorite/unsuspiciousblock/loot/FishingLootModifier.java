package com.meteorite.unsuspiciousblock.loot;

import com.meteorite.unsuspiciousblock.journal.state.LootSourceType;
import com.meteorite.unsuspiciousblock.journal.tracking.ArchaeologyLootRuntimeTracker;
import com.meteorite.unsuspiciousblock.journal.tracking.LootSession;
import com.meteorite.unsuspiciousblock.journal.tracking.LootTrackingContext;
import com.meteorite.unsuspiciousblock.journal.tracking.LootTrackingContextHolder;
import com.meteorite.unsuspiciousblock.journal.tracking.event.LootTrackingEvents;
import com.meteorite.unsuspiciousblock.journal.tracking.settlement.LootSettlementStrategies;
import com.meteorite.unsuspiciousblock.loottable.graph.RuntimeLootLinks;
import com.meteorite.unsuspiciousblock.loottable.simulation.LootSimulationScope;
import com.meteorite.unsuspiciousblock.loottable.signature.LootResultSignature;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.neoforged.neoforge.common.loot.IGlobalLootModifier;
import net.neoforged.neoforge.common.loot.LootModifier;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * NeoForge 钓鱼注入器，只负责执行泥地打捞父表。
 * <p>
 * 入口门槛（附魔 + 按等级掷概率）在 {@link #doApply} 里按
 * {@link RuntimeLootLinks#MUD_DREDGING_GATE} 判定，而不是写成 GLM 的 {@code conditions} 数据：
 * 这样门槛与 Fabric 端、与父表页子表入口的说明同源。判定时机与原先作为 GLM 条件时一致
 * （每次施放一次、在抽取子表之前），随机数消耗次序也不变。
 */
public class FishingLootModifier extends LootModifier {
    public static final MapCodec<FishingLootModifier> CODEC = RecordCodecBuilder.mapCodec(instance ->
            instance.group(IGlobalLootModifier.LOOT_CONDITIONS_CODEC.fieldOf("conditions")
                            .forGetter(modifier -> modifier.conditions))
                    .apply(instance, FishingLootModifier::new));

    public FishingLootModifier(LootItemCondition[] conditionsIn) {
        super(conditionsIn);
    }

    @Override
    protected @NotNull ObjectArrayList<ItemStack> doApply(@NotNull ObjectArrayList<ItemStack> generatedLoot,
                                                          LootContext context) {
        ItemStack tool = context.getParamOrNull(LootContextParams.TOOL);
        var origin = context.getParamOrNull(LootContextParams.ORIGIN);
        if (tool == null || tool.isEmpty() || origin == null) {
            return generatedLoot;
        }
        // 门槛没过就什么都不加：等价于原先"GLM 条件不成立时不执行本注入器"
        if (!RuntimeLootLinks.MUD_DREDGING_GATE
                .condition(context.getLevel().registryAccess()).test(context)) {
            return generatedLoot;
        }
        Entity entity = context.getParamOrNull(LootContextParams.THIS_ENTITY);
        LootParams.Builder paramsBuilder = new LootParams.Builder(context.getLevel())
                .withParameter(LootContextParams.ORIGIN, origin)
                .withParameter(LootContextParams.TOOL, tool)
                .withLuck(context.getLuck());
        if (entity != null) {
            paramsBuilder.withOptionalParameter(LootContextParams.THIS_ENTITY, entity);
        }
        LootParams mudParams = paramsBuilder.create(LootContextParamSets.FISHING);
        LootTable lootTable = context.getLevel().getServer().reloadableRegistries()
                .getLootTable(RuntimeLootLinks.MUD_DREDGING_TABLE_KEY);

        if (LootSimulationScope.isActive()) {
            lootTable.getRandomItemsRaw(mudParams, stack -> {
                if (!stack.isEmpty()) {
                    LootSimulationScope.recordChildTableDrop(
                            RuntimeLootLinks.MUD_DREDGING_TABLE_KEY.location(), stack);
                }
                generatedLoot.add(stack);
            });
            return generatedLoot;
        }

        ServerPlayer player = entity instanceof FishingHook hook
                && hook.getPlayerOwner() instanceof ServerPlayer serverPlayer ? serverPlayer : null;
        if (player == null) {
            lootTable.getRandomItemsRaw(mudParams, generatedLoot::add);
            return generatedLoot;
        }

        BlockPos pos = BlockPos.containing(origin);
        LootTrackingContext trackingContext = LootTrackingContext.root(
                player, RuntimeLootLinks.MUD_DREDGING_TABLE_KEY.location(), LootSourceType.FISHING,
                context.getLevel().getGameTime(), context.getLevel().getDayTime(), pos, null);
        LootSession session = new LootSession(trackingContext);
        List<ItemStack> captured = new ArrayList<>();
        Consumer<ItemStack> consumer = stack -> {
            if (!stack.isEmpty()) {
                captured.add(stack.copy());
            }
            generatedLoot.add(stack);
        };
        try (LootTrackingContextHolder.Scope ignored = LootTrackingContextHolder.open(session, trackingContext)) {
            lootTable.getRandomItemsRaw(mudParams, consumer);
        }

        Map<String, Integer> counts = new HashMap<>();
        for (ItemStack stack : captured) {
            LootResultSignature signature = ArchaeologyLootRuntimeTracker.resolveSignature(
                    trackingContext.rootTableId(), stack);
            if (signature != null) {
                counts.merge(signature.toStoredKey(), stack.getCount(), Integer::sum);
            }
        }
        if (!counts.isEmpty()) {
            LootTrackingEvents.submit(session, counts, LootSettlementStrategies.immediate());
        }
        return generatedLoot;
    }

    @Override
    public @NotNull MapCodec<? extends IGlobalLootModifier> codec() {
        return CODEC;
    }
}
