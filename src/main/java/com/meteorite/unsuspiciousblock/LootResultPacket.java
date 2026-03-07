package com.meteorite.unsuspiciousblock;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;

public record LootResultPacket(ItemStack item, BlockPos pos) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<LootResultPacket> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(UnsuspiciousBlock.MOD_ID, "loot_result")
            );

    public static final StreamCodec<RegistryFriendlyByteBuf, LootResultPacket> STREAM_CODEC =
            StreamCodec.composite(
                    ItemStack.OPTIONAL_STREAM_CODEC,
                    LootResultPacket::item,
                    BlockPos.STREAM_CODEC,
                    LootResultPacket::pos,
                    LootResultPacket::new
            );

    public static void handle(LootResultPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null) return;

            BlockPos pos = packet.pos();

            if (packet.item().isEmpty()) {
                Component msg = Component.translatable(
                        "item.unsuspiciousblock.suspicious_reader.result_empty",
                        pos.getX(), pos.getY(), pos.getZ()
                ).withStyle(ChatFormatting.GRAY);
                mc.player.sendSystemMessage(msg);
            } else {
                ItemStack loot = packet.item();

                // "[Suspicious Reader] Block at (x, y, z) contains: ✦ <item> ×<count>"
                Component header = Component.translatable(
                        "item.unsuspiciousblock.suspicious_reader.result_header"
                ).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD);

                Component coords = Component.literal(
                        String.format("(%d, %d, %d)", pos.getX(), pos.getY(), pos.getZ())
                ).withStyle(ChatFormatting.AQUA);

                Component itemName = loot.getHoverName().copy()
                        .withStyle(ChatFormatting.YELLOW);

                Component count = Component.literal(" ×" + loot.getCount())
                        .withStyle(ChatFormatting.WHITE);

                Component full = Component.empty()
                        .append(header)
                        .append(Component.literal(" "))
                        .append(coords)
                        .append(Component.literal(" → ").withStyle(ChatFormatting.GRAY))
                        .append(itemName)
                        .append(count);

                mc.player.sendSystemMessage(full);
            }
        });
    }

    @Override
    @NotNull
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
