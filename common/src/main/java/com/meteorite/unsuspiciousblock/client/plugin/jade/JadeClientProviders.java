package com.meteorite.unsuspiciousblock.client.plugin.jade;

import com.meteorite.unsuspiciousblock.core.block.SealedContentsDisplay;
import com.meteorite.unsuspiciousblock.core.block.UnsuspiciousBlock;
import com.meteorite.unsuspiciousblock.core.blockentity.BrushableBlockEntityScanState;
import com.meteorite.unsuspiciousblock.core.entity.ShimmerEntity;
import com.meteorite.unsuspiciousblock.core.text.TooltipBuilder;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.BrushableBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.EntityAccessor;
import snownee.jade.api.IBlockComponentProvider;
import snownee.jade.api.IEntityComponentProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.IWailaClientRegistration;
import snownee.jade.api.config.IPluginConfig;
import snownee.jade.api.ui.IElementHelper;

import static com.meteorite.unsuspiciousblock.core.plugin.jade.JadeData.SCANNED_LOOT_ID;
import static com.meteorite.unsuspiciousblock.core.plugin.jade.JadeData.SEALED_CONTENTS_ID;
import static com.meteorite.unsuspiciousblock.core.plugin.jade.JadeData.SHIMMER_STATUS_ID;
import static com.meteorite.unsuspiciousblock.core.plugin.jade.JadeData.TAG_CRAFTER_NAME;
import static com.meteorite.unsuspiciousblock.core.plugin.jade.JadeData.TAG_SEALED_ITEM;
import static com.meteorite.unsuspiciousblock.core.plugin.jade.JadeData.TAG_SHIMMER_DEADLINE;

/** Jade 客户端展示，消费服务端数据并保持现有 tooltip 语义。 */
public final class JadeClientProviders {
    private JadeClientProviders() {
    }

    /** 仅由 Jade 的客户端注册回调装配展示组件。 */
    public static void register(IWailaClientRegistration registration) {
        registration.registerBlockComponent(ScannedLootProvider.INSTANCE, BrushableBlock.class);
        registration.registerBlockComponent(SealedContentsProvider.INSTANCE, UnsuspiciousBlock.class);
        registration.registerEntityComponent(ShimmerStatusProvider.INSTANCE, ShimmerEntity.class);
    }

    enum ShimmerStatusProvider implements IEntityComponentProvider {
        INSTANCE;

        private static final String KEY = "jade.unsuspiciousblock.shimmer.";
        private static final int SECONDS_PER_MINUTE = SharedConstants.TICKS_PER_MINUTE / SharedConstants.TICKS_PER_SECOND;

        @Override
        public void appendTooltip(ITooltip tooltip, EntityAccessor accessor, IPluginConfig config) {
            if (!(accessor.getEntity() instanceof ShimmerEntity shimmer)) return;

            boolean permanent = shimmer.isPermanent();
            tooltip.add(statusLine("type", Component.translatable(KEY + (permanent ? "permanent" : "temporary"))));
            tooltip.add(statusLine("pan_remaining", Component.literal(Integer.toString(shimmer.getPanRemaining()))));

            CompoundTag data = accessor.getServerData();
            if (!data.contains(TAG_SHIMMER_DEADLINE, Tag.TAG_LONG)) {
                return;
            }

            long ticksLeft = data.getLong(TAG_SHIMMER_DEADLINE) - shimmer.level().getGameTime();
            // 恢复完成后，等待下一份 Jade 数据刷新恢复行。
            if (ticksLeft <= 0L && permanent) return;

            tooltip.add(statusLine(permanent ? "recovers_in" : "disappears_in", formatDuration(Math.max(0L, ticksLeft))));
        }

        private static Component statusLine(String label, MutableComponent value) {
            return Component.translatable(KEY + label, value.withStyle(TooltipBuilder.BODY))
                    .withStyle(TooltipBuilder.LABEL);
        }

        private static MutableComponent formatDuration(long ticks) {
            long seconds = Math.ceilDiv(ticks, SharedConstants.TICKS_PER_SECOND);
            if (seconds < SECONDS_PER_MINUTE) {
                return Component.translatable(KEY + "duration_seconds", seconds);
            }
            return Component.translatable(KEY + "duration_minutes_seconds",
                    seconds / SECONDS_PER_MINUTE, seconds % SECONDS_PER_MINUTE);
        }

        @Override
        public ResourceLocation getUid() {
            return SHIMMER_STATUS_ID;
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
