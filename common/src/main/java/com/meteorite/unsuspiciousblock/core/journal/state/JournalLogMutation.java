package com.meteorite.unsuspiciousblock.core.journal.state;

import com.meteorite.unsuspiciousblock.core.loottable.ServerLootTableConfigManager;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/** 日志意图、持久化时效与失败恢复；状态对象保持与存储和会话共享。 */
public final class JournalLogMutation {
    public static final int MAX_NOTE_LENGTH = 500;
    private final ArchaeologyJournalLogState state;
    private final Consumer<Set<ResourceLocation>> defer;
    private final Predicate<Set<ResourceLocation>> commit;

    public JournalLogMutation(ArchaeologyJournalLogState state, Consumer<Set<ResourceLocation>> defer,
                              Predicate<Set<ResourceLocation>> commit) {
        this.state = state;
        this.defer = defer;
        this.commit = commit;
    }

    /** 即时意图在落盘失败时恢复原对象；失败排队由持久化实现承担。 */
    public Result apply(Intent intent) {
        boolean deferred = intent instanceof FirstUnlock || intent instanceof Upsert
                || intent instanceof MergeAcquisition || intent instanceof UpdateNote;
        Set<ResourceLocation> tables = affectedTables(state, intent);
        if (!deferred) return applyImmediate(intent, tables);
        Result result = applyIntent(state, intent);
        if (result.status() == Status.CHANGED) defer.accept(tables);
        return result;
    }

    private Result applyImmediate(Intent intent, Set<ResourceLocation> tables) {
        Snapshot backup = Snapshot.capture(state, tables);
        Result result = applyIntent(state, intent);
        if (result.status() == Status.CHANGED && !commit.test(tables)) {
            backup.restore(state);
            return Result.status(Status.FAILED);
        }
        return result;
    }

    // 单表变更只复制该表历史，其他表保留原引用；失败恢复也保留原表顺序。
    private record Snapshot(List<ResourceLocation> order,
                            Map<ResourceLocation, ArchaeologyJournalLogState.TableLogHistory> histories,
                            Set<ResourceLocation> affected) {
        private static Snapshot capture(ArchaeologyJournalLogState state, Set<ResourceLocation> affected) {
            Map<ResourceLocation, ArchaeologyJournalLogState.TableLogHistory> histories = new LinkedHashMap<>();
            for (ResourceLocation tableId : affected) {
                ArchaeologyJournalLogState.TableLogHistory history = state.getTable(tableId);
                if (history != null) histories.put(tableId, history.copy());
            }
            return new Snapshot(List.copyOf(state.getTables().keySet()), histories, affected);
        }

        private void restore(ArchaeologyJournalLogState state) {
            Map<ResourceLocation, ArchaeologyJournalLogState.TableLogHistory> restored = new LinkedHashMap<>();
            for (ResourceLocation tableId : order) {
                restored.put(tableId, affected.contains(tableId) ? histories.get(tableId) : state.getTable(tableId));
            }
            state.clear();
            restored.forEach(state::putTable);
        }
    }

    /** 将加载前排队的变更回放到持久化镜像，交还需要写回的表。 */
    public static Set<ResourceLocation> replay(ArchaeologyJournalLogState state, Intent intent) {
        Set<ResourceLocation> tables = affectedTables(state, intent);
        applyIntent(state, intent);
        return tables;
    }

    private static Set<ResourceLocation> affectedTables(ArchaeologyJournalLogState state, Intent intent) {
        return switch (intent) {
            case FirstUnlock value -> Set.of(value.tableId());
            case Upsert value -> Set.of(value.tableId());
            case MergeAcquisition value -> Set.of(value.tableId());
            case UpdateNote value -> Set.of(value.tableId());
            case RemoveEntries value -> Set.of(value.tableId());
            case ClearEntries value -> Set.of(value.tableId());
            case RemoveTable value -> Set.of(value.tableId());
            case SetRetention value -> Set.of(value.tableId());
            case Prune value -> Set.of(value.tableId());
            case ClearAllEntries ignored -> state.getTables().entrySet().stream()
                    .filter(entry -> entry.getValue().getTotalEntryCount() > 0)
                    .map(Map.Entry::getKey).collect(Collectors.toUnmodifiableSet());
            case ClearAll ignored -> Set.copyOf(state.getTables().keySet());
        };
    }

