package com.meteorite.unsuspiciousblock.mixin.interaction;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.meteorite.unsuspiciousblock.enchantment.framework.EnchantmentManager;
import com.meteorite.unsuspiciousblock.enchantment.framework.trigger.TriggerContext;
import com.meteorite.unsuspiciousblock.enchantment.framework.trigger.TriggerType;
import com.meteorite.unsuspiciousblock.journal.state.LootSourceType;
import com.meteorite.unsuspiciousblock.journal.tracking.event.LootTrackingEvents;
import com.meteorite.unsuspiciousblock.loottable.LootTableNames;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootTable;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

/**
 * 在钓鱼收杆流程中替换原版战利品表，并将本模组解析出的钓鱼战利品表
 * 通过 {@link LootTrackingEvents} 发布为战利品发现事件，进入考古笔记追踪流程。
 */
@Mixin(FishingHook.class)
public abstract class FishingHookMixin {
    @Unique
    private ItemStack unsuspiciousblock$fishingRod = ItemStack.EMPTY;

    // 本次收杆最终解析出的钓鱼战利品表标识；仅服务端有效
    @Unique
    @Nullable
    private ResourceLocation unsuspiciousblock$resolvedFishingTable;

    // 本次收杆触发的服务端玩家；用于在 RETURN 时调用追踪器
    @Unique
    @Nullable
    private ServerPlayer unsuspiciousblock$fishingPlayer;

    // 本次收杆从 LootTable.getRandomItems 收集到的物品列表
    @Unique
    private final List<ItemStack> unsuspiciousblock$capturedFishingLoot = new ArrayList<>();

    // 在收杆开始时缓存本次使用的鱼竿
    @Inject(method = "retrieve", at = @At("HEAD"))
    private void unsuspiciousblock$captureFishingRod(ItemStack fishingRod, CallbackInfoReturnable<Integer> cir) {
        this.unsuspiciousblock$fishingRod = fishingRod;
        this.unsuspiciousblock$resolvedFishingTable = null;
        this.unsuspiciousblock$fishingPlayer = null;
        this.unsuspiciousblock$capturedFishingLoot.clear();
    }

    // 在原版查询战利品表参数时改写为本模组解析出的目标表
    @ModifyArg(
            method = "retrieve",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/ReloadableServerRegistries$Holder;getLootTable(Lnet/minecraft/resources/ResourceKey;)Lnet/minecraft/world/level/storage/loot/LootTable;"
            ),
            index = 0
    )
    private ResourceKey<LootTable> unsuspiciousblock$replaceFishingLootTable(ResourceKey<LootTable> originalLootTable) {
        FishingHook fishingHook = (FishingHook) (Object) this;
        if (!(fishingHook.level() instanceof ServerLevel serverLevel)
                || !(fishingHook.getPlayerOwner() instanceof ServerPlayer sp)) {
            return originalLootTable;
        }
        TriggerContext ctx = TriggerContext.builder(sp, serverLevel)
                .tool(this.unsuspiciousblock$fishingRod)
                .targetEntity(fishingHook)
                .build();
        ResourceKey<LootTable> resolved = EnchantmentManager.dispatchValue(TriggerType.FISHING_LOOT_TABLE_QUERY, ctx, originalLootTable);
        // 仅当解析出的表命中追踪规则时记录上下文，避免为未追踪表（如原版主表）写入无意义日志
        if (LootTableNames.isArchaeologyLootTable(resolved.location())) {
            this.unsuspiciousblock$resolvedFishingTable = resolved.location();
            this.unsuspiciousblock$fishingPlayer = sp;
        }
        return resolved;
    }

    // 拦截 getRandomItems 返回的物品列表，复制副本供 RETURN 阶段统一调度追踪器
    @ModifyExpressionValue(
            method = "retrieve",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/storage/loot/LootTable;getRandomItems(Lnet/minecraft/world/level/storage/loot/LootParams;)Lit/unimi/dsi/fastutil/objects/ObjectArrayList;"
            )
    )
    private ObjectArrayList<ItemStack> unsuspiciousblock$captureFishingLootItems(ObjectArrayList<ItemStack> original) {
        if (this.unsuspiciousblock$resolvedFishingTable != null && this.unsuspiciousblock$fishingPlayer != null) {
            for (ItemStack stack : original) {
                if (!stack.isEmpty()) {
                    this.unsuspiciousblock$capturedFishingLoot.add(stack);
                }
            }
        }
        return original;
    }

    // 在收杆结束后将本次钓鱼战利品写入考古笔记追踪流程，并清理缓存字段
    @Inject(method = "retrieve", at = @At("RETURN"))
    private void unsuspiciousblock$trackFishingLootAndClear(ItemStack fishingRod, CallbackInfoReturnable<Integer> cir) {
        ResourceLocation tableId = this.unsuspiciousblock$resolvedFishingTable;
        ServerPlayer player = this.unsuspiciousblock$fishingPlayer;
        if (tableId != null && player != null && !this.unsuspiciousblock$capturedFishingLoot.isEmpty()) {
            ServerLevel serverLevel = (ServerLevel) ((FishingHook) (Object) this).level();
            long gameTime = serverLevel.getGameTime();
            long dayTime = serverLevel.getDayTime();
            for (ItemStack stack : this.unsuspiciousblock$capturedFishingLoot) {
                LootTrackingEvents.publish(player, tableId, stack,
                        LootSourceType.FISHING, gameTime, dayTime);
            }
        }

        this.unsuspiciousblock$resolvedFishingTable = null;
        this.unsuspiciousblock$fishingPlayer = null;
        this.unsuspiciousblock$capturedFishingLoot.clear();
        this.unsuspiciousblock$fishingRod = ItemStack.EMPTY;
    }
}
