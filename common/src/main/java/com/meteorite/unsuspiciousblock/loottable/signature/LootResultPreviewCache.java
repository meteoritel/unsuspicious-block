package com.meteorite.unsuspiciousblock.loottable.signature;

import net.minecraft.world.item.ItemStack;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * 签名 → 预览栈的进程内缓存。
 * <p>
 * {@link LootResultSignature#createPreviewStack()} 对 {@code COMPONENT_EXACT} 需要 base64 + JSON 解码
 * 再套用组件补丁，成本远高于其它签名类型。玩家侧的掉落实时匹配会对同一批候选反复求值
 * （每次掉落、每个容器槽位），因此这里按签名内容缓存预览栈——预览栈由签名值唯一决定，
 * 缓存结果永远有效，不需要失效策略，只需要在数据包重载时释放以限制内存。
 * <p>
 * 概率模拟热路径不使用本缓存：它在单表模拟期间自带 {@code HashMap} 缓存，单线程且无并发开销，
 * 比这里的并发容器更快。
 * <p>
 * <b>调用方不得修改返回的 ItemStack</b>——它是共享实例，匹配逻辑只做只读比较。
 */
public final class LootResultPreviewCache {
    /** 可直接传给 {@link LootResultMatcher} 的带缓存提供者（单例，避免每次匹配重复构造 lambda）。 */
    public static final Function<LootResultSignature, ItemStack> PROVIDER = LootResultPreviewCache::preview;

    private static final Map<LootResultSignature, ItemStack> CACHE = new ConcurrentHashMap<>();

    private LootResultPreviewCache() {
    }

    /** 取预览栈；首次访问时构建并缓存。 */
    public static ItemStack preview(LootResultSignature signature) {
        return CACHE.computeIfAbsent(signature, LootResultSignature::createPreviewStack);
    }

    /** 清空缓存（数据包重载时调用，避免跨代累积）。 */
    public static void clear() {
        CACHE.clear();
    }

}
