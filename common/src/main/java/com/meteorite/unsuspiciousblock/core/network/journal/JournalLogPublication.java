package com.meteorite.unsuspiciousblock.core.network.journal;

import com.meteorite.unsuspiciousblock.core.journal.state.ArchaeologyJournalLogState;
import com.meteorite.unsuspiciousblock.core.journal.state.ArchaeologyJournalLogState.TableLogHistory;
import com.meteorite.unsuspiciousblock.core.journal.state.JournalLogMutation.Change;
import com.meteorite.unsuspiciousblock.core.journal.state.JournalLogMutation.TableChange;
import com.meteorite.unsuspiciousblock.core.journal.state.JournalLogMutation.TableRemoved;
import com.meteorite.unsuspiciousblock.core.journal.state.JournalLogMutation.ClearHistories;
import com.meteorite.unsuspiciousblock.core.network.payload.s2c.JournalTransferPayload.Kind;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** 日志会话的权威发送顺序，查询快照与变更共用有界队列。 */
public final class JournalLogPublication {
    private final JournalTransferOutbox outbox = new JournalTransferOutbox();
    private final Map<ResourceLocation, JournalLogSummary.Projection> summaries = new LinkedHashMap<>();
    private long revision;
    private long query;
    private long summaryTicket = -1;
    private @Nullable ResourceLocation selectedTable;
    private boolean started;
    private int limit;

    /** 建立或恢复摘要。恢复后客户端重新发起当前详情查询。 */
    public void baseline(ArchaeologyJournalLogState state, long ticket) {
        if (ticket <= summaryTicket) return;
        summaryTicket = ticket;
        outbox.clear();
        selectedTable = null;
        summaries.clear();
        CompoundTag tables = new CompoundTag();
        state.getTables().forEach((id, history) -> {
            var projection = JournalLogSummary.project(id, history);
            summaries.put(id, projection);
            tables.put(id.toString(), projection.summary().toTag());
        });
        limit = TableLogHistory.getGlobalMaxEntries();
        CompoundTag document = new CompoundTag();
        document.put("tables", tables);
        document.put("totals", JournalLogSummary.totals(summaries, limit).toTag());
        started = true;
        offer(Kind.LOG_SUMMARY, ticket, document);
    }

    /** 冻结表历史和当前修订。切表后队列不再发送旧详情。 */
    public void select(ArchaeologyJournalLogState state, ResourceLocation tableId, long ticket) {
        if (ticket <= query) return;
        outbox.cancelHistory();
        query = ticket;
        selectedTable = tableId;
        TableLogHistory history = state.getTable(tableId);
        CompoundTag document = new CompoundTag();
        document.putString("table", tableId.toString());
        if (history != null) document.put("history", history.toTag());
        offer(Kind.LOG_HISTORY, ticket, document);
    }

    /** 取消指定查询，保留已排队的摘要事务。 */
    public void close(long ticket) {
        if (ticket < query) return;
        query = ticket;
        outbox.cancelHistory();
        selectedTable = null;
    }

    /** 接收已提交变更，只有订阅中的表附带条目内容。 */
    public long publish(ArchaeologyJournalLogState state, Change change) {
        revision++;
        if (!started) return revision;
        CompoundTag document = new CompoundTag();
        switch (change) {
            case TableChange table -> {
                var projection = JournalLogSummary.project(table.tableId(), Objects.requireNonNull(state.getTable(table.tableId())));
                summaries.put(table.tableId(), projection);
                document.putString("table", table.tableId().toString());
                document.put("summary", projection.summary().toTag());
                if (table.tableId().equals(selectedTable)) {
                    document.putBoolean("clear", table.clearEntries());
                    if (table.entry() != null) document.put("entry", table.entry().toTag());
                    ListTag removed = new ListTag();
                    for (UUID id : table.removedEntryIds()) removed.add(NbtUtils.createUUID(id));
                    if (!removed.isEmpty()) document.put("removed", removed);
                }
            }
            case TableRemoved removed -> {
                summaries.remove(removed.tableId());
                document.putString("table", removed.tableId().toString());
            }
            case ClearHistories clear -> {
                document.putBoolean("all", true);
                document.putBoolean("clear_tables", clear == ClearHistories.ALL_TABLES);
                if (clear == ClearHistories.ALL_TABLES) summaries.clear();
                else summaries.replaceAll((id, table) -> new JournalLogSummary.Projection(
                        table.summary().emptyEntries(), table.archaeology(), table.containers(), null));
            }
        }
        sendChange(document);
        return revision;
    }

    /** 世界规则更新时也交付新的有效上限。 */
    public void tick(ServerPlayer player) {
        if (!started) return;
        if (limit != TableLogHistory.getGlobalMaxEntries()) {
            revision++;
            sendChange(new CompoundTag());
        }
        outbox.tick(player);
    }

    private void sendChange(CompoundTag document) {
        limit = TableLogHistory.getGlobalMaxEntries();
        document.put("totals", JournalLogSummary.totals(summaries, limit).toTag());
        offer(Kind.LOG_CHANGE, query, document);
    }

    private void offer(Kind kind, long ticket, CompoundTag document) {
        outbox.offer(kind, revision, ticket, JournalWireNbt.encode(document, kind.maxBytes()));
    }
}
