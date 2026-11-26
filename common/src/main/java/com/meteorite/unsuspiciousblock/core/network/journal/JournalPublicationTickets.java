package com.meteorite.unsuspiciousblock.core.network.journal;

/** 记录当前连接接受的最新刷新请求，阻止旧票据撤销新基线。 */
public final class JournalPublicationTickets {
    private long latest;

    /** 票据按客户端发出顺序单调增长。 */
    public boolean advance(long request) {
        if (request <= latest) return false;
        latest = request;
        return true;
    }

    public long latest() { return latest; }
}
