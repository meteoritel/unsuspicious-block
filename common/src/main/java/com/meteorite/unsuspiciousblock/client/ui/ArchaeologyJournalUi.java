package com.meteorite.unsuspiciousblock.client.ui;

import com.meteorite.unsuspiciousblock.client.ui.support.ArchaeologyJournalClientState;
import com.meteorite.unsuspiciousblock.journal.state.ArchaeologyJournalState;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;

public final class ArchaeologyJournalUi {
    @Nullable
    private static Consumer<ArchaeologyJournalState> opener;

    private ArchaeologyJournalUi() {
    }

    public static void registerOpener(Consumer<ArchaeologyJournalState> opener) {
        ArchaeologyJournalUi.opener = opener;
    }

    // 打开考古笔记 UI（使用客户端缓存的同步状态）
    public static void open() {
        Consumer<ArchaeologyJournalState> currentOpener = opener;
        if (currentOpener == null) return;
        currentOpener.accept(ArchaeologyJournalClientState.getState().copy());
    }
}
