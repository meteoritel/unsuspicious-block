package com.meteorite.unsuspiciousblock;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.BrushableBlock;
import snownee.jade.api.*;
import snownee.jade.api.config.IPluginConfig;
import snownee.jade.api.ui.IElementHelper;

// jade联动类
@WailaPlugin(UnsuspiciousBlock.MOD_ID)
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
            long posKey = accessor.getPosition().asLong();

            // containsKey 区分"未扫描(不存在)"和"扫描了但是空(EMPTY)"两种状态
            if (!SuspiciousReaderItem.SCAN_CACHE.containsKey(posKey)) return;

            ItemStack stack = SuspiciousReaderItem.SCAN_CACHE.get(posKey);

            if (stack == null || stack.isEmpty()) {
                tooltip.add(
                        Component.translatable("item.unsuspiciousblock.suspicious_reader.jade_empty")
                                .withStyle(style -> style.withColor(0xAAAAAA))
                );
                return;
            }

            IElementHelper elements = IElementHelper.get();

            // 开头
            tooltip.add(
                    Component.translatable("item.unsuspiciousblock.suspicious_reader.jade_prefix")
                            .withStyle(style -> style.withColor(0xAAAAAA))
            );
            // 图标，太大了缩放一下
            tooltip.append(elements.item(stack, 0.6f));
            tooltip.append(Component.literal(" "));
            // 物品名称与数量
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
