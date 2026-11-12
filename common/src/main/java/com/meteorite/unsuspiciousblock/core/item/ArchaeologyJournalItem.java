package com.meteorite.unsuspiciousblock.core.item;

import com.meteorite.unsuspiciousblock.core.text.ClientTooltipBridge;
import com.meteorite.unsuspiciousblock.core.text.TooltipBuilder;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public class ArchaeologyJournalItem extends Item {
    public ArchaeologyJournalItem(Properties properties) {
        super(properties);
    }

    @Override
    public void appendHoverText(@NotNull ItemStack stack, @NotNull TooltipContext context,
                                @NotNull List<Component> tooltipLines, @NotNull TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltipLines, flag);
        TooltipBuilder tooltip = new TooltipBuilder(tooltipLines);
        tooltip.intro("item.unsuspiciousblock.archaeology_journal.tooltip.desc");
        Component openKey = Component.keybind("key.unsuspiciousblock.open_journal")
                .withStyle(TooltipBuilder.ACCENT);
        tooltip.hint("item.unsuspiciousblock.archaeology_journal.tooltip.open", openKey);
    }

    @Override
    public @NotNull InteractionResultHolder<ItemStack> use(Level level, @NotNull Player player, @NotNull InteractionHand hand) {
        if (level.isClientSide()) {
            // 经桥接打开客户端界面：服务端可达类不直接加载 client 包
            ClientTooltipBridge.openJournal();
        }
        return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand), level.isClientSide());
    }
}
