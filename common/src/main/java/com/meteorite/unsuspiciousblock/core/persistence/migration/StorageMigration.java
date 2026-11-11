package com.meteorite.unsuspiciousblock.core.persistence.migration;

import com.meteorite.unsuspiciousblock.Constants;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 可恢复的磁盘布局迁移。领域 Adapter 保留原 manifest 编码与文件 IO，
 * 本执行器统一 pending、目标写入、committed、幂等源清理的顺序。
 *
 * @param <R> 领域写入 manifest 的迁移结果
 */
public final class StorageMigration<R> {
    private final MigrationVersions versions;
    private final Map<Integer, Step<R>> steps;

    /** 注册完整的连续迁移链，缺步、重复来源或跳步在接触磁盘前报错。 */
    public StorageMigration(MigrationVersions versions, List<Step<R>> steps) {
        this.versions = versions;
        Map<Integer, Step<R>> indexed = new HashMap<>();
        for (Step<R> step : steps) {
            versions.validateStep(step.fromVersion(), step.toVersion());
            if (indexed.put(step.fromVersion(), step) != null) {
                throw new IllegalArgumentException(versions.name() + " 重复迁移来源: " + step.fromVersion());
            }
        }
        for (int from = versions.oldest(); from < versions.current(); from++) {
            if (!indexed.containsKey(from)) {
                throw new IllegalArgumentException(versions.name() + " 缺少 v" + from
                        + " -> v" + (from + 1) + " 迁移步骤");
            }
        }
        this.steps = Map.copyOf(indexed);
    }

    /**
     * 恢复并迁移到当前布局；提交成功后才清理源。任意 IO 失败向调用方传播，
     * 下次从磁盘 checkpoint 重新读取状态后重试；提交后的清理可重复执行。
     */
    public State<R> migrate(State<R> initial, Checkpoint<R> checkpoint) throws IOException {
        versions.validate(initial.committedVersion());
        Pending pending = initial.pending();
        if (pending != null) {
            versions.validateStep(pending.fromVersion(), pending.toVersion());
            if (pending.fromVersion() != initial.committedVersion()) {
                throw new IllegalArgumentException(versions.name() + " pending 起点与已提交版本不一致");
            }
        }

        for (int from = versions.oldest(); from < initial.committedVersion(); from++) {
            steps.get(from).cleanup().run();
        }

        State<R> state = initial;
        while (state.committedVersion() < versions.current()) {
            Step<R> step = steps.get(state.committedVersion());
            if (pending != null) {
                if (step.recoveryPolicy() == RecoveryPolicy.ABORT_IF_INTERRUPTED) {
                    throw new IllegalStateException(versions.name() + " 迁移 v" + step.fromVersion()
                            + " -> v" + step.toVersion() + " 不支持自动重放，需要人工恢复");
                }
                Constants.LOG.warn(Constants.LOG_TAG + "恢复 {} 迁移: v{} -> v{}，策略={}",
                        versions.name(), step.fromVersion(), step.toVersion(), step.recoveryPolicy());
            } else {
                checkpoint.writePending(new Pending(step.fromVersion(), step.toVersion()));
            }

            R result = step.action().run();
            checkpoint.writeCommitted(step.toVersion(), result);
            state = new State<>(step.toVersion(), null, result);
            pending = null;
            step.cleanup().run();
            Constants.LOG.info(Constants.LOG_TAG + "{} 迁移完成: v{} -> v{}",
                    versions.name(), step.fromVersion(), step.toVersion());
        }
        return state;
    }

    /** 中断步骤只有保源且可重复覆盖目标时才允许自动重放。 */
    public enum RecoveryPolicy {
        RESTART_FROM_SOURCE,
        ABORT_IF_INTERRUPTED
    }

    /** 生成全部目标文件，返回待提交的领域结果；提交前保留源数据。 */
    @FunctionalInterface
    public interface Action<R> {
        /** IO 失败须抛出，避免把不完整目标标记为已提交。 */
        R run() throws IOException;
    }

    /** 提交后清理动作；实现必须幂等，以便提交后中断时重试。 */
    @FunctionalInterface
    public interface Cleanup {
        /** 清理失败向调用方传播，由下一次启动继续。 */
        void run() throws IOException;
    }

    /** 一步显式登记的磁盘迁移及其恢复契约。 */
    public record Step<R>(int fromVersion, int toVersion, RecoveryPolicy recoveryPolicy,
                          Action<R> action, Cleanup cleanup) {
    }

    /** manifest 中尚未提交的确切一步。 */
    public record Pending(int fromVersion, int toVersion) {
    }

    /** 从持久化 checkpoint 读取的状态；result 是最后一次已提交的结果。 */
    public record State<R>(int committedVersion, @Nullable Pending pending, R result) {
    }

    /** 领域 Adapter 负责原子写出 checkpoint；写入失败时保留原已提交版本。 */
    public interface Checkpoint<R> {
        /** 在迁移动作前持久化确切的待执行步骤。 */
        void writePending(Pending pending) throws IOException;

        /** 在全部目标持久化后提交版本与领域结果。 */
        void writeCommitted(int version, R result) throws IOException;
    }
}
