package com.meteorite.unsuspiciousblock.network.payload.c2s;

import com.meteorite.unsuspiciousblock.Constants;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/** 管理员批量提交战利品表本地化名称。 */
public record UpdateLootTableTranslationsPayload(List<Entry> entries) implements CustomPacketPayload {
    private static final int MAX_ENTRIES = 4096;
    public static final Type<UpdateLootTableTranslationsPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "update_loot_table_translations"));
    public static final StreamCodec<RegistryFriendlyByteBuf, UpdateLootTableTranslationsPayload> STREAM_CODEC =
            StreamCodec.of(UpdateLootTableTranslationsPayload::encode, UpdateLootTableTranslationsPayload::decode);

    /** 单个语言名称修改；空名称表示删除服务端配置值。 */
    public record Entry(ResourceLocation tableId, String languageCode, String localizedName) {
    }

    public UpdateLootTableTranslationsPayload {
        entries = List.copyOf(entries);
    }

    @Override
    public @NotNull Type<UpdateLootTableTranslationsPayload> type() {
        return TYPE;
    }

    private static void encode(RegistryFriendlyByteBuf buf, UpdateLootTableTranslationsPayload payload) {
        if (payload.entries.size() > MAX_ENTRIES) {
            throw new IllegalArgumentException("Too many loot table translation updates");
        }
        buf.writeVarInt(payload.entries.size());
        payload.entries.forEach(entry -> {
            buf.writeResourceLocation(entry.tableId);
            buf.writeUtf(entry.languageCode, 16);
            buf.writeUtf(entry.localizedName, 128);
        });
    }

    private static UpdateLootTableTranslationsPayload decode(RegistryFriendlyByteBuf buf) {
        int count = buf.readVarInt();
        if (count < 0 || count > MAX_ENTRIES) {
            throw new IllegalArgumentException("Invalid loot table translation update count: " + count);
        }
        List<Entry> entries = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            entries.add(new Entry(buf.readResourceLocation(), buf.readUtf(16), buf.readUtf(128)));
        }
        return new UpdateLootTableTranslationsPayload(entries);
    }
}
