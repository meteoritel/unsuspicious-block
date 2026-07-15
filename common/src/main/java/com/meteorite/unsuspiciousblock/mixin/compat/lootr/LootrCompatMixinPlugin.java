package com.meteorite.unsuspiciousblock.mixin.compat.lootr;

import com.meteorite.unsuspiciousblock.compat.LootrVersionChecker;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Lootr 兼容 Mixin 条件加载插件。
 * <p>
 * 检测策略委托给 {@link LootrVersionChecker}，按两级门控：
 * <ol>
 *   <li><b>基础 Lootr</b>：检测 {@code LootrAPI} 是否存在。
 *       若不存在，所有 Lootr Mixin 均不加载。</li>
 *   <li><b>可疑方块联动</b>：检测 {@code DefaultBrushableLootFiller} 是否存在。
 *       该类是 Lootr 1.11.37.121 引入的可疑方块 API，旧版不存在。
 *       若不存在，仅跳过 {@code DefaultBrushableLootFillerMixin}，
 *       基础容器 Mixin（{@code DefaultLootFillerMixin}、{@code LootrInventoryMixin}）仍正常加载。</li>
 * </ol>
 * <p>
 * 注意：{@code DefaultLootFiller} 与 {@code LootrInventory} 是 Lootr 核心类，
 * 自 Lootr 首个版本就存在，无需单独检测——{@code LootrAPI} 存在即保证它们存在。
 */
public class LootrCompatMixinPlugin implements IMixinConfigPlugin {

    @Override
    public void onLoad(String mixinPackage) {
        // 委托给 LootrVersionChecker 统一检测，避免逻辑重复
        LootrVersionChecker.detect();
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (!LootrVersionChecker.isLootrPresent()) {
            return false;
        }
        // DefaultBrushableLootFillerMixin 仅在 Lootr 支持可疑方块联动时加载
        if (mixinClassName.contains("DefaultBrushableLootFillerMixin")) {
            return LootrVersionChecker.isBrushableSupported();
        }
        return true;
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
        // 无操作
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
        // 无操作
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
        // 无操作
    }
}
