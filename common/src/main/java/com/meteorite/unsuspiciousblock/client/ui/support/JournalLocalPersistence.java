package com.meteorite.unsuspiciousblock.client.ui.support;

import com.meteorite.unsuspiciousblock.Constants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.storage.LevelResource;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

final class JournalLocalPersistence {
    private static final String STORAGE_DIR = "unsuspiciousblock_journal_logs";

    private JournalLocalPersistence() {
    }

    record Loaded(CompoundTag tag, boolean successful) {
    }

    static @Nullable Path resolveCurrentPath(String fileName) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return null;
        }
        if (minecraft.hasSingleplayerServer() && minecraft.getSingleplayerServer() != null) {
            return minecraft.getSingleplayerServer().getWorldPath(LevelResource.ROOT)
                    .resolve(STORAGE_DIR).resolve(minecraft.player.getUUID().toString()).resolve(fileName);
        }
        ServerData serverData = minecraft.getCurrentServer();
        if (serverData == null) {
            return null;
        }
        String serverKey = sanitize(serverData.ip);
        return minecraft.gameDirectory.toPath().resolve(STORAGE_DIR).resolve(serverKey)
                .resolve(minecraft.player.getUUID().toString()).resolve(fileName);
    }

    private static String sanitize(@Nullable String address) {
        return address == null || address.isBlank()
                ? "unknown_server" : address.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    static Loaded load(Path path, long maximumBytes) {
        if (!Files.exists(path)) {
            return new Loaded(new CompoundTag(), true);
        }
        try (InputStream input = Files.newInputStream(path)) {
            return new Loaded(NbtIo.readCompressed(input, NbtAccounter.create(maximumBytes)), true);
        } catch (IOException | RuntimeException exception) {
            Constants.LOG.warn("读取本地笔记文件失败: {}", path, exception);
            return new Loaded(new CompoundTag(), false);
        }
    }

    private static boolean writeAtomically(Path path, CompoundTag snapshot) {
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        boolean moved = false;
        try {
            Files.createDirectories(path.getParent());
            try (OutputStream output = Files.newOutputStream(temporary)) {
                NbtIo.writeCompressed(snapshot, output);
            }
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
            return true;
        } catch (IOException | RuntimeException exception) {
            Constants.LOG.warn("保存本地笔记文件失败，保留原路径快照重试: {}", path, exception);
            return false;
        } finally {
            if (!moved) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException exception) {
                    Constants.LOG.debug("清理本地笔记临时文件失败: {}", temporary, exception);
                }
            }
        }
    }

    static final class PendingWrites {
        private final Map<Path, CompoundTag> snapshots = new LinkedHashMap<>();

        void stage(Path path, CompoundTag snapshot) {
            // 同一路径只保留最新快照，后续重试不读取其他连接的可变状态。
            snapshots.put(path, snapshot.copy());
        }

        @Nullable CompoundTag pending(Path path) {
            CompoundTag snapshot = snapshots.get(path);
            return snapshot == null ? null : snapshot.copy();
        }

        boolean isIdle(Path path) {
            return !snapshots.containsKey(path);
        }

        void flush() {
            snapshots.entrySet().removeIf(entry -> writeAtomically(entry.getKey(), entry.getValue()));
        }
    }
}
