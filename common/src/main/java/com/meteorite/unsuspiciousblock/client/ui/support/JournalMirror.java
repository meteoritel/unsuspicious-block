package com.meteorite.unsuspiciousblock.client.ui.support;

import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.client.network.ClientNetwork;
import com.meteorite.unsuspiciousblock.core.journal.state.ArchaeologyJournalLogState.TableLogHistory;
import com.meteorite.unsuspiciousblock.core.journal.state.ExcavationLogEntry;
import com.meteorite.unsuspiciousblock.core.network.journal.JournalLogSummary.Table;
import com.meteorite.unsuspiciousblock.core.network.journal.JournalLogSummary.Totals;
import com.meteorite.unsuspiciousblock.core.network.journal.JournalWireNbt;
import com.meteorite.unsuspiciousblock.core.network.payload.c2s.RequestJournalLogPayload;
import com.meteorite.unsuspiciousblock.core.network.payload.c2s.RequestJournalLogPayload.Action;
import com.meteorite.unsuspiciousblock.core.network.payload.s2c.JournalTransferPayload;
import com.meteorite.unsuspiciousblock.core.network.payload.s2c.JournalTransferPayload.Kind;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** 已提交日志摘要与正在查看的表。替换历史只在完整快照到达时发生。 */
public final class JournalMirror {
    /** 当前所选历史的加载状态。 */
    public enum Status { IDLE, LOADING, READY, FAILED }

    private final JournalTransferAssembly assembly = new JournalTransferAssembly();
    private Map<ResourceLocation, Table> summaries = new LinkedHashMap<>();
    private final Map<UUID, ExcavationLogEntry> entries = new LinkedHashMap<>();
    private Totals totals = Totals.EMPTY;
    private List<ExcavationLogEntry> history = List.of();
    private @Nullable ResourceLocation selected;
    private long committed = -1;
    private long nextTicket;
    private long summaryTicket;
    private long historyTicket;
    private long deadline;
    private long summaryRevision;
    private long sortRevision;
    private long detailRevision;
    private boolean waitingSummary = true;
    private boolean recovered;
    private boolean failed;
    private boolean unavailable;
    private Status status = Status.IDLE;

    public Table summary(ResourceLocation tableId) { return summaries.get(tableId); }
    public Totals totals() { return totals; }
    public long summaryRevision() { return summaryRevision; }
    public long sortRevision() { return sortRevision; }
    public long detailRevision() { return detailRevision; }
    public long committed() { return committed; }
    public Status status() { return status; }

    /** 当前查询已提交的历史，按服务端插入顺序读取。 */
    public List<ExcavationLogEntry> history() { return history; }

    public boolean isDisplaying(ResourceLocation tableId) { return tableId.equals(selected); }

    /** 改变可见历史，切表立即废弃旧票据。 */
    public void select(@Nullable ResourceLocation tableId) {
        if (Objects.equals(selected, tableId)) return;
        selected = tableId;
        assembly.cancelHistory();
        entries.clear();
        history = List.of();
        detailRevision++;
        recovered = false;
        if (tableId == null) {
            status = Status.IDLE;
            if (historyTicket != 0) send(Action.CLOSE, ++nextTicket, null);
            historyTicket = 0;
        } else if (unavailable) {
            status = Status.FAILED;
        } else if (failed) {
            recover();
        } else {
            requestHistory();
        }
    }

    void receive(JournalTransferPayload piece) {
        Kind kind = piece.kind();
        if (failed || switch (kind) {
            case LOG_SUMMARY -> !waitingSummary || piece.ticket() != summaryTicket || piece.revision() < committed;
            case LOG_HISTORY -> selected == null || piece.ticket() != historyTicket || status != Status.LOADING;
            case LOG_CHANGE -> piece.total() != 0 && (piece.revision() <= committed || waitingSummary);
            default -> throw new IllegalArgumentException("非日志事务");
        }) return;

        if (piece.total() == 0) {
            if (kind == Kind.LOG_HISTORY) {
                assembly.clear();
                status = Status.FAILED;
                detailRevision++;
            } else {
                fail();
            }
            return;
        }
        if ((kind == Kind.LOG_CHANGE && piece.revision() != committed + 1)
                || (kind == Kind.LOG_HISTORY && piece.revision() != committed)) {
            recover();
            return;
        }
        try {
            byte[] bytes = assembly.accept(piece, System.currentTimeMillis());
            if (bytes == null) return;
            long nbtBudget = kind == Kind.LOG_HISTORY ? kind.maxBytes() + 1024L * 1024 : kind.maxBytes() * 2L;
            CompoundTag tag = JournalWireNbt.decode(bytes, nbtBudget);
            switch (kind) {
                case LOG_SUMMARY -> applyBaseline(piece.revision(), tag);
                case LOG_HISTORY -> applyHistory(tag);
                case LOG_CHANGE -> applyChange(piece, tag);
                default -> throw new IllegalArgumentException("非日志事务");
            }
        } catch (IOException | RuntimeException exception) {
            Constants.LOG.warn(Constants.LOG_TAG + "考古日志事务无法应用", exception);
            recover();
        }
    }

