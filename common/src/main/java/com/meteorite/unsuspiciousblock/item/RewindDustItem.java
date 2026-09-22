package com.meteorite.unsuspiciousblock.item;

import com.meteorite.unsuspiciousblock.client.tooltip.TooltipBuilder;
import com.meteorite.unsuspiciousblock.world.StructureRewindService;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/***
 * 回溯粉：对结构部件使用后，在原位置逐区块重新放置结构。
 */
public class RewindDustItem extends Item {

    // 简介文本的本地化键（item.unsuspiciousblock.<name>.tooltip.desc）
    private final String descriptionKey;

    public RewindDustItem(Properties properties, String descriptionKey) {
        super(properties);
        this.descriptionKey = descriptionKey;
    }

    @Override
    public @NotNull InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer();
        if (player == null || !player.mayBuild()) {
            return InteractionResult.FAIL;
        }
        if (player.getCooldowns().isOnCooldown(this)) {
            return InteractionResult.FAIL;
        }
        if (!(context.getLevel() instanceof ServerLevel level)) {
            return InteractionResult.SUCCESS;
        }
        if (!level.mayInteract(player, context.getClickedPos())) {
            return InteractionResult.FAIL;
        }
        if (!StructureRewindService.start(level, context.getClickedPos(), player)) {
            return InteractionResult.FAIL;
        }
        if (!player.getAbilities().instabuild) {
            context.getItemInHand().shrink(1);
        }
        player.getCooldowns().addCooldown(this, 20);
        return InteractionResult.CONSUME;
    }

    @Override
    public void appendHoverText(@NotNull ItemStack stack, @NotNull TooltipContext context,
                                @NotNull List<Component> tooltipLines, @NotNull TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltipLines, flag);
        TooltipBuilder tooltip = new TooltipBuilder(tooltipLines);
        tooltip.intro(descriptionKey);
        tooltip.hint("item.unsuspiciousblock.rewind_dust.tooltip.use");
        tooltip.hint("item.unsuspiciousblock.rewind_dust.tooltip.warning");
    }
}
