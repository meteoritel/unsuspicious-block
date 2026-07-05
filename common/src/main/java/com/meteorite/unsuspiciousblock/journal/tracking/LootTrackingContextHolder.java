package com.meteorite.unsuspiciousblock.journal.tracking;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 战利品追踪上下文 ThreadLocal 栈持有者。
 * <p>
 * 追踪入口（钓鱼 / 开箱 / 考古刷拭等）在调用 {@code LootTable.getRandomItems} 前
 * {@link #push} 上下文，调用结束后 {@link #pop}；嵌套子表 Mixin 通过 {@link #current}
 * 读取上下文并自动捕获子表物品。
 * <p>
 * 用栈而非单值：支持嵌套表内部再嵌套表的多层场景，每层 NestedLootTable 调用
 * {@link LootTrackingContext#descend} 压栈，调用结束弹栈。
 * <p>
 * <b>调用方必须在 try-finally 中配对 pop，避免上下文泄漏到后续无关调用。</b>
 * 仅在服务端主线程使用，无需同步。
 */
public final class LootTrackingContextHolder {
    private static final ThreadLocal<Deque<LootTrackingContext>> STACK =
            ThreadLocal.withInitial(ArrayDeque::new);

    private LootTrackingContextHolder() {
    }

    // 压入上下文到栈顶
    public static void push(LootTrackingContext ctx) {
        if (ctx != null) {
            STACK.get().push(ctx);
        }
    }

    // 返回栈顶上下文；栈空时返回 null
    public static @Nullable LootTrackingContext current() {
        Deque<LootTrackingContext> stack = STACK.get();
        return stack.isEmpty() ? null : stack.peek();
    }

    // 弹出栈顶上下文；栈空时无操作
    public static void pop() {
        Deque<LootTrackingContext> stack = STACK.get();
        if (!stack.isEmpty()) {
            stack.pop();
        }
        if (stack.isEmpty()) {
            STACK.remove();
        }
    }

    // 清空栈（异常恢复用）
    public static void clear() {
        STACK.get().clear();
        STACK.remove();
    }
}
