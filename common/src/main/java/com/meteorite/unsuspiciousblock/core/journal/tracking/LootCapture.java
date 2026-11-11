package com.meteorite.unsuspiciousblock.core.journal.tracking;

import com.meteorite.unsuspiciousblock.core.journal.catalog.ArchaeologyJournalServerCatalog;
import com.meteorite.unsuspiciousblock.core.journal.state.ArchaeologyJournalStateHolder;
import com.meteorite.unsuspiciousblock.core.journal.state.LootSourceType;
import com.meteorite.unsuspiciousblock.core.journal.tracking.event.LootTrackingEvents;
import com.meteorite.unsuspiciousblock.core.journal.tracking.settlement.LootSettlementTarget;
import com.meteorite.unsuspiciousblock.core.loottable.signature.LootResultSignature;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/** 拥有根生成的会话与作用域；未跟踪时仍执行生成，异常时不提交半成品。 */
public final class LootCapture {
    private LootCapture() {}

    /** 生成入口的宿主事实；刷拭可传入原始触发时间。 */
    public record Source(@Nullable ServerPlayer player, ResourceLocation table, LootSourceType type,
            BlockPos pos, @Nullable ResourceLocation block, long gameTime, long dayTime) {
        public Source(@Nullable ServerPlayer player, ResourceLocation table, LootSourceType type,
                ServerLevel level, BlockPos pos, @Nullable ResourceLocation block) {
            this(player, table, type, pos, block, level.getGameTime(), level.getDayTime());
        }
    }

    public static <T extends Iterable<ItemStack>> T capture(Source source, LootSettlementTarget target,
            Supplier<T> generator) {
        try (CaptureHandle handle = begin(source)) {
            T generated = handle.roll(generator);
            handle.complete(generated, target);
            return generated;
        }
    }

    /** 已确定最终结果的来源直接提交，不再次生成。 */
    public static void submitFinal(Source source, ItemStack stack, LootSettlementTarget target) {
        try (CaptureHandle handle = begin(source)) {
            handle.complete(List.of(stack), target);
        }
    }

    /** 仅供跨既有注入相位的 adapter 持有；close 只终止，不隐式提交。 */
    public static CaptureHandle begin(Source source) {
        LootTrackingContext parent = LootTrackingContextHolder.current();
        LootSession parentSession = LootTrackingContextHolder.currentSession();
        if (parent != null && parentSession != null && parent.player() == source.player()
                && parent.lootSource().equals(source.type()) && !parent.currentTableId().equals(source.table())) {
            return new CaptureHandle(parentSession, parent.descend(source.table()), false);
        }
        LootSession session = null;
        LootTrackingContext context = null;
        if (source.player() != null && ArchaeologyJournalServerCatalog.tracked(source.table())
                && ArchaeologyJournalStateHolder.getState(source.player()) != null) {
            context = LootTrackingContext.root(source.player(), source.table(), source.type(),
                    source.gameTime(), source.dayTime(), source.pos(), source.block());
            session = new LootSession(context);
        }
        return new CaptureHandle(session, context, true);
    }

    /** 每个根入口独占一个句柄；嵌套表继续使用栈顶会话。 */
    public static final class CaptureHandle implements AutoCloseable {
        private final @Nullable LootSession session;
        private final @Nullable LootTrackingContext context;
        private final boolean root;
        private boolean completed;
        private boolean closed;

        private CaptureHandle(@Nullable LootSession session, @Nullable LootTrackingContext context, boolean root) {
            this.session = session;
            this.context = context;
            this.root = root;
        }

        public @Nullable LootTrackingContext context() { return context; }

        public <T> T roll(Supplier<T> generator) {
            requireOpen();
            try (var ignored = LootTrackingContextHolder.open(session, context())) {
                return generator.get();
            } catch (RuntimeException | Error failure) {
                close();
                throw failure;
            }
        }

        public void complete(Iterable<ItemStack> stacks, LootSettlementTarget target) {
            requireOpen();
            Map<String, Integer> counts = new LinkedHashMap<>();
            if (session != null) {
                for (ItemStack stack : stacks) {
                    if (stack.isEmpty()) continue;
                    LootResultSignature signature = resolveSignature(session, stack);
                    counts.merge(signature.toStoredKey(), stack.getCount(), Integer::sum);
                }
            }
            complete(counts, target);
        }

        public void complete(ItemStack stack, LootSettlementTarget target) { complete(List.of(stack), target); }

        public void complete(Map<String, Integer> counts, LootSettlementTarget target) {
            requireOpen();
            completed = true;
            if (session == null) return;
            if (root) LootTrackingEvents.submit(session, counts, target);
            else session.capture(context, counts);
        }

        private LootResultSignature resolveSignature(LootSession session, ItemStack stack) {
            if (!root && context != null) {
                LootResultSignature local = ArchaeologyLootRuntimeTracker.resolveSignature(context.currentTableId(), stack);
                if (local != null) return local;
            }
            for (ResourceLocation table : session.discoveredTableIds()) {
                LootResultSignature signature = ArchaeologyLootRuntimeTracker.resolveSignature(table, stack);
                if (signature != null) return signature;
            }
            return LootResultSignature.plain(BuiltInRegistries.ITEM.getKey(stack.getItem()));
        }

        private void requireOpen() {
            if (closed || completed) throw new IllegalStateException("战利品捕获已结束");
        }

        @Override
        public void close() { closed = true; }
    }
}
