package com.meteorite.unsuspiciousblock.client.tooltip;

import com.meteorite.unsuspiciousblock.client.state.HandOfCatClientState;
import com.meteorite.unsuspiciousblock.client.ui.ArchaeologyJournalUi;
import com.meteorite.unsuspiciousblock.text.ClientTooltipBridge;
import net.minecraft.client.gui.screens.Screen;

import java.util.UUID;

/***
 * 客户端 tooltip 数据桥实现。
 * <p>
 * 把只有客户端才能做的按键检测、界面打开与客户端缓存读取，通过
 * {@link ClientTooltipBridge} 暴露给 common 的服务端可达类；由两个平台客户端入口安装。
 */
public final class ClientTooltipHooks implements ClientTooltipBridge.Hooks {

    public ClientTooltipHooks() {
    }

    @Override
    public boolean isExpandKeyDown() {
        return Screen.hasShiftDown();
    }

    @Override
    public void openJournal() {
        ArchaeologyJournalUi.open();
    }

    @Override
    public int handOfCatFavor() {
        return HandOfCatClientState.getCachedFavor();
    }

    @Override
    public int handOfCatLives() {
        return HandOfCatClientState.getCachedNineLivesCount();
    }

    @Override
    public boolean isLocalPlayer(UUID playerUuid) {
        return HandOfCatClientState.isLocalPlayer(playerUuid);
    }
}
