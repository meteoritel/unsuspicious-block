package com.meteorite.unsuspiciousblock.network.payload.s2c;

import com.meteorite.unsuspiciousblock.Constants;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/** 服务端→客户端：范围扫描完成后同步被扫描到的可疑方块位置，供客户端红色描边渲染 */
public record SyncReaderScanResultPayload(List<BlockPos> scannedBlocks) implements CustomPacketPayload {

    public SyncReaderScanResultPayload {
        scannedBlocks = List.copyOf(scannedBlocks);
    }

    public static final Type<SyncReaderScanResultPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "sync_reader_scan_result"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SyncReaderScanResultPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, payload) -> {
                        buf.writeVarInt(payload.scannedBlocks.size());
                        for (BlockPos pos : payload.scannedBlocks) {
                            BlockPos.STREAM_CODEC.encode(buf, pos);
                        }
                    },
                    buf -> {
                        int size = buf.readVarInt();
                        List<BlockPos> list = new ArrayList<>(size);
                        for (int i = 0; i < size; i++) {
                            list.add(BlockPos.STREAM_CODEC.decode(buf));
                        }
                        return new SyncReaderScanResultPayload(list);
                    }
            );

    @Override
    public @NotNull Type<SyncReaderScanResultPayload> type() {
        return TYPE;
    }
}
