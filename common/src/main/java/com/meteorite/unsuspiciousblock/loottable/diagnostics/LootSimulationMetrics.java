package com.meteorite.unsuspiciousblock.loottable.diagnostics;

/**
 * 单表模拟的可选观测数据；不读取随机源、不参与概率、缓存或调度判断。
 * 用游戏 JVM 属性 usb.loot.profile=true 或环境变量 USB_LOOT_PROFILE=true 开启。
 * 分段时间是墙钟纳秒，嵌套明细不能与其父段相加；线程 CPU 仍由工作器单独计量。
 */
public final class LootSimulationMetrics {
    private static final boolean ENABLED = Boolean.parseBoolean(System.getProperty(
            "usb.loot.profile", System.getenv("USB_LOOT_PROFILE")));
    private static final ThreadLocal<LootSimulationMetrics> ACTIVE = new ThreadLocal<>();

    private final long[] counts = new long[Count.values().length];
    private final long[] nanos = new long[Stage.values().length];

    // 只有模拟时间片内的共享签名调用归属本任务，玩家侧调用不计入。
    public static LootSimulationMetrics current() {
        return ENABLED ? ACTIVE.get() : null;
    }

    // 保存外层观测器，时间片结束时必须恢复，避免跨任务或异常路径串账。
    public LootSimulationMetrics attach() {
        LootSimulationMetrics previous = current();
        if (ENABLED) {
            ACTIVE.set(this);
        }
        return previous;
    }

    // 恢复进入时间片前的状态；不在 tick 之间持有活动任务。
    public static void restore(LootSimulationMetrics previous) {
        if (ENABLED) {
            if (previous == null) {
                ACTIVE.remove();
            } else {
                ACTIVE.set(previous);
            }
        }
    }

    // 关闭时不调用时钟；时间值只能用于日志。
    public static long now() {
        return ENABLED ? System.nanoTime() : 0L;
    }

    // 累计实际执行次数；不计缓存命中为序列化或存储键构造。
    public void add(Count count, long amount) {
        if (ENABLED) {
            this.counts[count.ordinal()] += amount;
        }
    }

    // 记录一个已执行代码段的时间。
    public void end(Stage stage, long start) {
        if (ENABLED) {
            this.nanos[stage.ordinal()] += System.nanoTime() - start;
        }
    }

    // 仅在完成日志处格式化，热循环不创建日志字符串。
    public String summary() {
        if (!ENABLED) {
            return "";
        }
        StringBuilder text = new StringBuilder("，profile=v1");
        for (Count count : Count.values()) {
            text.append(' ').append(count.name()).append('=').append(this.counts[count.ordinal()]);
        }
        for (Stage stage : Stage.values()) {
            text.append(' ').append(stage.name()).append("_ns=").append(this.nanos[stage.ordinal()]);
        }
        return text.toString();
    }

    /** 抽取按根表调用计数，掉落按非空栈计数，不按栈内物品数量计数。 */
    public enum Count {
        SCENARIOS, ROLLS, DROPS, SCANS, EXACT_SERIALIZATIONS, STORED_KEY_CALLS,
        PREVIEW_BUILDS, MATCHED, RAW_SKIPPED, DERIVED, CANDIDATES_ADDED
    }

    /** 顶层阶段互不重叠；最后三项是父阶段内的嵌套明细。 */
    public enum Stage {
        PREPARE, BEGIN_ROLL, GENERATE, INJECT, MATCH, RAW_MATCH, DERIVE, KEY_LOOKUP,
        RECORD, CHILD_RECORD, FINISH, RESULT,
        SERIALIZE_DETAIL, STORED_KEY_DETAIL, PREVIEW_DETAIL
    }
}
