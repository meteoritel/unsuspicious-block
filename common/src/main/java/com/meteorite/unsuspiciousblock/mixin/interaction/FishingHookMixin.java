package com.meteorite.unsuspiciousblock.mixin.interaction;

import com.meteorite.unsuspiciousblock.enchantment.framework.EnchantmentManager;
import com.meteorite.unsuspiciousblock.enchantment.framework.trigger.TriggerContext;
import com.meteorite.unsuspiciousblock.enchantment.framework.trigger.TriggerType;
import com.meteorite.unsuspiciousblock.journal.state.LootSourceType;
import com.meteorite.unsuspiciousblock.journal.tracking.LootTrackingContext;
import com.meteorite.unsuspiciousblock.journal.tracking.LootTrackingContextHolder;
import com.meteorite.unsuspiciousblock.loottable.LootTableNames;
import net.minecraft.core.BlockPos;
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

/**
 * 在钓鱼收杆流程中替换原版战利品表，并向 {@link LootTrackingContextHolder} 压入追踪上下文，
 * 使 {@link NestedLootTableMixin} 自动捕获钓鱼主表内部 fish/junk/treasure 子表物品。
 * <p>
 * 主表 {@code minecraft:gameplay/fishing} 本身不产生物品（仅 NestedLootTable 容器），
 * 所有物品由子表产出并由子表 Mixin 发布事件；本 Mixin 不再直接 publish，
 * 仅负责上下文生命周期管理（HEAD push / RETURN pop）。
 */
@Mixin(FishingHook.class)
public abstract class FishingHookMixin {
    @Unique
    private ItemStack unsuspiciousblock$fishingRod = ItemStack.EMPTY;

    // 本次收杆解析出的钓鱼战利品表标识；仅服务端有效
    @Unique
    @Nullable
    private ResourceLocation unsuspiciousblock$resolvedFishingTable;

    // 本次收杆触发的服务端玩家；用于在 RETURN 时调用追踪器
    @Unique
    @Nullable
    private ServerPlayer unsuspiciousblock$fishingPlayer;

    // 标记本次收杆是否已 push 追踪上下文，RETURN 时据此 pop
    @Unique
    private boolean unsuspiciousblock$ctxPushed;

    // 在收杆开始时缓存本次使用的鱼竿并重置上下文状态
    @Inject(method = "retrieve", at = @At("HEAD"))
    private void unsuspiciousblock$captureFishingRod(ItemStack fishingRod, CallbackInfoReturnable<Integer> cir) {
        this.unsuspiciousblock$fishingRod = fishingRod;
        this.unsuspiciousblock$resolvedFishingTable = null;
        this.unsuspiciousblock$fishingPlayer = null;
        // 异常恢复：上次 retrieve 异常未清理时强制 pop，避免上下文泄漏
        if (this.unsuspiciousblock$ctxPushed) {
            LootTrackingContextHolder.pop();
            this.unsuspiciousblock$ctxPushed = false;
        }
    }

    // 在原版查询战利品表参数时改写为本模组解析出的目标表，并 push 追踪上下文
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
        // 仅当解析出的表命中追踪规则时记录上下文并 push，避免为未追踪表写入无意义状态
        if (LootTableNames.isArchaeologyLootTable(resolved.location())) {
            this.unsuspiciousblock$resolvedFishingTable = resolved.location();
            this.unsuspiciousblock$fishingPlayer = sp;
            long gameTime = serverLevel.getGameTime();
            long dayTime = serverLevel.getDayTime();
            // 钓鱼位置取鱼漂位置（物品实际生成的位置）
            BlockPos pos = fishingHook.blockPosition();
            LootTrackingContext trackingCtx = LootTrackingContext.root(
                    sp, resolved.location(), LootSourceType.FISHING, gameTime, dayTime, pos, null);
            LootTrackingContextHolder.push(trackingCtx);
            this.unsuspiciousblock$ctxPushed = true;
        }
        return resolved;
    }

    // 在收杆结束后 pop 追踪上下文并清理缓存字段
    @Inject(method = "retrieve", at = @At("RETURN"))
    private void unsuspiciousblock$popTrackingContext(ItemStack fishingRod, CallbackInfoReturnable<Integer> cir) {
        if (this.unsuspiciousblock$ctxPushed) {
            LootTrackingContextHolder.pop();
            this.unsuspiciousblock$ctxPushed = false;
        }
        this.unsuspiciousblock$resolvedFishingTable = null;
        this.unsuspiciousblock$fishingPlayer = null;
        this.unsuspiciousblock$fishingRod = ItemStack.EMPTY;
    }
}