    private static Result applyIntent(ArchaeologyJournalLogState state, Intent intent) {
        return switch (intent) {
            case FirstUnlock value -> state.setFirstUnlockMetaMin(value.tableId(), value.source(),
                    value.gameTime(), value.dayTime())
                    ? Result.changed(new TableChange(value.tableId(), null, List.of(), false), 0, 0)
                    : Result.status(Status.UNCHANGED);
            case Upsert value -> upsert(state, value.tableId(), value.entry());
            case MergeAcquisition value -> upsert(state, value.tableId(),
                    state.mergeAcquisition(value.tableId(), value.delta(), value.legacyActual()));
            case UpdateNote value -> updateNote(state, value);
            case RemoveEntries value -> {
                List<UUID> removed = state.removeEntries(value.tableId(), value.entryIds());
                yield Result.removed(new TableChange(value.tableId(), null, removed, false), removed.size());
            }
            case ClearEntries value -> Result.removed(new TableChange(value.tableId(), null, List.of(), true),
                    state.clearEntries(value.tableId()));
            case ClearAllEntries ignored -> Result.removed(ClearHistories.ALL_ENTRIES, state.clearAllEntries());
            case RemoveTable value -> state.removeTable(value.tableId())
                    ? Result.changed(new TableRemoved(value.tableId()), 0, 0) : Result.status(Status.NOT_FOUND);
            case ClearAll ignored -> {
                boolean changed = !state.isEmpty();
                state.clear();
                yield changed ? Result.changed(ClearHistories.ALL_TABLES, 0, 0) : Result.status(Status.UNCHANGED);
            }
            case SetRetention value -> {
                ArchaeologyJournalLogState.RetentionUpdateResult result = state.setRetentionLimit(value.tableId(), value.limit());
                yield result.changed()
                        ? Result.changed(new TableChange(value.tableId(), null, result.removedEntryIds(), false),
                                result.removedEntryIds().size(), result.retentionLimit())
                        : new Result(Status.UNCHANGED, 0, result.retentionLimit(), null, null);
            }
            case Prune value -> {
                ArchaeologyJournalLogState.TableLogHistory table = state.getTable(value.tableId());
                if (table == null) yield Result.status(Status.NOT_FOUND);
                List<UUID> removed = state.pruneToMostRecent(value.tableId(), value.keepCount());
                yield removed.isEmpty() ? new Result(Status.UNCHANGED, 0, table.getRetentionLimit(), null, null)
                        : Result.changed(new TableChange(value.tableId(), null, removed, false),
                                removed.size(), table.getRetentionLimit());
            }
        };
    }

    private static Result upsert(ArchaeologyJournalLogState state, ResourceLocation tableId, ExcavationLogEntry entry) {
        ArchaeologyJournalLogState.EntryUpsertResult result = state.upsertEntryWithResult(tableId, entry);
        Status status = result.blocked() ? Status.BLOCKED : result.changed() ? Status.CHANGED : Status.UNCHANGED;
        TableChange change = result.changed() ? new TableChange(tableId,
                result.removedEntryIds().contains(entry.entryId()) ? null : entry, result.removedEntryIds(), false) : null;
        return new Result(status, result.removedEntryIds().size(), 0, result, change);
    }

    private static Result updateNote(ArchaeologyJournalLogState state, UpdateNote value) {
        ArchaeologyJournalLogState.TableLogHistory table = state.getTable(value.tableId());
        if (table == null) return Result.status(Status.NOT_FOUND);
        ExcavationLogEntry existing = table.getEntry(value.entryId());
        if (existing == null) return Result.status(Status.NOT_FOUND);
        String sanitized = sanitizeNote(value.note());
        if (sanitized.equals(existing.note())) return Result.status(Status.UNCHANGED);
        ExcavationLogEntry updated = existing.withNote(sanitized);
        state.upsertEntry(value.tableId(), updated);
        return Result.changed(new TableChange(value.tableId(), updated, List.of(), false), 0, 0);
    }

