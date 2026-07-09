package com.meteorite.unsuspiciousblock.network.payload.s2c;

import com.meteorite.unsuspiciousblock.Constants;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

/** 战利品表 100% 完成奖励通知包 -- 服务端->客户端，通知客户端弹出完成 Toast */
public record NotifyTableCompletionRewardPayload(ResourceLocation tableId) implements CustomPacketPayload {

    public static final Type<NotifyTableCompletionRewardPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "notify_table_completion"));

    @Override
    public @NotNull Type<NotifyTableCompletionRewardPayload> type() {
        return TYPE;
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, NotifyTableCompletionRewardPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, payload) -> ResourceLocation.STREAM_CODEC.encode(buf, payload.tableId),
                    buf -> new NotifyTableCompletionRewardPayload(ResourceLocation.STREAM_CODEC.decode(buf))
            );
}
