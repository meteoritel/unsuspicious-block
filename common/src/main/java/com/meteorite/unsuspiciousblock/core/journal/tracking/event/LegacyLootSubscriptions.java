package com.meteorite.unsuspiciousblock.core.journal.tracking.event;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/** 已公开订阅入口的兼容 adapter，不控制内建五步是否执行。 */
final class LegacyLootSubscriptions {
    private static final List<Subscriber> SUBSCRIBERS = new ArrayList<>();
    private static long sequence;
    private static long initializationSequence = Long.MAX_VALUE;

    private LegacyLootSubscriptions() {}

    static void register(int priority, Consumer<LootDiscoveredEvent> listener) {
        SUBSCRIBERS.add(new Subscriber(priority, sequence++, Objects.requireNonNull(listener, "listener")));
        SUBSCRIBERS.sort(Comparator.comparingInt(Subscriber::priority));
    }

    static void markCommonInitialization() {
        if (initializationSequence == Long.MAX_VALUE) initializationSequence = sequence++;
    }

    static Dispatch dispatch(LootDiscoveredEvent event) { return new Dispatch(event); }

    static final class Dispatch {
        private final LootDiscoveredEvent event;
        private final List<Subscriber> subscribers = List.copyOf(SUBSCRIBERS);
        private int next;

        private Dispatch(LootDiscoveredEvent event) { this.event = event; }

        void before(LootTrackingBootstrap.Step step) {
            int priority = switch (step) {
                case UNLOCK -> 100;
                case FIRST_UNLOCK -> 200;
                case SETTLE -> 250;
                case CHALLENGE -> 300;
                case COMPLETION_REWARD -> 350;
            };
            while (next < subscribers.size()) {
                Subscriber subscriber = subscribers.get(next);
                if (subscriber.priority() > priority || subscriber.priority() == priority
                        && subscriber.sequence() > initializationSequence) break;
                invoke(subscriber);
                next++;
            }
        }

        void finish() {
            while (next < subscribers.size()) invoke(subscribers.get(next++));
        }

        private void invoke(Subscriber subscriber) {
            LootTrackingEvents.runStep(event, "extension[priority=" + subscriber.priority() + ", registration="
                    + subscriber.sequence() + "]", () -> subscriber.listener().accept(event));
        }
    }

    private record Subscriber(int priority, long sequence, Consumer<LootDiscoveredEvent> listener) {}
}
