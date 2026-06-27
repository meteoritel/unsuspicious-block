package com.meteorite.unsuspiciousblock.client.keybind;

import com.meteorite.unsuspiciousblock.Constants;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

/**
 * 模组按键绑定注册表。
 * 在 Fabric/NeoForge 客户端初始化时调用 {@link #register()} 将绑定注册到 Minecraft 的按键设置中，
 * 使得玩家可以在游戏内「控制设置 → 按键绑定」中自由修改。
 */
public class ModKeyBindings {

    /** 扫描等级切换键，默认 V 键 */
    public static final KeyMapping SCAN_LEVEL_CYCLE = new KeyMapping(
            "key.unsuspiciousblock.scan_level_cycle",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_V,
            "key.categories.unsuspiciousblock"
    );

    /** 打开考古手册键，默认 C 键 */
    public static final KeyMapping JOURNAL_OPEN = new KeyMapping(
            "key.unsuspiciousblock.open_journal",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_C,
            "key.categories.unsuspiciousblock"
    );

    /** 切换「猫的威慑」被动开关键，默认 G 键 */
    public static final KeyMapping CAT_DETERRENCE_TOGGLE = new KeyMapping(
            "key.unsuspiciousblock.cat_deterrence_toggle",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_G,
            "key.categories.unsuspiciousblock"
    );

    private ModKeyBindings() {
    }

    /**
     * 将所有按键绑定注册到 Minecraft 的按键映射。
     * Fabric 版通过 {@code KeyBindingHelper.registerKeyBinding()} 调用；
     * NeoForge 版通过 {@code RegisterKeyMappingsEvent} 调用。
     * 两种方式最终都调用此方法。
     */
    public static void register() {
        // Fabric 和 NeoForge 各自使用平台 API 来注册，
        // 因此此方法仅用于 NeoForge 的 RegisterKeyMappingsEvent，
        // Fabric 侧直接在 UnsuspiciousBlockFabricClient 中使用 KeyBindingHelper。
        Constants.LOG.debug("ModKeyBindings registered");
    }
}