package com.meteorite.unsuspiciousblock.journal.catalog;

import com.meteorite.unsuspiciousblock.loottable.catalog.CatalogTableDto;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.CatalogStructure;
import com.meteorite.unsuspiciousblock.network.payload.s2c.SyncArchaeologyCatalogPayload;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/** 同代同 revision 的线路快照；内容与摘要共用真实 codec 的裁剪和编码。 */
public record CatalogReadSnapshot(long generation, long revision,
                                  SyncArchaeologyCatalogPayload payload, String hash) {
    static CatalogReadSnapshot freeze(long generation, long revision, List<CatalogTableDto> tables,
                                       CatalogStructure structure, RegistryAccess registries) {
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), registries);
        try {
            SyncArchaeologyCatalogPayload.STREAM_CODEC.encode(buffer,
                    new SyncArchaeologyCatalogPayload(tables, structure));
            // 解码副本剥离活谓词和服务端内部约束，同时冻结实际线路裁剪后的树。
            SyncArchaeologyCatalogPayload frozen = SyncArchaeologyCatalogPayload.STREAM_CODEC.decode(buffer);
            if (buffer.isReadable()) throw new IllegalStateException("目录快照解码后仍有未消费字节");
            buffer.clear();
            SyncArchaeologyCatalogPayload.STREAM_CODEC.encode(buffer, frozen);
            byte[] encoded = new byte[buffer.readableBytes()];
            buffer.getBytes(buffer.readerIndex(), encoded);
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encoded));
            return new CatalogReadSnapshot(generation, revision, frozen, hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        } finally {
            buffer.release();
        }
    }
}
