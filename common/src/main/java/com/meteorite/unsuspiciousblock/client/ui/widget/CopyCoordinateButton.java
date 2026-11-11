package com.meteorite.unsuspiciousblock.client.ui.widget;

import com.meteorite.meteosuikit.api.control.UiControl;
import com.meteorite.unsuspiciousblock.client.ui.layout.JournalLayout;
import com.meteorite.unsuspiciousblock.client.ui.theme.UiAtlas;
import com.meteorite.unsuspiciousblock.core.journal.state.ExcavationLogEntry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;

import java.util.List;

/**
 * 复制坐标按钮——统一的图集皮肤与指令复制逻辑，同时是坐标**显示文本**的唯一格式化入口。
 * 用于日志列表页条目行与日志详情页坐标信息行。
 * 复制指令格式：/execute as @s in <维度注册名> run tp @s x (y+1) z
 */
public final class CopyCoordinateButton {
    public static final int WIDTH = JournalLayout.LOG_ENTRY_COPY_BTN_WIDTH;
    public static final int HEIGHT = JournalLayout.LOG_ENTRY_COPY_BTN_HEIGHT;

    private CopyCoordinateButton() {
    }

    /** 配置复制坐标控件的统一轻量 9-slice 皮肤、COPY 图集图标与点击动作。 */
    public static void configure(UiControl control, ExcavationLogEntry entry) {
        Component label = Component.translatable("screen.unsuspiciousblock.archaeology_journal.log_copy_teleport_tooltip");
        control.configure(Minecraft.getInstance().font, Component.empty(),
                UiAtlas.icon(UiAtlas.Icon.COPY), List.of(label), () -> copyCommand(entry));
        control.setAccessibleName(label);
        control.setSkin(UiAtlas.QUIET_BUTTON_SKIN);
    }

    /** 坐标显示文本的唯一格式化入口：日志列表页与详情页共用，避免出现多种括号格式。 */
    public static String formatCoordinates(int x, int y, int z) {
        return Component.translatable(
                "screen.unsuspiciousblock.archaeology_journal.coords_format", x, y, z).getString();
    }

    /** 复制 {@code /execute as @s in <dim> run tp @s x (y+1) z} 到剪贴板并反馈提示。 */
    public static void copyCommand(ExcavationLogEntry entry) {
        var pos = entry.pos();
        ResourceLocation dimId = entry.dimensionId();
        String dimStr = dimId != null ? dimId.toString() : "minecraft:overworld";
        // y+1：传送到方块上方，避免卡在方块内
        String command = String.format("/execute as @s in %s run tp @s %d %d %d",
                dimStr, pos.getX(), pos.getY() + 1, pos.getZ());
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.keyboardHandler.setClipboard(command);
        minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
        if (minecraft.player != null) {
            minecraft.player.displayClientMessage(Component.translatable(
                    "screen.unsuspiciousblock.archaeology_journal.log_copy_teleport_success", command), true);
        }
    }
}
