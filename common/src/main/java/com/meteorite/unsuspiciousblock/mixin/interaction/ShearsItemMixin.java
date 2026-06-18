package com.meteorite.unsuspiciousblock.mixin.interaction;

import com.meteorite.unsuspiciousblock.enchantment.framework.EnchantmentManager;
import com.meteorite.unsuspiciousblock.enchantment.framework.trigger.TriggerContext;
import com.meteorite.unsuspiciousblock.enchantment.framework.trigger.TriggerType;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ShearsItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 在剪刀破坏树叶后通过 framework 触发织物回收附魔效果。
 */
@Mixin(ShearsItem.class)
public abstract class ShearsItemMixin {

    // 在原版剪刀挖掘结束后通过 framework 触发额外树叶掉落判定
    @Inject(method = "mineBlock", at = @At("TAIL"))
    private void unsuspiciousblock$dropExtraLeafLoot(ItemStack stack, Level level, BlockState state,
                                                     BlockPos pos, LivingEntity miningEntity,
                                                     CallbackInfoReturnable<Boolean> cir) {
        if (miningEntity instanceof ServerPlayer sp && level instanceof ServerLevel serverLevel) {
            TriggerContext ctx = TriggerContext.builder(sp, serverLevel)
                    .pos(pos)
                    .blockState(state)
                    .tool(stack)
                    .build();
            EnchantmentManager.dispatch(TriggerType.TOOL_MINE_BLOCK, ctx);
        }
    }
}