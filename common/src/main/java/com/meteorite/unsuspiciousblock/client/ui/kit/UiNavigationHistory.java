package com.meteorite.unsuspiciousblock.client.ui.kit;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** 有界导航历史；宿主提供快照、有效性检查与恢复，输入和模态优先级由宿主决定。 */
public final class UiNavigationHistory<State> {
    private final int capacity;
    private final Deque<State> entries = new ArrayDeque<>();
    private boolean restoring;

    public UiNavigationHistory(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("Navigation capacity must be positive");
        this.capacity = capacity;
    }

    public void push(State state) {
        Objects.requireNonNull(state);
        if (restoring || state.equals(entries.peekLast())) return;
        entries.addLast(state);
        while (entries.size() > capacity) entries.removeFirst();
    }

    public boolean canGoBack(Predicate<State> valid) {
        entries.removeIf(state -> !valid.test(state));
        return !entries.isEmpty();
    }

    public boolean back(Predicate<State> valid, Consumer<State> restore) {
        while (!entries.isEmpty()) {
            State state = entries.removeLast();
            if (!valid.test(state)) continue;
            restoring = true;
            try { restore.accept(state); } finally { restoring = false; }
            return true;
        }
        return false;
    }

    public void clear() { entries.clear(); }
}
