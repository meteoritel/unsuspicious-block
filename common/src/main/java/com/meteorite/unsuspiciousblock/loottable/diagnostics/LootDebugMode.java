package com.meteorite.unsuspiciousblock.loottable.diagnostics;

import com.meteorite.unsuspiciousblock.Constants;
import net.minecraft.resources.ResourceLocation;

/**
 * 战利品**调试模式**开关——只在开发/验收时让"函数捕获调试表"与它的目录分类进入考古笔记。
 * <p>
 * 开启口径与 {@link LootSimulationMetrics} 一致（系统属性优先，其次环境变量），因此 common 层直接可读，
 * 不需要任何平台类，两端行为一致：
 * <pre>
 *   -Dusb.loot.debug=true      或      USB_LOOT_DEBUG=true
 * </pre>
 * <p>
 * 关闭时：调试战利品表不进收录闭包、调试分类资源被跳过——普通玩家看不到任何调试内容。
 * 表本身仍然是正常注册的战利品表（数据包资源无法按运行时开关条件化），但它不会出现在目录里，
 * 也没有任何游戏内路径会去抽取它。
 */
public final class LootDebugMode {
    /** 系统属性名。 */
    public static final String PROPERTY = "usb.loot.debug";
    /** 环境变量名。 */
    public static final String ENVIRONMENT = "USB_LOOT_DEBUG";

    /** 调试分类资源的文件名（不含 .json）。 */
    public static final String DEBUG_CATEGORY_FILE = "dev_debug";

    /** 调试战利品表的路径前缀；只有本模组命名空间下的这个前缀会被视作调试表。 */
    public static final String DEBUG_TABLE_PATH_PREFIX = "archaeology/debug/";

    private static final boolean ENABLED = Boolean.parseBoolean(
            System.getProperty(PROPERTY, System.getenv(ENVIRONMENT)));

    private LootDebugMode() {
    }

    // 是否处于调试模式；进程内只读一次，避免热路径反复解析属性
    public static boolean isEnabled() {
        return ENABLED;
    }

    // 该表是否为调试表：只认本模组命名空间下的固定路径前缀，避免误伤其它模组的同名路径
    public static boolean isDebugTable(ResourceLocation tableId) {
        return Constants.MOD_ID.equals(tableId.getNamespace())
                && tableId.getPath().startsWith(DEBUG_TABLE_PATH_PREFIX);
    }

    // 该表在当前模式下是否应参与目录收录：非调试表始终收录，调试表只在调试模式下收录
    public static boolean allows(ResourceLocation tableId) {
        return ENABLED || !isDebugTable(tableId);
    }
}
