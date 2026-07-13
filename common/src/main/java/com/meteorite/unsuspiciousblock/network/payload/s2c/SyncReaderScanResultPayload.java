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

/** 服务端->客户端：范围扫描完成后同步被扫描到的方块位置，供客户端描边渲染。
 *  <p>suspiciousBlocks 为可疑方块（红色描边），lootContainers 为含战利品表的容器（紫色描边，仅高亮不解析）。 */
public record SyncReaderScanResultPayload(List<BlockPos> suspiciousBlocks,
                                          List<BlockPos> lootContainers) implements CustomPacketPayload {

    public SyncReaderScanResultPayload {
        suspiciousBlocks = List.copyOf(suspiciousBlocks);
        lootContainers = List.copyOf(lootContainers);
    }

    public static final Type<SyncReaderScanResultPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "sync_reader_scan_result"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SyncReaderScanResultPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, payload) -> {
                        buf.writeVarInt(payload.suspiciousBlocks.size());
                        for (BlockPos pos : payload.suspiciousBlocks) {
                            BlockPos.STREAM_CODEC.encode(buf, pos);
                        }
                        buf.writeVarInt(payload.lootContainers.size());
                        for (BlockPos pos : payload.lootContainers) {
                            BlockPos.STREAM_CODEC.encode(buf, pos);
                        }
                    },
                    buf -> {
                        int suspiciousSize = buf.readVarInt();
                        List<BlockPos> suspicious = new ArrayList<>(suspiciousSize);
                        for (int i = 0; i < suspiciousSize; i++) {
                            suspicious.add(BlockPos.STREAM_CODEC.decode(buf));
                        }
                        int lootSize = buf.readVarInt();
                        List<BlockPos> loot = new ArrayList<>(lootSize);
                        for (int i = 0; i < lootSize; i++) {
                            loot.add(BlockPos.STREAM_CODEC.decode(buf));
                        }
                        return new SyncReaderScanResultPayload(suspicious, loot);
                    }
            );

    @Override
    public @NotNull Type<SyncReaderScanResultPayload> type() {
        return TYPE;
    }
}