    void unavailable() {
        unavailable = true;
        fail();
    }

    void tick() {
        long now = System.currentTimeMillis();
        if (waitingSummary && deadline == 0) deadline = now + JournalTransferAssembly.RESPONSE_TIMEOUT_MS;
        if (!failed && (assembly.expired(now)
                || !assembly.active() && deadline != 0 && now >= deadline && (waitingSummary || status == Status.LOADING))) {
            recover();
        }
    }

    private void applyBaseline(long revision, CompoundTag tag) {
        Totals next = Totals.fromTag(tag.getCompound("totals"));
        CompoundTag tables = tag.getCompound("tables");
        Map<ResourceLocation, Table> nextSummaries = new LinkedHashMap<>();
        for (String key : tables.getAllKeys()) {
            nextSummaries.put(ResourceLocation.parse(key), Table.fromTag(tables.getCompound(key)));
        }
        summaries = nextSummaries;
        sortRevision++;
        commitTotals(next, revision);
        waitingSummary = false;
        if (status == Status.LOADING) deadline = System.currentTimeMillis() + JournalTransferAssembly.RESPONSE_TIMEOUT_MS;
        else recovered = false;
    }

    private void applyHistory(CompoundTag tag) {
        if (!ResourceLocation.parse(tag.getString("table")).equals(selected)) {
            throw new IllegalArgumentException("考古日志历史表与请求不一致");
        }
        List<ExcavationLogEntry> next = tag.contains("history", Tag.TAG_COMPOUND)
                ? TableLogHistory.fromTag(tag.getCompound("history")).getEntries() : List.of();
        entries.clear();
        next.forEach(entry -> entries.put(entry.entryId(), entry));
        publishHistory();
        status = Status.READY;
        recovered = false;
    }

    private void applyChange(JournalTransferPayload piece, CompoundTag tag) {
        Totals next = Totals.fromTag(tag.getCompound("totals"));
        if (tag.getBoolean("all")) {
            if (tag.getBoolean("clear_tables")) summaries.clear();
            else summaries.replaceAll((id, summary) -> summary.emptyEntries());
            sortRevision++;
            if (status == Status.READY && !entries.isEmpty()) {
                entries.clear();
                publishHistory();
            }
        } else if (tag.contains("table", Tag.TAG_STRING)) {
            ResourceLocation tableId = ResourceLocation.parse(tag.getString("table"));
            Table summary = tag.contains("summary", Tag.TAG_COMPOUND) ? Table.fromTag(tag.getCompound("summary")) : null;
            boolean detail = piece.ticket() == historyTicket && tableId.equals(selected) && status == Status.READY;
            ExcavationLogEntry updated = detail && tag.contains("entry", Tag.TAG_COMPOUND)
                    ? ExcavationLogEntry.fromTag(tag.getCompound("entry")) : null;
            if (detail && tag.contains("entry", Tag.TAG_COMPOUND) && updated == null) {
                throw new IllegalArgumentException("考古日志条目无效");
            }
            List<UUID> removed = new ArrayList<>();
            if (detail) {
                for (Tag id : tag.getList("removed", Tag.TAG_INT_ARRAY)) removed.add(NbtUtils.loadUUID(id));
            }
            Table previous = summary == null ? summaries.remove(tableId) : summaries.put(tableId, summary);
            if (!Objects.equals(previous == null ? null : previous.latest(), summary == null ? null : summary.latest())) {
                sortRevision++;
            }
            if (detail && (summary == null || tag.getBoolean("clear") || updated != null || !removed.isEmpty())) {
                if (summary == null || tag.getBoolean("clear")) entries.clear();
                removed.forEach(entries::remove);
                if (updated != null) entries.put(updated.entryId(), updated);
                publishHistory();
            }
        }
        commitTotals(next, piece.revision());
    }

    private void commitTotals(Totals next, long revision) {
        if (!next.equals(totals)) summaryRevision++;
        totals = next;
        committed = revision;
    }

    private void publishHistory() {
        history = List.copyOf(entries.values());
        detailRevision++;
    }

    private void recover() {
        if (recovered) {
            fail();
            return;
        }
        recovered = true;
        failed = false;
        assembly.clear();
        waitingSummary = true;
        summaryTicket = ++nextTicket;
        deadline = System.currentTimeMillis() + JournalTransferAssembly.RESPONSE_TIMEOUT_MS;
        send(Action.SUMMARY, summaryTicket, null);
        if (selected != null) requestHistory();
    }

    private void requestHistory() {
        status = Status.LOADING;
        detailRevision++;
        historyTicket = ++nextTicket;
        deadline = System.currentTimeMillis() + JournalTransferAssembly.RESPONSE_TIMEOUT_MS;
        send(Action.HISTORY, historyTicket, selected);
    }

    private void fail() {
        assembly.clear();
        failed = true;
        status = Status.FAILED;
        detailRevision++;
    }

    private static void send(Action action, long ticket, @Nullable ResourceLocation table) {
        ClientNetwork.sendToServer(new RequestJournalLogPayload(action, ticket, table));
    }
}
