package com.meteorite.unsuspiciousblock;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.BrushableBlock;
import net.minecraft.world.level.block.entity.BrushableBlockEntity;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import snownee.jade.api.*;
import snownee.jade.api.config.IPluginConfig;
import snownee.jade.api.ui.IElementHelper;

import javax.annotation.Nullable;
import java.util.Optional;

// jade联动类
@WailaPlugin
public class JadePlugin implements IWailaPlugin {
    private static final Logger LOGGER = LogManager.getLogger(UnsuspiciousBlock.MOD_ID);
    public static final ResourceLocation SCANNED_LOOT_ID =
            ResourceLocation.fromNamespaceAndPath("unsuspiciousblock", "scanned_loot");

    private static final String TAG_ITEM = "unsuspiciousblock_item";
    private static final String TAG_SCANNED = "unsuspiciousblock_scanned";

    @Override
    public void register(IWailaCommonRegistration registration) {
        registration.registerBlockDataProvider(ScannedLootProvider.INSTANCE, BrushableBlockEntity.class);
    }

    @Override
    public void registerClient(IWailaClientRegistration registration) {
        registration.registerBlockComponent(ScannedLootProvider.INSTANCE, BrushableBlock.class);
        registration.markAsServerFeature(SCANNED_LOOT_ID);
    }

    enum ScannedLootProvider implements IBlockComponentProvider, StreamServerDataProvider<BlockAccessor, Optional<ItemStack>> {
        INSTANCE;

        @Override
        public Optional<ItemStack> streamData(BlockAccessor accessor) {
            if (!(accessor.getBlockEntity() instanceof BrushableBlockEntity brushable)) return Optional.empty();

            long posKey = brushable.getBlockPos().asLong();
            ItemStack cached = SuspiciousReaderItem.SCAN_CACHE.get(posKey);

            // null → 未扫描，接口默认实现会跳过写入，客户端 decodeFromData 返回 empty
            if (cached == null) return Optional.empty();

            LOGGER.debug("jade streamData called at {}", brushable.getBlockPos());

            // 已扫描：用 Optional 包装（stack 本身可能 isEmpty）
            return Optional.of(cached);
        }

        @Override
        public StreamCodec<RegistryFriendlyByteBuf, Optional<ItemStack>> streamCodec() {
            return ByteBufCodecs.optional(ItemStack.STREAM_CODEC);
        }

        @Override
        public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {

            Optional<Optional<ItemStack>> decoded = decodeFromData(accessor);
            if (decoded.isEmpty()) return;

            Optional<ItemStack> scannedResult = decoded.get();

            // 内层为 empty 或 stack 本身为空 → 已扫描但方块无物品
            if (scannedResult.isEmpty() || scannedResult.get().isEmpty()) {
                tooltip.add(
                        Component.translatable("item.unsuspiciousblock.suspicious_reader.jade_empty")
                                .withStyle(style -> style.withColor(0xAAAAAA))
                );
                return;
            }

            ItemStack stack = scannedResult.get();
            IElementHelper elements = IElementHelper.get();

            // 前缀
            tooltip.add(Component.translatable(
                            "item.unsuspiciousblock.suspicious_reader.jade_prefix")
                    .withStyle(style -> style.withColor(0xAAAAAA)));

            // 物品图标
            tooltip.append(elements.item(stack));

            // 物品名称
            tooltip.append(stack.getHoverName().copy()
                    .withStyle(style -> style.withColor(0xFFE040)));

            // 数量（仅在 count > 1 时显示）
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

        @Override
        public boolean shouldRequestData(BlockAccessor accessor) {
            LOGGER.debug("should request 调用 ");
            return StreamServerDataProvider.super.shouldRequestData(accessor);
        }
    }
}
