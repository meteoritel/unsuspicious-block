package com.meteorite.unsuspiciousblock.journal.tracking;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 战利品追踪上下文 ThreadLocal 栈持有者。
 * <p>
 * 追踪入口（钓鱼 / 开箱 / 考古刷拭等）在调用 {@code LootTable.getRandomItems} 前
 * 通过 {@link #open} 建立作用域；嵌套子表 Mixin 通过 {@link #current} 读取上下文并自动捕获子表物品。
 * <p>
 * 用栈而非单值：支持嵌套表内部再嵌套表的多层场景，每层 NestedLootTable 调用
 * {@link LootTrackingContext#descend} 压栈，调用结束弹栈。
 * <p>
 * <b>调用方必须使用 try-with-resources 关闭作用域，避免上下文泄漏到后续无关调用。</b>
 * 仅在服务端主线程使用，无需同步。
 */
public final class LootTrackingContextHolder {
    private static final ThreadLocal<Deque<Frame>> STACK =
            ThreadLocal.withInitial(ArrayDeque::new);

    private LootTrackingContextHolder() {
    }

    // 建立上下文作用域；关闭时恢复到进入前的栈深度
    public static Scope open(@Nullable LootTrackingContext ctx) {
        if (ctx == null) {
            return Scope.INACTIVE;
        }
        Deque<Frame> stack = STACK.get();
        LootSession session = stack.isEmpty()
                ? new LootSession(ctx)
                : stack.peek().session();
        return open(session, ctx);
    }

    // 使用指定聚合会话建立上下文作用域，供根入口在生成结束后提交同一会话
    public static Scope open(LootSession session, LootTrackingContext ctx) {
        if (session == null || ctx == null) {
            return Scope.INACTIVE;
        }
        if (!session.rootContext().rootTableId().equals(ctx.rootTableId())) {
            throw new IllegalArgumentException("LootSession 与 LootTrackingContext 的根表必须一致");
        }
        Deque<Frame> stack = STACK.get();
        int previousDepth = stack.size();
        stack.push(new Frame(session, ctx));
        return new Scope(Thread.currentThread(), previousDepth);
    }

    // 返回栈顶上下文；栈空时返回 null
    public static @Nullable LootTrackingContext current() {
        Deque<Frame> stack = STACK.get();
        return stack.isEmpty() ? null : stack.peek().context();
    }

    // 返回当前聚合会话；栈空时返回 null
    public static @Nullable LootSession currentSession() {
        Deque<Frame> stack = STACK.get();
        return stack.isEmpty() ? null : stack.peek().session();
    }

    /**
     * 单次追踪上下文的生命周期令牌。
     */
    public static final class Scope implements AutoCloseable {
        private static final Scope INACTIVE = new Scope(null, -1);

        @Nullable
        private final Thread ownerThread;
        private final int previousDepth;
        private boolean closed;

        private Scope(@Nullable Thread ownerThread, int previousDepth) {
            this.ownerThread = ownerThread;
            this.previousDepth = previousDepth;
        }

        @Override
        public void close() {
            if (this.closed || this.ownerThread == null) {
                return;
            }
            if (Thread.currentThread() != this.ownerThread) {
                throw new IllegalStateException("LootTrackingContext 作用域必须在创建线程关闭");
            }

            Deque<Frame> stack = STACK.get();
            while (stack.size() > this.previousDepth) {
                stack.pop();
            }
            if (stack.isEmpty()) {
                STACK.remove();
            }
            this.closed = true;
        }
    }

    private record Frame(LootSession session, LootTrackingContext context) {
    }
}
