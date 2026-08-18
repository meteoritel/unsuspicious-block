package com.meteorite.unsuspiciousblock.client.ui;

import com.meteorite.unsuspiciousblock.client.ui.support.ArchaeologyJournalClientState;
import com.meteorite.unsuspiciousblock.journal.state.ArchaeologyJournalState;
import org.jetbrains.annotations.Nullable;

import java.util.function.BiConsumer;
import net.minecraft.resources.ResourceLocation;

/**
 * 客户端考古笔记打开入口，支持普通打开和携带初始物品搜索条件打开。
 */
public final class ArchaeologyJournalUi {
    @Nullable
    private static BiConsumer<ArchaeologyJournalState, @Nullable ResourceLocation> opener;

    private ArchaeologyJournalUi() {
    }

    public static void registerOpener(
            BiConsumer<ArchaeologyJournalState, @Nullable ResourceLocation> opener) {
        ArchaeologyJournalUi.opener = opener;
    }

    // 打开考古笔记 UI（使用客户端缓存的同步状态）
    public static void open() {
        BiConsumer<ArchaeologyJournalState, @Nullable ResourceLocation> currentOpener = opener;
        if (currentOpener == null) return;
        currentOpener.accept(ArchaeologyJournalClientState.getState().copy(), null);
    }

    // 从快捷键打开考古笔记 UI，逻辑与 open() 相同
    public static void openFromKeybind() {
        open();
    }

    // 打开手册并立即按物品注册名执行搜索。
    public static void openForItem(ResourceLocation itemId) {
        BiConsumer<ArchaeologyJournalState, @Nullable ResourceLocation> currentOpener = opener;
        if (currentOpener == null) return;
        currentOpener.accept(ArchaeologyJournalClientState.getState().copy(), itemId);
    }
}
