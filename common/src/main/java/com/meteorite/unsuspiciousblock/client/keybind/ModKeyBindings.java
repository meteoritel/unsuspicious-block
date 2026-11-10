package com.meteorite.unsuspiciousblock.client.keybind;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * 模组按键绑定注册表。
 * 清单由 Fabric/NeoForge 客户端入口分别交给各自 Loader API 注册，
 * 玩家可以在游戏内「控制设置 → 按键绑定」中自由修改。
 */
public final class ModKeyBindings {

    /** 扫描等级切换键，默认 V 键 */
    public static final KeyMapping SCAN_LEVEL_CYCLE = new KeyMapping(
            "key.unsuspiciousblock.scan_level_cycle",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_V,
            "key.categories.unsuspiciousblock"
    );

    /** 打开考古笔记键，默认 C 键 */
    public static final KeyMapping JOURNAL_OPEN = new KeyMapping(
            "key.unsuspiciousblock.open_journal",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_C,
            "key.categories.unsuspiciousblock"
    );

    /** 切换「猫的威慑」被动开关键，默认未绑定（可在控制设置中自行绑定） */
    public static final KeyMapping CAT_DETERRENCE_TOGGLE = new KeyMapping(
            "key.unsuspiciousblock.cat_deterrence_toggle",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_UNKNOWN,
            "key.categories.unsuspiciousblock"
    );

    /** 切换「轻步」压力板触发开关，默认未绑定（可在控制设置中自行绑定） */
    public static final KeyMapping CAT_LIGHT_STEP_TOGGLE = new KeyMapping(
            "key.unsuspiciousblock.cat_light_step_toggle",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_UNKNOWN,
            "key.categories.unsuspiciousblock"
    );

    /** 扫描仪 HUD 显示开关键，默认 H 键 */
    public static final KeyMapping READER_HUD_TOGGLE = new KeyMapping(
            "key.unsuspiciousblock.reader_hud_toggle",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_H,
            "key.categories.unsuspiciousblock"
    );

    /** 客户端平台按键注册清单。 */
    public static final List<KeyMapping> MANIFEST = List.of(
            SCAN_LEVEL_CYCLE,
            JOURNAL_OPEN,
            CAT_DETERRENCE_TOGGLE,
            CAT_LIGHT_STEP_TOGGLE,
            READER_HUD_TOGGLE
    );

    private ModKeyBindings() {
    }

}
