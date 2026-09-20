package com.meteorite.unsuspiciousblock.loottable.signature;

import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.platform.Services;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 签名排除组件策略——把配置里声明的"实例态组件"解析成组件类型集合，供签名派生判断。
 * <p>
 * 这些组件随物品实例随机化或随玩家进度变化（例如 Relics 的 {@code relics:data} 每次掉落都会被
 * 重新随机化），不属于战利品定义。含它们的物品退化为物品级签名，避免同一物品被平铺成上千个伪变体。
 * <p>
 * 解析结果按配置内容缓存：配置值未变化时只做一次列表比较，不重复查注册表。本类**只读判断，
 * 不修改传入的物品栈**——修改会触发第三方模组的组件校验钩子并产生副作用。
 */
public final class SignatureExcludedComponents {
    private static volatile List<String> cachedIds = List.of();
    private static volatile Set<DataComponentType<?>> cachedTypes = Set.of();

    private SignatureExcludedComponents() {
    }

    // 判断补丁中是否含被排除的实例态组件
    public static boolean containsExcluded(DataComponentPatch patch) {
        Set<DataComponentType<?>> types = resolved();
        if (types.isEmpty() || patch.isEmpty()) {
            return false;
        }
        for (Map.Entry<DataComponentType<?>, ?> entry : patch.entrySet()) {
            if (types.contains(entry.getKey())) {
                return true;
            }
        }
        return false;
    }

    // 当前生效的排除组件 id 列表——失效链与诊断需要使用同一口径
    public static List<String> configuredIds() {
        return List.copyOf(Services.LOOT_TABLE_CONFIG.getSignatureExcludedComponents());
    }

    // 解析配置为组件类型集合；配置内容未变化时复用上一次结果
    private static Set<DataComponentType<?>> resolved() {
        List<String> ids = configuredIds();
        if (ids.equals(cachedIds)) {
            return cachedTypes;
        }

        Set<DataComponentType<?>> types = new HashSet<>();
        for (String id : ids) {
            ResourceLocation key = ResourceLocation.tryParse(id);
            DataComponentType<?> type = key == null ? null : BuiltInRegistries.DATA_COMPONENT_TYPE.get(key);
            if (type == null) {
                // 注册表里没有该组件时按"未配置"处理，但必须留下可行动的诊断
                Constants.LOG.warn("Unknown data component type '{}' in signature excluded components; ignored.", id);
                continue;
            }
            types.add(type);
        }

        Set<DataComponentType<?>> resolvedTypes = types.isEmpty() ? Set.of() : Set.copyOf(types);
        cachedTypes = resolvedTypes;
        cachedIds = ids;
        return resolvedTypes;
    }
}
