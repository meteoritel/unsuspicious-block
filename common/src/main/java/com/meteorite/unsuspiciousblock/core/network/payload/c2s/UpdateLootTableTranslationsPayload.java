package com.meteorite.unsuspiciousblock.core.network.payload.c2s;

import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.LootTableNameEdit;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/** 补充表名的条件写入请求。每包按编码字节限制发送。 */
public record UpdateLootTableTranslationsPayload(int requestId, int sequence, boolean last, List<LootTableNameEdit> entries)
        implements CustomPacketPayload {
    public static final int MAX_ENTRIES = 4096;
    private static final int MAX_BATCH_BYTES = 30 * 1024;
    private static final int BATCH_HEADER_BYTES = 16;
    public static final Type<UpdateLootTableTranslationsPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "update_loot_table_translations"));
    public static final StreamCodec<RegistryFriendlyByteBuf, UpdateLootTableTranslationsPayload> STREAM_CODEC =
            StreamCodec.of((buf, payload) -> {
                buf.writeVarInt(payload.requestId());
                buf.writeVarInt(payload.sequence());
                buf.writeBoolean(payload.last());
                buf.writeCollection(payload.entries(), UpdateLootTableTranslationsPayload::encodeEntry);
            }, UpdateLootTableTranslationsPayload::decode);

    @Override
    public Type<UpdateLootTableTranslationsPayload> type() {
        return TYPE;
    }

    /** 用真实线格式计量每项，拆包后服务端仍按整批语言文件提交。 */
    public static List<UpdateLootTableTranslationsPayload> split(int requestId, List<LootTableNameEdit> edits) {
        List<UpdateLootTableTranslationsPayload> packets = new ArrayList<>();
        List<LootTableNameEdit> chunk = new ArrayList<>();
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        int size = BATCH_HEADER_BYTES;
        try {
            for (LootTableNameEdit edit : edits) {
                buffer.clear();
                encodeEntry(buffer, edit);
                int bytes = buffer.readableBytes();
                if (BATCH_HEADER_BYTES + bytes > MAX_BATCH_BYTES) {
                    throw new IllegalArgumentException(edit.tableId().toString());
                }
                if (size + bytes > MAX_BATCH_BYTES) {
                    packets.add(new UpdateLootTableTranslationsPayload(requestId, packets.size(), false, List.copyOf(chunk)));
                    chunk.clear();
                    size = BATCH_HEADER_BYTES;
                }
                chunk.add(edit);
                size += bytes;
            }
            packets.add(new UpdateLootTableTranslationsPayload(requestId, packets.size(), true, List.copyOf(chunk)));
            return List.copyOf(packets);
        } finally {
            buffer.release();
        }
    }

    private static void encodeEntry(FriendlyByteBuf buf, LootTableNameEdit entry) {
        buf.writeResourceLocation(entry.tableId());
        buf.writeUtf(entry.language(), LootTableNameEdit.MAX_LANGUAGE_LENGTH);
        buf.writeUtf(entry.value(), LootTableNameEdit.MAX_WIRE_NAME_LENGTH);
        buf.writeUtf(entry.expected(), LootTableNameEdit.MAX_WIRE_NAME_LENGTH);
        buf.writeUtf(entry.legacyKey());
    }

    private static UpdateLootTableTranslationsPayload decode(RegistryFriendlyByteBuf buf) {
        int requestId = buf.readVarInt();
        int sequence = buf.readVarInt();
        boolean last = buf.readBoolean();
        int count = buf.readVarInt();
        if (count < 0 || count > MAX_ENTRIES) throw new IllegalArgumentException("Invalid loot table name count");
        List<LootTableNameEdit> entries = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            entries.add(new LootTableNameEdit(buf.readResourceLocation(),
                    buf.readUtf(LootTableNameEdit.MAX_LANGUAGE_LENGTH),
                    buf.readUtf(LootTableNameEdit.MAX_WIRE_NAME_LENGTH),
                    buf.readUtf(LootTableNameEdit.MAX_WIRE_NAME_LENGTH), buf.readUtf()));
        }
        return new UpdateLootTableTranslationsPayload(requestId, sequence, last, List.copyOf(entries));
    }
}
