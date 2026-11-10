package com.meteorite.unsuspiciousblock.client.plugin.jade;

import com.meteorite.unsuspiciousblock.core.block.SealedContentsDisplay;
import com.meteorite.unsuspiciousblock.core.block.UnsuspiciousBlock;
import com.meteorite.unsuspiciousblock.core.blockentity.BrushableBlockEntityScanState;
import com.meteorite.unsuspiciousblock.core.text.TooltipBuilder;
import com.meteorite.unsuspiciousblock.core.entity.ShimmerEntity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.BrushableBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import snownee.jade.api.config.IPluginConfig;
import snownee.jade.api.ui.IElementHelper;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.EntityAccessor;
import snownee.jade.api.IBlockComponentProvider;
import snownee.jade.api.IEntityComponentProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.IWailaClientRegistration;

import static com.meteorite.unsuspiciousblock.core.plugin.jade.JadeData.SCANNED_LOOT_ID;
import static com.meteorite.unsuspiciousblock.core.plugin.jade.JadeData.SEALED_CONTENTS_ID;
import static com.meteorite.unsuspiciousblock.core.plugin.jade.JadeData.SHIMMER_LIFETIME_ID;
import static com.meteorite.unsuspiciousblock.core.plugin.jade.JadeData.TAG_SHIMMER_REMAINING;
import static com.meteorite.unsuspiciousblock.core.plugin.jade.JadeData.TAG_SEALED_ITEM;
import static com.meteorite.unsuspiciousblock.core.plugin.jade.JadeData.TAG_CRAFTER_NAME;

/** Jade 客户端展示，消费服务端数据并保持现有 tooltip 语义。 */
public final class JadeClientProviders {
    private JadeClientProviders() {
    }

    /** 仅由 Jade 的客户端注册回调装配展示组件。 */
    public static void register(IWailaClientRegistration registration) {
        registration.registerBlockComponent(ScannedLootProvider.INSTANCE, BrushableBlock.class);
        registration.registerBlockComponent(SealedContentsProvider.INSTANCE, UnsuspiciousBlock.class);
        registration.registerEntityComponent(ShimmerLifetimeProvider.INSTANCE, ShimmerEntity.class);
    }

    enum ShimmerLifetimeProvider implements IEntityComponentProvider {
        INSTANCE;

        @Override
        public void appendTooltip(ITooltip tooltip, EntityAccessor accessor, IPluginConfig config) {
            if (!(accessor.getEntity() instanceof ShimmerEntity shimmer)) return;

            CompoundTag data = accessor.getServerData();
            if (data.contains(TAG_SHIMMER_REMAINING, Tag.TAG_LONG)) {
                long ticks = data.getLong(TAG_SHIMMER_REMAINING);
                if (ticks < 0L) {
                    tooltip.add(Component.translatable("jade.unsuspiciousblock.shimmer.permanent"));
                } else {
                    // 向上取整，避免尚有不足一秒寿命时提前显示零秒。
                    long seconds = ticks / 20L + (ticks % 20L == 0L ? 0L : 1L);
                    tooltip.add(Component.translatable("jade.unsuspiciousblock.shimmer.remaining",
                            seconds / 60L, seconds % 60L));
                }
            }

            // 采集次数随 entityData 同步，客户端可直接读取；采空后实体会消失，无需处理耗尽状态。
            int panRemaining = shimmer.getPanRemaining();
            if (panRemaining > 0) {
                tooltip.add(Component.translatable("jade.unsuspiciousblock.shimmer.pan_remaining",
                        panRemaining).withStyle(TooltipBuilder.TITLE));
            }
        }

        @Override
        public ResourceLocation getUid() {
            return SHIMMER_LIFETIME_ID;
        }
    }

    enum ScannedLootProvider implements IBlockComponentProvider {
        INSTANCE;

        @Override
        public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
            BlockEntity blockEntity = accessor.getBlockEntity();
            if (!(blockEntity instanceof BrushableBlockEntityScanState scanState)) {
                return;
            }

            if (!scanState.unsuspiciousblock$isScanned()) {
                return;
            }

            Player viewer = accessor.getPlayer();
            if (viewer == null || !scanState.unsuspiciousblock$isScanner(viewer.getUUID())) {
                return;
            }

            ItemStack stack = scanState.unsuspiciousblock$getItem();

            if (stack.isEmpty()) {
                tooltip.add(
                        Component.translatable("jade.unsuspiciousblock.suspicious_reader.empty")
                                .withStyle(TooltipBuilder.LABEL)
                );
                return;
            }

            IElementHelper elements = IElementHelper.get();

            tooltip.add(
                    Component.translatable("jade.unsuspiciousblock.suspicious_reader.prefix")
                            .withStyle(TooltipBuilder.LABEL)
            );
            tooltip.append(elements.item(stack, 0.6f));
            tooltip.append(Component.literal(" "));
            tooltip.append(
                    stack.getHoverName().copy()
                            .withStyle(TooltipBuilder.TITLE)
            );

            if (stack.getCount() > 1) {
                tooltip.append(
                        Component.literal(" ×" + stack.getCount())
                                .withStyle(TooltipBuilder.BODY)
                );
            }
        }

        @Override
        public ResourceLocation getUid() {
            return SCANNED_LOOT_ID;
        }
    }

    enum SealedContentsProvider implements IBlockComponentProvider {
        INSTANCE;

        @Override
        public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
            CompoundTag data = accessor.getServerData();
            Tag encodedItem = data.get(TAG_SEALED_ITEM);
            ItemStack sealedItem = encodedItem == null
                    ? ItemStack.EMPTY
                    : accessor.decodeFromNbt(ItemStack.OPTIONAL_STREAM_CODEC, encodedItem)
                            .orElse(ItemStack.EMPTY);

            IElementHelper elements = IElementHelper.get();
            tooltip.add(SealedContentsDisplay.sealedItemPrefix());
            if (sealedItem.isEmpty()) {
                tooltip.append(SealedContentsDisplay.sealedItemValue(sealedItem));
            } else {
                tooltip.append(elements.item(sealedItem, 0.6f));
                tooltip.append(Component.literal(" "));
                tooltip.append(SealedContentsDisplay.sealedItemValue(sealedItem));
            }

            String crafterName = data.contains(TAG_CRAFTER_NAME, Tag.TAG_STRING)
                    ? data.getString(TAG_CRAFTER_NAME)
                    : null;
            tooltip.add(SealedContentsDisplay.sealedByNameLine(crafterName));
        }

        @Override
        public ResourceLocation getUid() {
            return SEALED_CONTENTS_ID;
        }
    }
}
