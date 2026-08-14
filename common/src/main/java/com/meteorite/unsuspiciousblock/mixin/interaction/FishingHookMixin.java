package com.meteorite.unsuspiciousblock.mixin.interaction;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.meteorite.unsuspiciousblock.journal.state.LootSourceType;
import com.meteorite.unsuspiciousblock.journal.tracking.LootSession;
import com.meteorite.unsuspiciousblock.journal.tracking.LootTrackingContext;
import com.meteorite.unsuspiciousblock.journal.tracking.LootTrackingContextHolder;
import com.meteorite.unsuspiciousblock.journal.tracking.RecentLootTableService;
import com.meteorite.unsuspiciousblock.journal.tracking.event.LootTrackingEvents;
import com.meteorite.unsuspiciousblock.journal.tracking.settlement.LootSettlementStrategies;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 在钓鱼收杆流程的 loot roll 期间建立 {@link LootTrackingContextHolder} 作用域，
 * 使 {@link NestedLootTableMixin} 自动捕获钓鱼嵌套子表物品并汇入同一会话。
 * <p>
 * 本 Mixin 仅负责上下文生命周期管理，
 * 不再替换战利品表——战利品注入由平台专属机制处理
 * （Fabric: {@code LootTableEvents.MODIFY}，NeoForge: {@code FishingLootModifier} GLM）。
 */
@Mixin(FishingHook.class)
public abstract class FishingHookMixin {
    @Unique
    private static final ResourceLocation FISHING_ROOT_TABLE =
            ResourceLocation.parse("minecraft:gameplay/fishing");

    // 仅在原版钓鱼表生成物品期间激活上下文，异常时由 Scope 自动恢复栈
    @WrapOperation(
            method = "retrieve",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/storage/loot/LootTable;getRandomItems(Lnet/minecraft/world/level/storage/loot/LootParams;)Lit/unimi/dsi/fastutil/objects/ObjectArrayList;")
    )
    private ObjectArrayList<ItemStack> unsuspiciousblock$trackFishingLoot(
            LootTable lootTable, LootParams lootParams,
            Operation<ObjectArrayList<ItemStack>> original) {
        FishingHook hook = (FishingHook) (Object) this;
        if (!(hook.level() instanceof ServerLevel serverLevel)
                || !(hook.getPlayerOwner() instanceof ServerPlayer sp)) {
            return original.call(lootTable, lootParams);
        }

        RecentLootTableService.record(sp, FISHING_ROOT_TABLE);
        BlockPos pos = hook.blockPosition();
        LootTrackingContext ctx = LootTrackingContext.root(
                sp, FISHING_ROOT_TABLE, LootSourceType.FISHING,
                serverLevel.getGameTime(), serverLevel.getDayTime(),
                pos, null);
        LootSession session = new LootSession(ctx);
        ObjectArrayList<ItemStack> generatedItems;
        try (LootTrackingContextHolder.Scope ignored = LootTrackingContextHolder.open(session, ctx)) {
            generatedItems = original.call(lootTable, lootParams);
        }
        LootTrackingEvents.submit(session, generatedItems, LootSettlementStrategies.immediate());
        return generatedItems;
    }
}
