package com.meteorite.unsuspiciousblock.platform.mixin.loot;

import net.neoforged.neoforge.common.NeoForgeEventHandler;
import net.neoforged.neoforge.common.loot.LootModifierManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** 读取 NeoForge 本轮数据包重载后实际启用的全局战利品修改器。 */
@Mixin(value = NeoForgeEventHandler.class, remap = false)
public interface LootModifierManagerAccess {
    /** 返回真实世界掉落入口使用的修改器容器。 */
    @Invoker("getLootModifierManager")
    static LootModifierManager unsuspiciousblock$getLootModifierManager() {
        throw new AssertionError();
    }
}
