package com.meteorite.unsuspiciousblock.core.network.payload.c2s;

import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.core.loottable.LootTrackingRules.TableEdit;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/** 提交基于指定世界配置版本的单表修改或整页草稿。 */
public record UpdateTrackedLootTablePayload(int requestId, long revision, long resourceRevision, boolean preview, Edit edit, int sequence, boolean last) implements CustomPacketPayload {
    public static final int MAX_CONFIG_CHARS = 128 * 1024;
    // Minecraft 的 UTF-8 每个 UTF-16 单元最多占三字节，留出自定义包头空间。
    private static final int MAX_PART_CHARS = 10 * 1024;

    /** 对超出单包的表单草稿按顺序发送，服务端完整收到后才解析和提交。 */
    public static List<UpdateTrackedLootTablePayload> split(int requestId, long revision, long resources,
                                                            boolean preview, Edit edit) {
        if (edit instanceof Single) {
            return List.of(new UpdateTrackedLootTablePayload(requestId, revision, resources, preview, edit, 0, true));
        }
        String settings = ((Replace) edit).settingsJson();
        if (settings.length() > MAX_CONFIG_CHARS) throw new IllegalArgumentException("Loot tracking draft is too large");
        List<UpdateTrackedLootTablePayload> packets = new ArrayList<>();
        int start = 0;
        do {
            int end = Math.min(settings.length(), start + MAX_PART_CHARS);
            if (end < settings.length() && Character.isHighSurrogate(settings.charAt(end - 1))) end--;
            packets.add(new UpdateTrackedLootTablePayload(requestId, revision, resources, preview,
                    new Replace(settings.substring(start, end)), packets.size(), end == settings.length()));
            start = end;
        } while (start < settings.length());
        return List.copyOf(packets);
    }

    public static final Type<UpdateTrackedLootTablePayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "update_loot_tracking"));
    public static final StreamCodec<RegistryFriendlyByteBuf, UpdateTrackedLootTablePayload> STREAM_CODEC =
            StreamCodec.of(UpdateTrackedLootTablePayload::encode, UpdateTrackedLootTablePayload::decode);

    /** 服务端区分整页规则的离线选择器与单表操作的在线注册表校验。 */
    public sealed interface Edit permits Single, Replace {}

    /** 针对一张已注册且可管理的战利品表的编辑。 */
    public record Single(ResourceLocation tableId, TableEdit action) implements Edit {}

    /** 替换当前世界的可编辑字段，校验由服务端提交入口执行。 */
    public record Replace(String settingsJson) implements Edit {}

    @Override
    public @NotNull Type<UpdateTrackedLootTablePayload> type() {
        return TYPE;
    }

    private static void encode(RegistryFriendlyByteBuf buf, UpdateTrackedLootTablePayload payload) {
        buf.writeVarInt(payload.requestId);
        buf.writeVarLong(payload.revision);
        buf.writeVarLong(payload.resourceRevision);
        buf.writeBoolean(payload.preview);
        buf.writeVarInt(payload.sequence);
        buf.writeBoolean(payload.last);
        buf.writeBoolean(payload.edit instanceof Single);
        switch (payload.edit) {
            case Single single -> {
                buf.writeResourceLocation(single.tableId());
                buf.writeEnum(single.action());
            }
            case Replace replace -> buf.writeUtf(replace.settingsJson(), MAX_PART_CHARS);
        }
    }

    private static UpdateTrackedLootTablePayload decode(RegistryFriendlyByteBuf buf) {
        int requestId = buf.readVarInt();
        long revision = buf.readVarLong();
        long resourceRevision = buf.readVarLong();
        boolean preview = buf.readBoolean();
        int sequence = buf.readVarInt();
        boolean last = buf.readBoolean();
        Edit edit = buf.readBoolean()
                ? new Single(buf.readResourceLocation(), buf.readEnum(TableEdit.class))
                : new Replace(buf.readUtf(MAX_PART_CHARS));
        return new UpdateTrackedLootTablePayload(requestId, revision, resourceRevision, preview, edit, sequence, last);
    }
}
