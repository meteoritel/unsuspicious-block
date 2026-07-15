package com.meteorite.unsuspiciousblock.mixin.interaction;

import com.meteorite.unsuspiciousblock.journal.state.LootSourceType;
import com.meteorite.unsuspiciousblock.journal.tracking.LootTrackingContext;
import com.meteorite.unsuspiciousblock.journal.tracking.LootTrackingContextHolder;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 在钓鱼收杆流程中向 {@link LootTrackingContextHolder} 压入追踪上下文，
 * 使 {@link NestedLootTableMixin} 自动捕获钓鱼嵌套子表物品并发布追踪事件。
 * <p>
 * 与旧版 Mixin 不同，本 Mixin 仅负责上下文生命周期管理（push/pop），
 * 不再替换战利品表——战利品注入由平台专属机制处理
 * （Fabric: {@code LootTableEvents.MODIFY}，NeoForge: {@code FishingLootModifier} GLM）。
 */
@Mixin(FishingHook.class)
public abstract class FishingHookMixin {

    @Unique
    private boolean unsuspiciousblock$ctxPushed;

    @Unique
    private static final ResourceLocation FISHING_ROOT_TABLE =
            ResourceLocation.parse("minecraft:gameplay/fishing");

    // 收杆开始时 push 追踪上下文，使 NestedLootTableMixin 自动捕获嵌套子表物品
    @Inject(method = "retrieve", at = @At("HEAD"))
    private void unsuspiciousblock$pushFishingContext(ItemStack fishingRod, CallbackInfoReturnable<Integer> cir) {
        // 异常恢复：上次 retrieve 异常未清理时强制 pop，避免上下文泄漏
        if (this.unsuspiciousblock$ctxPushed) {
            LootTrackingContextHolder.pop();
            this.unsuspiciousblock$ctxPushed = false;
        }

        FishingHook hook = (FishingHook) (Object) this;
        if (!(hook.level() instanceof ServerLevel serverLevel)
                || !(hook.getPlayerOwner() instanceof ServerPlayer sp)) {
            return;
        }

        BlockPos pos = hook.blockPosition();
        LootTrackingContext ctx = LootTrackingContext.root(
                sp, FISHING_ROOT_TABLE, LootSourceType.FISHING,
                serverLevel.getGameTime(), serverLevel.getDayTime(),
                pos, null);
        LootTrackingContextHolder.push(ctx);
        this.unsuspiciousblock$ctxPushed = true;
    }

    // 收杆结束后 pop 追踪上下文并清理状态
    @Inject(method = "retrieve", at = @At("RETURN"))
    private void unsuspiciousblock$popFishingContext(ItemStack fishingRod, CallbackInfoReturnable<Integer> cir) {
        if (this.unsuspiciousblock$ctxPushed) {
            LootTrackingContextHolder.pop();
            this.unsuspiciousblock$ctxPushed = false;
        }
    }
}