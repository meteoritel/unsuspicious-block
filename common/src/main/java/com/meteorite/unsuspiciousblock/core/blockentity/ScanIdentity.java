package com.meteorite.unsuspiciousblock.core.blockentity;

import net.minecraft.nbt.CompoundTag;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.UUID;

/** 扫描身份兼容普通方块标记与封存方块的 UUID 形态。 */
public final class ScanIdentity {
    public enum Layout { BRUSHABLE, SEALED }
    private static final String SCANNED = "unsuspiciousblock_scanned";
    private static final String SCANNER = "unsuspiciousblock_scanner_uuid";
    private static final String SEALED_SCANNER = "ScannerUuid";
    private final Layout layout;
    private boolean scanned;
    private @Nullable UUID scanner;

    public ScanIdentity(Layout layout) { this.layout = layout; }
    public boolean scanned() { return scanned; }
    public @Nullable UUID scanner() { return scanner; }

    public boolean mark(UUID uuid) {
        if (scanned && Objects.equals(scanner, uuid)) return false;
        scanner = uuid;
        scanned = true;
        return true;
    }

    public boolean clear() {
        if (!scanned && scanner == null) return false;
        scanned = false;
        scanner = null;
        return true;
    }

    public void write(CompoundTag tag) {
        if (layout == Layout.SEALED) {
            if (scanner != null) tag.putUUID(SEALED_SCANNER, scanner);
        } else {
            tag.putBoolean(SCANNED, scanned);
            if (scanned && scanner != null) tag.putUUID(SCANNER, scanner);
            else tag.remove(SCANNER);
        }
    }

    public void read(CompoundTag tag) {
        if (layout == Layout.SEALED) {
            scanner = tag.hasUUID(SEALED_SCANNER) ? tag.getUUID(SEALED_SCANNER) : null;
            scanned = scanner != null;
        } else {
            scanned = tag.getBoolean(SCANNED);
            scanner = scanned && tag.contains(SCANNER) ? tag.getUUID(SCANNER) : null;
        }
    }
}
