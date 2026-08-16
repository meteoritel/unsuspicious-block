package com.meteorite.unsuspiciousblock.network.journal;

import com.meteorite.unsuspiciousblock.journal.state.ArchaeologyJournalLogState;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * 日志表快照压缩编解码器——网络层只传输受限 byte 分片，不直接调用 readNbt 读取全量状态。
 */
public final class JournalLogSnapshotCodec {
    public static final int MAX_CHUNK_BYTES = 128 * 1024;
    public static final int MAX_COMPRESSED_TABLE_BYTES = 16 * 1024 * 1024;
    public static final int MAX_UNCOMPRESSED_TABLE_BYTES = 64 * 1024 * 1024;
    public static final int MAX_TABLES_PER_SNAPSHOT = 65_536;
    public static final int MAX_CHUNKS_PER_TABLE = MAX_COMPRESSED_TABLE_BYTES / MAX_CHUNK_BYTES;

    private JournalLogSnapshotCodec() {
    }

    // 将单表历史压缩成独立 NBT 字节流
    public static byte[] encode(ArchaeologyJournalLogState.TableLogHistory history) {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            NbtIo.writeCompressed(history.toTag(), output);
            byte[] data = output.toByteArray();
            if (data.length > MAX_COMPRESSED_TABLE_BYTES) {
                throw new IllegalStateException("单张考古日志表压缩后超过限制: " + data.length);
            }
            return data;
        } catch (IOException exception) {
            throw new IllegalStateException("压缩考古日志表失败", exception);
        }
    }

    // 从重组后的压缩字节流恢复单表历史，并限制解压后的 NBT 规模
    public static ArchaeologyJournalLogState.TableLogHistory decode(byte[] data) {
        if (data.length > MAX_COMPRESSED_TABLE_BYTES) {
            throw new IllegalArgumentException("考古日志表压缩数据超过限制: " + data.length);
        }
        try (ByteArrayInputStream input = new ByteArrayInputStream(data)) {
            CompoundTag tag = NbtIo.readCompressed(input,
                    NbtAccounter.create(MAX_UNCOMPRESSED_TABLE_BYTES));
            return ArchaeologyJournalLogState.TableLogHistory.fromTag(tag);
        } catch (IOException exception) {
            throw new IllegalArgumentException("解压考古日志表失败", exception);
        }
    }
}
