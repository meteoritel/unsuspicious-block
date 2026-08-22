package com.meteorite.unsuspiciousblock.network.payload.s2c;

import com.meteorite.unsuspiciousblock.Constants;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 服务端->客户端：同步解析仪扫描结果。
 * 每个可疑方块携带 HUD 所需的紧凑物品信息，战利品容器仅同步位置用于紫色描边。
 */
public record SyncReaderScanResultPayload(boolean highlightResults,
                                          List<ScanEntry> results,
                                          List<BlockPos> lootContainers) implements CustomPacketPayload {

    private static final int MAX_RESULTS = 343;
    private static final int MAX_COMPONENT_JSON_LENGTH = 512;
    private static final int MAX_FALLBACK_TEXT_LENGTH = 128;
    private static final int MAX_CRAFTER_NAME_LENGTH = 64;

    public SyncReaderScanResultPayload {
        results = List.copyOf(results);
        lootContainers = List.copyOf(lootContainers);
    }

    public static final Type<SyncReaderScanResultPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "sync_reader_scan_result"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SyncReaderScanResultPayload> STREAM_CODEC =
            StreamCodec.of(SyncReaderScanResultPayload::encode, SyncReaderScanResultPayload::decode);

    // 提取可疑方块坐标，供现有世界描边状态复用
    public List<BlockPos> suspiciousBlocks() {
        return this.results.stream().map(ScanEntry::pos).toList();
    }

    @Override
    public @NotNull Type<SyncReaderScanResultPayload> type() {
        return TYPE;
    }

    private static void encode(RegistryFriendlyByteBuf buf, SyncReaderScanResultPayload payload) {
        buf.writeBoolean(payload.highlightResults);
        buf.writeVarInt(payload.results.size());
        for (ScanEntry entry : payload.results) {
            BlockPos.STREAM_CODEC.encode(buf, entry.pos);
            buf.writeBoolean(entry.itemId != null);
            if (entry.itemId != null) {
                buf.writeResourceLocation(entry.itemId);
                buf.writeVarInt(entry.count);
                String displayNameJson = encodeDisplayName(buf, entry.displayName);
                buf.writeUtf(displayNameJson, MAX_COMPONENT_JSON_LENGTH);
            }
            buf.writeBoolean(entry.alreadyScanned);
            buf.writeBoolean(entry.sealedByPlayer);
            if (entry.sealedByPlayer) {
                buf.writeUtf(entry.crafterName, MAX_CRAFTER_NAME_LENGTH);
            }
        }

        buf.writeVarInt(payload.lootContainers.size());
        for (BlockPos pos : payload.lootContainers) {
            BlockPos.STREAM_CODEC.encode(buf, pos);
        }
    }

    private static SyncReaderScanResultPayload decode(RegistryFriendlyByteBuf buf) {
        boolean highlightResults = buf.readBoolean();
        int resultCount = readBoundedSize(buf, "scan results");
        List<ScanEntry> results = new ArrayList<>(resultCount);
        for (int i = 0; i < resultCount; i++) {
            BlockPos pos = BlockPos.STREAM_CODEC.decode(buf);
            ResourceLocation itemId = null;
            int count = 0;
            Component displayName = Component.empty();
            if (buf.readBoolean()) {
                itemId = buf.readResourceLocation();
                count = Math.max(1, buf.readVarInt());
                Component decodedName = Component.Serializer.fromJson(
                        buf.readUtf(MAX_COMPONENT_JSON_LENGTH), buf.registryAccess());
                if (decodedName != null) {
                    displayName = decodedName;
                }
            }
            boolean alreadyScanned = buf.readBoolean();
            boolean sealedByPlayer = buf.readBoolean();
            String crafterName = sealedByPlayer ? buf.readUtf(MAX_CRAFTER_NAME_LENGTH) : "";
            results.add(new ScanEntry(pos, itemId, count, displayName,
                    alreadyScanned, sealedByPlayer, crafterName));
        }

        int lootCount = readBoundedSize(buf, "loot containers");
        List<BlockPos> lootContainers = new ArrayList<>(lootCount);
        for (int i = 0; i < lootCount; i++) {
            lootContainers.add(BlockPos.STREAM_CODEC.decode(buf));
        }
        return new SyncReaderScanResultPayload(highlightResults, results, lootContainers);
    }

    private static String encodeDisplayName(RegistryFriendlyByteBuf buf, Component displayName) {
        String json = Component.Serializer.toJson(displayName, buf.registryAccess());
        if (json.length() <= MAX_COMPONENT_JSON_LENGTH) {
            return json;
        }
        String plainText = truncateCodePoints(displayName.getString(), MAX_FALLBACK_TEXT_LENGTH);
        return Component.Serializer.toJson(Component.literal(plainText), buf.registryAccess());
    }

    private static String truncateCodePoints(String value, int maxCodePoints) {
        int codePointCount = value.codePointCount(0, value.length());
        if (codePointCount <= maxCodePoints) {
            return value;
        }
        return value.substring(0, value.offsetByCodePoints(0, maxCodePoints));
    }

    private static int readBoundedSize(RegistryFriendlyByteBuf buf, String fieldName) {
        int size = buf.readVarInt();
        if (size < 0 || size > MAX_RESULTS) {
            throw new IllegalArgumentException("Invalid " + fieldName + " size: " + size);
        }
        return size;
    }

    /** 单个可疑方块的紧凑扫描结果，不同步完整 ItemStack 以限制批量包体积。 */
    public record ScanEntry(BlockPos pos,
                            @Nullable ResourceLocation itemId,
                            int count,
                            Component displayName,
                            boolean alreadyScanned,
                            boolean sealedByPlayer,
                            String crafterName) {

        public ScanEntry {
            displayName = displayName.copy();
            crafterName = crafterName == null ? "" : crafterName;
        }

        // 空物品使用 null id 表示
        public boolean isEmpty() {
            return this.itemId == null;
        }
    }
}
