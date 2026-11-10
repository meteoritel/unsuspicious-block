package com.meteorite.unsuspiciousblock.blockentity;

import com.meteorite.unsuspiciousblock.journal.tracking.PendingJournal;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/** 普通与 Lootr 可疑方块共享的持久状态；临时刷拭上下文归宿主。 */
public final class BrushableTracking {
    private static final String PARSED = "unsuspiciousblock_loot_table_parsed";
    private static final String TABLE = "unsuspiciousblock_loot_table_name";
    private final ScanIdentity scan = new ScanIdentity(ScanIdentity.Layout.BRUSHABLE);
    private final PendingJournal pending;
    private boolean parsed;
    private @Nullable ResourceLocation table;

    public BrushableTracking(Runnable changed) { pending = new PendingJournal(changed); }
    public ScanIdentity scan() { return scan; }
    public PendingJournal pending() { return pending; }
    public @Nullable ResourceLocation table() { return table; }

    public boolean recordResolved(ResourceLocation next) {
        boolean changed = !parsed || !Objects.equals(table, next);
        table = next;
        parsed = true;
        return changed;
    }

    public boolean clearScan() {
        if (!scan.clear()) return false;
        pending.discard();
        return true;
    }

    public boolean clearResolved() {
        boolean changed = parsed || table != null || pending.current() != null;
        parsed = false;
        table = null;
        pending.discard();
        return changed;
    }

    public void write(CompoundTag tag) {
        scan.write(tag);
        tag.putBoolean(PARSED, parsed);
        if (parsed && table != null) tag.putString(TABLE, table.toString());
        else tag.remove(TABLE);
        pending.write(tag);
    }

    public void read(CompoundTag tag) {
        scan.read(tag);
        if (!tag.getBoolean(PARSED)) {
            parsed = false;
            table = null;
            pending.read(new CompoundTag());
            return;
        }
        table = tag.contains(TABLE) ? ResourceLocation.tryParse(tag.getString(TABLE)) : null;
        parsed = table != null;
        pending.read(tag);
    }
}
