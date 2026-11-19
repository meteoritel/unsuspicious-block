package com.meteorite.unsuspiciousblock.core.network.payload.c2s;

import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.core.loottable.LootTrackingRules.TableEdit;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

/** 提交基于指定世界配置版本的单表修改或整页草稿。 */
public record UpdateTrackedLootTablePayload(int requestId, long revision, Edit edit) implements CustomPacketPayload {
    public static final int MAX_CONFIG_CHARS = 128 * 1024;
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
        buf.writeBoolean(payload.edit instanceof Single);
        switch (payload.edit) {
            case Single single -> {
                buf.writeResourceLocation(single.tableId());
                buf.writeEnum(single.action());
            }
            case Replace replace -> buf.writeUtf(replace.settingsJson(), MAX_CONFIG_CHARS);
        }
    }

    private static UpdateTrackedLootTablePayload decode(RegistryFriendlyByteBuf buf) {
        int requestId = buf.readVarInt();
        long revision = buf.readVarLong();
        Edit edit = buf.readBoolean()
                ? new Single(buf.readResourceLocation(), buf.readEnum(TableEdit.class))
                : new Replace(buf.readUtf(MAX_CONFIG_CHARS));
        return new UpdateTrackedLootTablePayload(requestId, revision, edit);
    }
}
