package com.meteorite.unsuspiciousblock.network.payload.s2c;

import com.meteorite.unsuspiciousblock.Constants;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * 服务端→客户端：附魔台完整候选列表同步包。
 * <p>
 * 服务端在 {@code EnchantmentMenu#slotsChanged} 后评估揭示条件：
 * <ul>
 *   <li>条件满足时 {@code reveal=true}，附带 3 个槽位的完整候选附魔列表</li>
 *   <li>条件不满足时 {@code reveal=false}，客户端清空缓存的候选列表</li>
 * </ul>
 * 附魔以 {@link ResourceLocation} 标识，避免客户端与服务端 registry id 映射不一致问题。
 */
public record SyncEnchantmentRevealListPayload(
        int containerId,
        boolean reveal,
        List<Entry> entries
) implements CustomPacketPayload {

    public SyncEnchantmentRevealListPayload {
        entries = List.copyOf(entries);
    }

    /** 单条候选附魔：槽位索引 + 附魔 key + 等级 */
    public record Entry(int slot, ResourceLocation enchantId, int level) {}

    public static final Type<SyncEnchantmentRevealListPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "sync_enchant_reveal_list"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SyncEnchantmentRevealListPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, payload) -> {
                        buf.writeVarInt(payload.containerId);
                        buf.writeBoolean(payload.reveal);
                        buf.writeVarInt(payload.entries.size());
                        for (Entry e : payload.entries) {
                            buf.writeVarInt(e.slot());
                            ResourceLocation.STREAM_CODEC.encode(buf, e.enchantId());
                            buf.writeVarInt(e.level());
                        }
                    },
                    buf -> {
                        int containerId = buf.readVarInt();
                        boolean reveal = buf.readBoolean();
                        int size = buf.readVarInt();
                        List<Entry> entries = new ArrayList<>(size);
                        for (int i = 0; i < size; i++) {
                            int slot = buf.readVarInt();
                            ResourceLocation enchantId = ResourceLocation.STREAM_CODEC.decode(buf);
                            int level = buf.readVarInt();
                            entries.add(new Entry(slot, enchantId, level));
                        }
                        return new SyncEnchantmentRevealListPayload(containerId, reveal, entries);
                    }
            );

    @Override
    public @NotNull Type<SyncEnchantmentRevealListPayload> type() {
        return TYPE;
    }
}
