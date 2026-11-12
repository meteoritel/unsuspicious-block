package com.meteorite.unsuspiciousblock.client.tooltip;

import com.meteorite.unsuspiciousblock.client.state.HandOfCatClientState;
import com.meteorite.unsuspiciousblock.client.ui.ArchaeologyJournalUi;
import com.meteorite.unsuspiciousblock.core.text.ClientTooltipBridge;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/***
 * 客户端 tooltip 数据桥实现。
 * <p>
 * 把只有客户端才能做的按键检测、文本折行、界面打开与客户端缓存读取，通过
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
    public List<Component> splitLines(Component text, int maxWidth) {
        Minecraft client = Minecraft.getInstance();
        Font font = client.font;
        if (font == null || maxWidth <= 0) {
            return List.of(text);
        }
        List<FormattedText> split = font.getSplitter().splitLines(text, maxWidth, Style.EMPTY);
        if (split.isEmpty()) {
            return List.of(text);
        }
        List<Component> result = new ArrayList<>(split.size());
        for (FormattedText line : split) {
            MutableComponent rebuilt = Component.empty();
            line.visit((style, value) -> {
                rebuilt.append(Component.literal(value).setStyle(style));
                return Optional.empty();
            }, Style.EMPTY);
            result.add(rebuilt);
        }
        return result;
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
