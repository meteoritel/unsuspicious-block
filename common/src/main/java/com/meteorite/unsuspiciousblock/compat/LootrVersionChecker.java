package com.meteorite.unsuspiciousblock.compat;

import com.meteorite.unsuspiciousblock.Constants;
import org.jetbrains.annotations.Nullable;

import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.util.jar.Manifest;

/**
 * Lootr 版本检测工具，是 Lootr 联动检测的<b>唯一权威来源</b>。
 * <p>
 * {@link com.meteorite.unsuspiciousblock.mixin.compat.lootr.LootrCompatMixinPlugin}
 * 和 {@link com.meteorite.unsuspiciousblock.UnsuspiciousBlockCommon#init()}
 * 均通过本类获取检测结果，避免逻辑重复。
 *
 * <h3>两级门控策略</h3>
 * <ol>
 *   <li><b>基础 Lootr（Tier 1）</b>：检测 {@code LootrAPI} 是否可加载。
 *       控制 {@code DefaultLootFillerMixin} 和 {@code LootrInventoryMixin} 的加载。
 *       <p>
 *       {@code DefaultLootFiller} 与 {@code LootrInventory} 是 Lootr 核心类，
 *       自首个版本就存在，{@code LootrAPI} 存在即保证它们存在，无需单独检测。
 *       </li>
 *   <li><b>可疑方块联动（Tier 2）</b>：检测 {@code DefaultBrushableLootFiller} 是否可加载。
 *       控制 {@code DefaultBrushableLootFillerMixin} 的加载。
 *       <p>
 *       {@code DefaultBrushableLootFiller} 是 Lootr {@value #MIN_BRUSHABLE_VERSION} 引入的可疑方块 API，
 *       旧版 Lootr 中不存在。若缺失，基础容器 Mixin 仍正常加载，仅跳过可疑方块联动。
 *       </li>
 * </ol>
 *
 * <h3>版本号获取</h3>
 * 尝试从 {@code LootrAPI} 所在 JAR 的 MANIFEST.MF 读取 {@code Implementation-Version}，
 * 若清单不可用则通过特性类检测推断最低版本。
 *
 * <p>
 * 在 common 模块中通过反射与 JAR 清单检测 Lootr 的安装状态与版本，
 * 避免直接引用平台专属 API（如 FabricLoader / ModList）。
 */
public final class LootrVersionChecker {

    // 当前联动开发所基于的 Lootr 版本
    public static final String EXPECTED_VERSION = "1.11.37.122";
    // 支持可疑方块（Brushable）联动的最低版本
    public static final String MIN_BRUSHABLE_VERSION = "1.11.37.121";

    private static volatile boolean detected;
    @Nullable
    private static volatile String detectedVersion;
    private static volatile boolean lootrPresent;
    private static volatile boolean brushableSupported;

    private LootrVersionChecker() {
    }

    /**
     * 执行检测。应在模组初始化阶段调用一次。
     * 幂等：多次调用仅首次执行检测。
     */
    public static void detect() {
        if (detected) {
            return;
        }
        detected = true;

        // 检查 Lootr 是否安装
        try {
            Class.forName("noobanidus.mods.lootr.common.api.LootrAPI");
            lootrPresent = true;
        } catch (ClassNotFoundException e) {
            lootrPresent = false;
            brushableSupported = false;
            return;
        }

        // 尝试读取版本号
        detectedVersion = readVersionFromManifest();

        // 特性检测：DefaultBrushableLootFiller 是否存在
        try {
            Class.forName("noobanidus.mods.lootr.common.api.data.DefaultBrushableLootFiller");
            brushableSupported = true;
        } catch (ClassNotFoundException e) {
            brushableSupported = false;
            Constants.LOG.warn("[UnsuspiciousBlock] Lootr 已安装但版本过低，可疑方块联动功能已禁用。"
                    + " 需要 Lootr >= {}，当前安装版本: {}",
                    MIN_BRUSHABLE_VERSION,
                    detectedVersion != null ? detectedVersion : "未知");
        }

        if (lootrPresent) {
            Constants.LOG.info("[UnsuspiciousBlock] Lootr 联动已启用。"
                    + " Lootr 版本: {}, 可疑方块联动: {}",
                    detectedVersion != null ? detectedVersion : "未知",
                    brushableSupported ? "已启用" : "已禁用（版本过低）");
        }
    }