    private static String sanitizeNote(@Nullable String raw) {
        if (raw == null) return "";
        StringBuilder result = new StringBuilder(Math.min(raw.length(), MAX_NOTE_LENGTH));
        for (int index = 0; index < raw.length() && result.length() < MAX_NOTE_LENGTH; index++) {
            char character = raw.charAt(index);
            if (character == '\n' || character == '\r' || character == '\t' || character >= ' ') result.append(character);
        }
        return result.toString();
    }

    /** 成就使用全局配置门槛，与每表的有效保留上限独立。 */
    public static boolean crossesAchievementThreshold(long previous, long current) {
        long achievementThreshold = ServerLootTableConfigManager.effectiveSettings().logLimit();
        return previous < achievementThreshold && current >= achievementThreshold;
    }

    /** 不携带网络会话或 payload 的领域意图。 */
    public sealed interface Intent permits FirstUnlock, Upsert, MergeAcquisition, UpdateNote, RemoveEntries, ClearEntries,
            ClearAllEntries, RemoveTable, ClearAll, SetRetention, Prune {
    }

    /** 正常记录延迟合并落盘。 */
    public record FirstUnlock(ResourceLocation tableId, @Nullable LootSourceType source, long gameTime, long dayTime) implements Intent {
        public FirstUnlock {
            gameTime = Math.max(0L, gameTime);
            dayTime = Math.max(0L, dayTime);
        }
    }

    /** 新增或更新日志，保留累计数与自动淘汰规则。 */
    public record Upsert(ResourceLocation tableId, ExcavationLogEntry entry) implements Intent {}
    /** 领取量按当前玩家的日志增量合并，适用于日志尚在加载的情况。 */
    public record MergeAcquisition(ResourceLocation tableId, ExcavationLogEntry delta,
                                   Map<String, Integer> legacyActual) implements Intent {}
    /** 备注清洗后延迟落盘。 */
    public record UpdateNote(ResourceLocation tableId, UUID entryId, @Nullable String note) implements Intent {}
    /** 删除选中条目，保留首次解锁、累计数及策略。 */
    public record RemoveEntries(ResourceLocation tableId, Set<UUID> entryIds) implements Intent {
        public RemoveEntries { entryIds = Set.copyOf(entryIds); }
    }
    /** 清空表内条目并保留元信息。 */
    public record ClearEntries(ResourceLocation tableId) implements Intent {}
    /** 清空所有条目并保留元信息。 */
    public record ClearAllEntries() implements Intent {}
    /** 删除整张表及其元信息。 */
    public record RemoveTable(ResourceLocation tableId) implements Intent {}
    /** 清空全部表及其元信息。 */
    public record ClearAll() implements Intent {}
    /** 修改单表保留上限，规则由表历史实施。 */
    public record SetRetention(ResourceLocation tableId, int limit) implements Intent {}
    /** 按时间保留最近条目，带备注条目受保护。 */
    public record Prune(ResourceLocation tableId, int keepCount) implements Intent {}

    /** 即时失败只返回 FAILED，成功回执由调用方按结果发送。 */
    public enum Status { CHANGED, UNCHANGED, NOT_FOUND, BLOCKED, FAILED }

    /** 一次成功写入产生的变更，立即变更在索引提交后才交付。 */
    public sealed interface Change permits TableChange, TableRemoved, ClearHistories {}

    /** 条目增删以及需要重新读取元数据的表。 */
    public record TableChange(ResourceLocation tableId, @Nullable ExcavationLogEntry entry,
                              List<UUID> removedEntryIds, boolean clearEntries) implements Change {}

    /** 彻底移除一张表及其累计量。 */
    public record TableRemoved(ResourceLocation tableId) implements Change {}

    /** 全局清理范围。 */
    public enum ClearHistories implements Change { ALL_ENTRIES, ALL_TABLES }

    /** 失败与空操作不产生可发布变更。 */
    public record Result(Status status, int removedCount, int retentionLimit,
                         @Nullable ArchaeologyJournalLogState.EntryUpsertResult upsert,
                         @Nullable Change change) {
        private static Result status(Status status) { return new Result(status, 0, 0, null, null); }
        private static Result changed(Change change, int count, int limit) {
            return new Result(Status.CHANGED, count, limit, null, change);
        }
        private static Result removed(Change change, int count) {
            return count > 0 ? changed(change, count, 0) : status(Status.NOT_FOUND);
        }
    }
}
