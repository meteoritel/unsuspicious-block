package com.meteorite.unsuspiciousblock.core.network.journal;

import com.meteorite.unsuspiciousblock.core.network.payload.s2c.JournalTransferPayload;
import com.meteorite.unsuspiciousblock.core.network.payload.s2c.JournalTransferPayload.Kind;
import com.meteorite.unsuspiciousblock.platform.Services;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.Arrays;

/** 按连接和流保存已冻结的事务，每 tick 推进一个有界发送窗口。 */
public final class JournalTransferOutbox {
    private static final int MAX_QUEUED_BYTES = 96 * 1024 * 1024;
    private static final int MAX_QUEUED_TRANSACTIONS = 1024;

    private final ArrayDeque<Transfer> pending = new ArrayDeque<>();
    private int queuedBytes;
    private boolean overflow;

    /** 空数据向接收方报告容量失败。排队超限后用同一流的失败帧释放双方状态。 */
    public void offer(Kind kind, long revision, long ticket, byte @Nullable [] bytes) {
        if (overflow) return;
        if (bytes == null || bytes.length > kind.maxBytes()) bytes = new byte[0];
        if (queuedBytes + bytes.length > MAX_QUEUED_BYTES || pending.size() >= MAX_QUEUED_TRANSACTIONS) {
            clear();
            overflow = true;
            Kind failure = kind.stream() == JournalTransferPayload.Stream.LOG ? Kind.LOG_CHANGE : kind;
            pending.addLast(new Transfer(failure, revision, ticket, new byte[0]));
            return;
        }
        pending.addLast(new Transfer(kind, revision, ticket, bytes));
        queuedBytes += bytes.length;
    }

    /** 切换表时撤回旧历史，保留摘要和已接受的变更顺序。 */
    public void cancelHistory() {
        pending.removeIf(transfer -> {
            if (transfer.kind != Kind.LOG_HISTORY || overflow) return false;
            queuedBytes -= transfer.bytes.length;
            return true;
        });
    }

    /** 新基线覆盖此前排队的内容。 */
    public void clear() {
        pending.clear();
        queuedBytes = 0;
        overflow = false;
    }

    /** 在服务端线程交付，重生后的 player 仍使用同一个队列。 */
    public void tick(ServerPlayer player) {
        int budget = JournalTransferPayload.MAX_PAYLOAD_BYTES;
        while (!pending.isEmpty()) {
            Transfer transfer = pending.getFirst();
            int length = Math.min(JournalTransferPayload.PIECE_BYTES, transfer.bytes.length - transfer.offset);
            if (length + JournalTransferPayload.HEADER_BYTES > budget) return;
            byte[] piece = transfer.offset == 0 && length == transfer.bytes.length ? transfer.bytes
                    : Arrays.copyOfRange(transfer.bytes, transfer.offset, transfer.offset + length);
            Services.NETWORK.sendToPlayer(player, new JournalTransferPayload(transfer.kind, transfer.revision,
                    transfer.ticket, transfer.bytes.length, transfer.offset, piece));
            budget -= length + JournalTransferPayload.HEADER_BYTES;
            transfer.offset += length;
            if (transfer.offset == transfer.bytes.length) queuedBytes -= pending.removeFirst().bytes.length;
        }
    }

    private static final class Transfer {
        private final Kind kind;
        private final long revision;
        private final long ticket;
        private final byte[] bytes;
        private int offset;

        private Transfer(Kind kind, long revision, long ticket, byte[] bytes) {
            this.kind = kind;
            this.revision = revision;
            this.ticket = ticket;
            this.bytes = bytes;
        }
    }
}
