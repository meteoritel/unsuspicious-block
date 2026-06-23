package com.meteorite.unsuspiciousblock.enchantment.framework.builtin;

import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.enchantment.framework.effect.EffectContext;
import com.meteorite.unsuspiciousblock.enchantment.framework.effect.EnchantmentEffect;
import com.meteorite.unsuspiciousblock.enchantment.framework.trigger.TriggerContext;
import com.meteorite.unsuspiciousblock.enchantment.framework.trigger.TriggerType;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;

/**
 * 织物采集附魔效果：剪羊毛时按概率额外掉落线。
 * <p>
 * 注册到 {@link TriggerType#ENTITY_SHEAR}，由平台 Adapter / Fabric Mixin
 * 在原版剪毛逻辑执行前触发。
 */
public final class TextileRecoveryEffect implements EnchantmentEffect {
    private static final double EXTRA_STRING_CHANCE = 0.15D;
    private static final int EXTRA_STRING_MIN = 1;
    private static final int EXTRA_STRING_MAX = 2;

    @Override
    public void apply(EffectContext<?> ctx) {
        var triggerCtx = ctx.triggerContext();
        if (triggerCtx.targetEntity instanceof Sheep sheep) {
            handleSheepShear(triggerCtx, sheep);
        }
    }

    // 剪羊毛额外掉线
    private void handleSheepShear(TriggerContext triggerCtx, Sheep sheep) {
        if (triggerCtx.player.getRandom().nextDouble() >= EXTRA_STRING_CHANCE) {
            return;
        }
        int count = EXTRA_STRING_MIN + triggerCtx.player.getRandom().nextInt(EXTRA_STRING_MAX - EXTRA_STRING_MIN + 1);
        Block.popResource(triggerCtx.level, sheep.blockPosition(), new ItemStack(Items.STRING, count));
    }
}
