package com.meteorite.unsuspiciousblock.plugin.jade;

import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.block.SealedContents;
import com.meteorite.unsuspiciousblock.block.UnsuspiciousBlock;
import com.meteorite.unsuspiciousblock.blockentity.BrushableBlockEntityScanState;
import com.meteorite.unsuspiciousblock.blockentity.UnsuspiciousBlockEntity;
import net.minecraft.ChatFormatting;
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

    private static final String TAG_SEALED_ITEM = "SealedItem";
    private static final String TAG_CRAFTER_NAME = "CrafterName";

    @Override
    public void register(IWailaCommonRegistration registration) {
        registration.registerBlockDataProvider(
                SealedContentsServerDataProvider.INSTANCE, UnsuspiciousBlockEntity.class);
    }

    @Override
    public void registerClient(IWailaClientRegistration registration) {
        registration.registerBlockComponent(ScannedLootProvider.INSTANCE, BrushableBlock.class);
        registration.registerBlockComponent(SealedContentsProvider.INSTANCE, UnsuspiciousBlock.class);
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
                    .map(JadePlugin::displayCrafterName)
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
            tooltip.add(Component.translatable("jade.unsuspiciousblock.sealed_item")
                    .withStyle(ChatFormatting.GRAY));
            if (sealedItem.isEmpty()) {
                tooltip.append(Component.translatable("jade.unsuspiciousblock.empty")
                        .withStyle(ChatFormatting.DARK_GRAY));
            } else {
                tooltip.append(elements.item(sealedItem, 0.6f));
                tooltip.append(Component.literal(" "));
                tooltip.append(sealedItem.getHoverName().copy().withStyle(ChatFormatting.YELLOW));
                tooltip.append(Component.literal(" ×" + sealedItem.getCount())
                        .withStyle(ChatFormatting.WHITE));
            }

            Component crafterName = data.contains(TAG_CRAFTER_NAME, Tag.TAG_STRING)
                    ? Component.literal(data.getString(TAG_CRAFTER_NAME))
                    : Component.translatable("jade.unsuspiciousblock.unknown_player");
            tooltip.add(Component.translatable("jade.unsuspiciousblock.sealed_by", crafterName)
                    .withStyle(ChatFormatting.AQUA));
        }

        @Override
        public ResourceLocation getUid() {
            return SEALED_CONTENTS_ID;
        }
    }

    // 玩家名缺失时使用 UUID，确保已有身份数据始终可识别
    private static String displayCrafterName(SealedContents.CrafterIdentity identity) {
        return identity.name().isBlank() ? identity.uuid().toString() : identity.name();
    }
}
