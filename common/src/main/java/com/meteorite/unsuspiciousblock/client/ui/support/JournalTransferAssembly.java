package com.meteorite.unsuspiciousblock.client.ui.support;

import com.meteorite.unsuspiciousblock.core.network.payload.s2c.JournalTransferPayload;
import com.meteorite.unsuspiciousblock.core.network.payload.s2c.JournalTransferPayload.Kind;
import org.jetbrains.annotations.Nullable;

/** 重组一个有序事务。调用方先按已提交版本和票据忽略过期事务。 */
final class JournalTransferAssembly {
    static final long RESPONSE_TIMEOUT_MS = 40_000L;
    private static final long PIECE_TIMEOUT_MS = 5_000L;

    private byte @Nullable [] data;
    private Kind kind;
    private long revision;
    private long ticket;
    private int offset;
    private long lastPiece;

    byte @Nullable [] accept(JournalTransferPayload piece, long now) {
        if (data == null) {
            if (piece.offset() != 0) throw new IllegalArgumentException("考古笔记缺少首片");
            if (piece.bytes().length == piece.total()) return piece.bytes();
            data = new byte[piece.total()];
            kind = piece.kind();
            revision = piece.revision();
            ticket = piece.ticket();
        }
        if (piece.kind() != kind || piece.revision() != revision || piece.ticket() != ticket
                || piece.total() != data.length || piece.offset() > offset) {
            throw new IllegalArgumentException("考古笔记分片不连续");
        }
        if (piece.offset() < offset) return null;
        System.arraycopy(piece.bytes(), 0, data, offset, piece.bytes().length);
        offset += piece.bytes().length;
        lastPiece = now;
        if (offset != data.length) return null;
        byte[] completed = data;
        clear();
        return completed;
    }

    boolean matches(JournalTransferPayload piece) {
        return active() && kind == piece.kind() && revision == piece.revision() && ticket == piece.ticket();
    }

    void cancelHistory() {
        if (kind == Kind.LOG_HISTORY) clear();
    }

    boolean active() {
        return data != null;
    }

    boolean expired(long now) {
        return active() && now - lastPiece >= PIECE_TIMEOUT_MS;
    }

    void clear() {
        data = null;
        offset = 0;
    }
}
