package com.meteorite.unsuspiciousblock.core.loottable.signature;

import com.meteorite.unsuspiciousblock.Constants;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 当前目录使用的实例态组件策略。命中排除项的变体采用物品级签名。 */
public final class SignatureExcludedComponents {
    public static final SignatureExcludedComponents EMPTY = new SignatureExcludedComponents(Set.of());

    private final Set<DataComponentType<?>> types;

    private SignatureExcludedComponents(Set<DataComponentType<?>> types) {
        this.types = Set.copyOf(types);
    }

    /** 按注册表解析配置；保留未知组件 ID 的诊断。 */
    public static SignatureExcludedComponents resolve(List<String> ids) {
        Set<DataComponentType<?>> types = new HashSet<>();
        for (String id : ids) {
            DataComponentType<?> type = BuiltInRegistries.DATA_COMPONENT_TYPE.get(ResourceLocation.parse(id));
            if (type == null) {
                Constants.LOG.warn("{}Unknown data component '{}' in signature exclusions", Constants.LOG_TAG, id);
            } else {
                types.add(type);
            }
        }
        return new SignatureExcludedComponents(types);
    }

    /** 检查补丁中的组件身份，包含显式移除的组件。 */
    public boolean containsExcluded(DataComponentPatch patch) {
        return patch.entrySet().stream().anyMatch(entry -> this.types.contains(entry.getKey()));
    }
}
