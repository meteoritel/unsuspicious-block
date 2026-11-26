package com.meteorite.unsuspiciousblock.core.network.journal;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.ByteBufOutputStream;
import io.netty.buffer.Unpooled;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;

/** 笔记 NBT 事务复用原版格式，在分片重组后使用独立的配额解码。 */
public final class JournalWireNbt {
    private JournalWireNbt() {}

    /** 编码超过线路容量时通知调用方发送失败帧。 */
    public static byte @Nullable [] encode(CompoundTag tag, int limit) {
        ByteBuf buffer = Unpooled.buffer(Math.min(8192, limit), limit);
        try {
            NbtIo.write(tag, new ByteBufOutputStream(buffer));
            byte[] bytes = new byte[buffer.readableBytes()];
            buffer.readBytes(bytes);
            return bytes;
        } catch (IndexOutOfBoundsException exception) {
            if (buffer.writerIndex() >= limit - 65536) return null;
            throw exception;
        } catch (IOException exception) {
            throw new IllegalStateException("无法编码考古笔记 NBT", exception);
        } finally {
            buffer.release();
        }
    }

    /** 完整读取消费整个事务，避免多个不同解释共享同一字节序列。 */
    public static CompoundTag decode(byte[] bytes, long budget) throws IOException {
        ByteBuf buffer = Unpooled.wrappedBuffer(bytes);
        try {
            CompoundTag tag = NbtIo.read(new ByteBufInputStream(buffer), NbtAccounter.create(budget));
            if (buffer.isReadable()) throw new IOException("考古笔记 NBT 尾部存在额外字节");
            return tag;
        } finally {
            buffer.release();
        }
    }
}