    /**
     * 返回 Lootr 是否已安装。
     */
    public static boolean isLootrPresent() {
        if (!detected) {
            detect();
        }
        return lootrPresent;
    }

    /**
     * 返回可疑方块（Brushable）联动是否可用。
     */
    public static boolean isBrushableSupported() {
        if (!detected) {
            detect();
        }
        return brushableSupported;
    }

    /**
     * 返回检测到的 Lootr 版本号，若无法获取则返回 null。
     */
    @Nullable
    public static String getDetectedVersion() {
        if (!detected) {
            detect();
        }
        return detectedVersion;
    }

    /**
     * 检查 Lootr 版本是否满足最低要求，不满足时记录警告。
     */
    public static void warnIfVersionTooLow() {
        if (!detected) {
            detect();
        }
        if (!lootrPresent) {
            return;
        }
        if (detectedVersion == null) {
            Constants.LOG.warn("[UnsuspiciousBlock] 无法获取 Lootr 版本号，"
                    + "请确保安装的 Lootr 版本 >= {}", EXPECTED_VERSION);
            return;
        }
        String version = detectedVersion;
        if (compareVersions(version) < 0) {
            Constants.LOG.warn("[UnsuspiciousBlock] 检测到 Lootr 版本 {} 低于联动开发版本 {}，"
                    + "部分功能可能无法正常工作，建议升级 Lootr。",
                    version, EXPECTED_VERSION);
        }
    }

    // 尝试从 LootrAPI 所在 JAR 的 MANIFEST.MF 读取版本号
    @Nullable
    private static String readVersionFromManifest() {
        try {
            Class<?> lootrApiClass = Class.forName("noobanidus.mods.lootr.common.api.LootrAPI");
            // 尝试从 Package 获取 Implementation-Version
            Package pkg = lootrApiClass.getPackage();
            if (pkg != null) {
                String implVersion = pkg.getImplementationVersion();
                if (implVersion != null && !implVersion.isEmpty()) {
                    return implVersion;
                }
            }
            // 尝试从 MANIFEST.MF 读取
            String classPath = lootrApiClass.getProtectionDomain().getCodeSource().getLocation().getPath();
            if (classPath != null && classPath.endsWith(".jar")) {
                URL manifestUrl = URI.create("jar:file:" + classPath + "!/META-INF/MANIFEST.MF").toURL();
                try (InputStream is = manifestUrl.openStream()) {
                    Manifest manifest = new Manifest(is);
                    String version = manifest.getMainAttributes().getValue("Implementation-Version");
                    if (version != null && !version.isEmpty()) {
                        return version;
                    }
                }
            }
        } catch (Exception e) {
            Constants.LOG.debug("[UnsuspiciousBlock] 无法从 MANIFEST.MF 读取 Lootr 版本: {}", e.getMessage());
        }
        return null;
    }

    // 简易语义版本比较，返回负数表示 a < b
    static int compareVersions(String a) {
        String[] partsA = a.split("[.\\-]");
        String[] partsB = LootrVersionChecker.EXPECTED_VERSION.split("[.\\-]");
        int maxLen = Math.max(partsA.length, partsB.length);
        for (int i = 0; i < maxLen; i++) {
            int numA = i < partsA.length ? parseLeadingInt(partsA[i]) : 0;
            int numB = i < partsB.length ? parseLeadingInt(partsB[i]) : 0;
            if (numA != numB) {
                return numA - numB;
            }
        }
        return 0;
    }

    private static int parseLeadingInt(String s) {
        int num = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                break;
            }
            num = num * 10 + (c - '0');
        }
        return num;
    }
}