package com.meteorite.unsuspiciousblock.core.journal.catalog;

import com.meteorite.unsuspiciousblock.core.loottable.catalog.CatalogTableDto;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.LootTableCatalog.CatalogStructure;
import com.meteorite.unsuspiciousblock.core.network.journal.JournalTransferOutbox;
import com.meteorite.unsuspiciousblock.core.network.payload.s2c.JournalCatalogData;
import com.meteorite.unsuspiciousblock.core.network.payload.s2c.JournalTransferPayload.Kind;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** 每版目录只冻结一次可交付的线路字节，发送队列共享该快照。 */
public final class CatalogReadSnapshot {
    private final CatalogIdentity identity;
    private final byte @Nullable [] wire;

    private CatalogReadSnapshot(CatalogIdentity identity, byte @Nullable [] wire) {
        this.identity = identity;
        this.wire = wire;
    }

    public CatalogIdentity identity() { return identity; }

    static CatalogReadSnapshot freeze(long generation, long revision, List<CatalogTableDto> tables,
                                       CatalogStructure structure, RegistryAccess registries, List<String> exclusions,
                                       Map<ResourceLocation, Set<ResourceLocation>> foldedItems) {
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(8192, Kind.CATALOG.maxBytes()), registries);
        try {
            JournalCatalogData.STREAM_CODEC.encode(buffer, new JournalCatalogData(tables, structure, exclusions, foldedItems));
            byte[] body = new byte[buffer.readableBytes()];
            buffer.readBytes(body);
            CatalogIdentity identity = new CatalogIdentity(generation, revision, CatalogIdentity.digest(body, 0));
            buffer.clear();
            identity.write(buffer);
            if (body.length > buffer.maxWritableBytes()) return new CatalogReadSnapshot(identity, null);
            buffer.writeBytes(body);
            byte[] wire = new byte[buffer.readableBytes()];
            buffer.readBytes(wire);
            return new CatalogReadSnapshot(identity, wire);
        } catch (IndexOutOfBoundsException exception) {
            // 超出 ByteBuf 编码容量的版本仍可公告并返回明确的容量结果。
            return new CatalogReadSnapshot(new CatalogIdentity(generation, revision, "0".repeat(64)), null);
        } finally {
            buffer.release();
        }
    }

    public void enqueue(JournalTransferOutbox outbox, long ticket) {
        outbox.clear();
        outbox.offer(Kind.CATALOG, identity.revision(), ticket, wire);
    }

    /** 完整匹配公告身份和内容摘要后才解析目录。 */
    public static JournalCatalogData decode(byte[] bytes, CatalogIdentity expected, RegistryAccess registries) {
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(bytes), registries);
        try {
            CatalogIdentity actual = CatalogIdentity.read(buffer);
            if (!actual.equals(expected) || !actual.hash().equals(CatalogIdentity.digest(bytes, buffer.readerIndex()))) {
                throw new DecoderException("考古目录身份或内容摘要不匹配");
            }
            JournalCatalogData data = JournalCatalogData.STREAM_CODEC.decode(buffer);
            if (buffer.isReadable()) throw new DecoderException("考古目录尾部存在额外字节");
            return data;
        } finally {
            buffer.release();
        }
    }
}
