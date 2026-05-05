package com.meteorite.unsuspiciousblock.plugin.jade;

import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.blockentity.BrushableBlockEntityScanState;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.BrushableBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BrushableBlockEntity;
import snownee.jade.api.*;
import snownee.jade.api.config.IPluginConfig;
import snownee.jade.api.ui.IElementHelper;

/** Jade 联动——在可疑方块上显示已扫描的战利品信息 */
@WailaPlugin(Constants.MOD_ID)
public class JadePlugin implements IWailaPlugin {
    public static final ResourceLocation SCANNED_LOOT_ID =
            ResourceLocation.fromNamespaceAndPath("unsuspiciousblock", "scanned_loot");

    @Override
    public void registerClient(IWailaClientRegistration registration) {
        registration.registerBlockComponent(ScannedLootProvider.INSTANCE, BrushableBlock.class);
    }

    enum ScannedLootProvider implements IBlockComponentProvider {
        INSTANCE;

        @Override
        public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
            BlockEntity blockEntity = accessor.getBlockEntity();
            if (!(blockEntity instanceof BrushableBlockEntity brushable)) {
                return;
            }

            BrushableBlockEntityScanState scanState = (BrushableBlockEntityScanState) brushable;
            if (!scanState.unsuspiciousblock$isScanned()) {
                return;
            }

            Player viewer = accessor.getPlayer();
            if (viewer == null || !scanState.unsuspiciousblock$isScanner(viewer.getUUID())) {
                return;
            }

            ItemStack stack = brushable.getItem();

            if (stack.isEmpty()) {
                tooltip.add(
                        Component.translatable("item.unsuspiciousblock.suspicious_reader.jade_empty")
                                .withStyle(style -> style.withColor(0xAAAAAA))
                );
                return;
            }

            IElementHelper elements = IElementHelper.get();

            tooltip.add(
                    Component.translatable("item.unsuspiciousblock.suspicious_reader.jade_prefix")
                            .withStyle(style -> style.withColor(0xAAAAAA))
            );
            tooltip.append(elements.item(stack, 0.6f));
            tooltip.append(Component.literal(" "));
            tooltip.append(
                    stack.getHoverName().copy()
                            .withStyle(style -> style.withColor(0xFFE040))
            );

            if (stack.getCount() > 1) {
                tooltip.append(
                        Component.literal(" ×" + stack.getCount())
                                .withStyle(style -> style.withColor(0xFFFFFF))
                );
            }
        }

        @Override
        public ResourceLocation getUid() {
            return SCANNED_LOOT_ID;
        }
    }
}
