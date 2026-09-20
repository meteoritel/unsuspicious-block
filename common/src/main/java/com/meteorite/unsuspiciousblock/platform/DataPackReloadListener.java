package com.meteorite.unsuspiciousblock.platform;

import com.meteorite.unsuspiciousblock.Constants;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

/**
 * 数据包重载监听器——修复 D9：改了战利品表 JSON 并执行 {@code /reload} 后，
 * 资源快照与表哈希必须重算，否则静态分析按旧 JSON、运行时抽取按新 JSON，两者不一致。
 * <p>
 * 职责刻意只有一件事：**置脏标记**。真正的重建由
 * {@link ServerLootTableConfigManager#tick} 在服务端 tick 路径上消费（见
 * {@link ServerLootTableConfigManager#markDataPackReload()}）。
 * 两条理由：
 * <ul>
 *   <li>在重载回调里同步跑全量构建（资源快照 + 引用图 + 编译 + 投影）会拖住重载本身；</li>
 *   <li>Fabric 与 NeoForge 的重载事件时序不同，只置标记能把平台差异压到最小。</li>
 * </ul>
 * 服务端启动时的首次资源加载也会触发本监听器，但那时目录尚未加载，标记会被忽略——
 * 启动路径自己负责首次构建。
 */
public final class DataPackReloadListener extends SimplePreparableReloadListener<Void> {

    /** 两个平台共用同一个监听器实例；它无状态，只调用静态入口。 */
    public static final DataPackReloadListener INSTANCE = new DataPackReloadListener();

    private DataPackReloadListener() {
    }

    @Override
    protected Void prepare(ResourceManager resourceManager, ProfilerFiller profiler) {
        return null;
    }

    @Override
    protected void apply(Void unused, ResourceManager resourceManager, ProfilerFiller profiler) {
        ServerLootTableConfigManager.markDataPackReload();
        Constants.LOG.debug("Detected a data pack reload; archaeology catalog rebuild is queued.");
    }
}
