package com.meteorite.unsuspiciousblock.plugin.jade;

import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.block.SealedContentsDisplay;
import com.meteorite.unsuspiciousblock.block.UnsuspiciousBlock;
import com.meteorite.unsuspiciousblock.blockentity.BrushableBlockEntityScanState;
import com.meteorite.unsuspiciousblock.blockentity.UnsuspiciousBlockEntity;
import com.meteorite.unsuspiciousblock.client.tooltip.TooltipBuilder;
import com.meteorite.unsuspiciousblock.entity.ShimmerEntity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.BrushableBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import snownee.jade.api.*;
import snownee.jade.api.config.IPluginConfig;
import snownee.jade.api.ui.IElementHelper;

/** Jade 联动——显示已扫描的原版考古战利品与不可疑方块的常驻封存信息。 */
@WailaPlugin(Constants.MOD_ID)
public class JadePlugin implements IWailaPlugin {
    public static final ResourceLocation SCANNED_LOOT_ID =
            ResourceLocation.fromNamespaceAndPath("unsuspiciousblock", "scanned_loot");
    public static final ResourceLocation SEALED_CONTENTS_ID =
            ResourceLocation.fromNamespaceAndPath("unsuspiciousblock", "sealed_contents");
    public static final ResourceLocation SHIMMER_LIFETIME_ID =
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "shimmer_lifetime");

    private static final String TAG_SHIMMER_REMAINING = "ShimmerRemainingTicks";

    private static final String TAG_SEALED_ITEM = "SealedItem";
    private static final String TAG_CRAFTER_NAME = "CrafterName";

    @Override
    public void register(IWailaCommonRegistration registration) {
        registration.registerBlockDataProvider(
                SealedContentsServerDataProvider.INSTANCE, UnsuspiciousBlockEntity.class);
        registration.registerEntityDataProvider(ShimmerLifetimeServerProvider.INSTANCE, ShimmerEntity.class);
    }

    @Override
    public void registerClient(IWailaClientRegistration registration) {
        registration.registerBlockComponent(ScannedLootProvider.INSTANCE, BrushableBlock.class);
        registration.registerBlockComponent(SealedContentsProvider.INSTANCE, UnsuspiciousBlock.class);
        registration.registerEntityComponent(ShimmerLifetimeProvider.INSTANCE, ShimmerEntity.class);
    }

    /*** 按需发送服务端计算的剩余寿命，避免客户端尚未同步的来源和截止时间造成误报。 */
    enum ShimmerLifetimeServerProvider implements IServerDataProvider<EntityAccessor> {
        INSTANCE;

        @Override
        public void appendServerData(CompoundTag data, EntityAccessor accessor) {
            if (accessor.getEntity() instanceof ShimmerEntity shimmer) {
                data.putLong(TAG_SHIMMER_REMAINING, shimmer.getSpawnSource().hasLifetime()
                        ? Math.max(0L, shimmer.getExpiresAt() - shimmer.level().getGameTime()) : -1L);
            }
        }

        @Override
        public ResourceLocation getUid() {
            return SHIMMER_LIFETIME_ID;
        }
    }

    /*** 显示闪烁的光距离自然消失的分秒数；世界生成点单独说明不会自然消失。 */
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

    /** 已扫描原版可疑方块的客户端提示组件。 */
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

    /** 将不可疑方块的封存内容按需发送给当前 Jade 查看者。 */
    enum SealedContentsServerDataProvider implements IServerDataProvider<BlockAccessor> {
        INSTANCE;

        @Override
        public void appendServerData(CompoundTag data, BlockAccessor accessor) {
            if (!(accessor.getBlockEntity() instanceof UnsuspiciousBlockEntity blockEntity)) {
                return;
            }

            ItemStack sealedItem = blockEntity.unsuspiciousblock$getItem().copy();
            data.put(TAG_SEALED_ITEM,
                    accessor.encodeAsNbt(ItemStack.OPTIONAL_STREAM_CODEC, sealedItem));

            blockEntity.getCrafter()
                    .map(SealedContentsDisplay::displayCrafterName)
                    .ifPresent(name -> data.putString(TAG_CRAFTER_NAME, name));
        }

        @Override
        public ResourceLocation getUid() {
            return SEALED_CONTENTS_ID;
        }
    }

    /** 无需扫描即可显示不可疑方块封存内容的 Jade 客户端组件。 */
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
