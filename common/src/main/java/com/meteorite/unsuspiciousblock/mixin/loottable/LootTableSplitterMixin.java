package com.meteorite.unsuspiciousblock.mixin.loottable;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.meteorite.unsuspiciousblock.loottable.simulation.FunctionTraceSession;
import com.meteorite.unsuspiciousblock.loottable.simulation.LootSimulationScope;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootTable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.function.Consumer;

/**
 * 让局部拆栈产生的副本继承来源观测链。
 * <p>
 * <b>为什么不用"包装 lambda 内 copyWithCount 调用"的窄方案</b>（实施时已按字节码核对）：
 * <ol>
 *   <li>该调用位于编译器生成的合成方法里。vanilla / neoforge merged jar 中为
 *       {@code private static lambda$createStackSplitter$5(ServerLevel, Consumer, ItemStack)}，
 *       {@code copyWithCount} 在偏移 50；但 loom named merged jar 中同一合成方法是
 *       {@code private static method_331(ServerLevel, Consumer, ItemStack)}，
 *       {@code lambda$createStackSplitter$5} 这个名字在 Fabric 命名空间里根本不存在，
 *       必须依赖 refmap 对合成 lambda 名做重映射，而 lambda 序号不是稳定 API；</li>
 *   <li>两个平台的 {@code createStackSplitter} 调用点还不一致：vanilla 在
 *       {@code public getRandomItems(LootContext, Consumer)} 内，neoforge 在
 *       {@code private getRandomItems(LootContext)} 内（返回 ObjectArrayList 的私有重载）。</li>
 * </ol>
 * 因此按任务书允许的替代方案实施：<b>保留原 splitter 与其拆栈算法</b>，只在
 * {@code createStackSplitter} 这个两平台一致的非 lambda 静态方法上包装它的输入/输出 Consumer。
 * 两种方案只选一种，不叠加；也不对 {@code ItemStack.copy/copyWithCount} 做全局注入。
 * <p>
 * 拆栈算法、随机序列与产物数量完全由原版实现决定，本类只转交 Consumer。
 */
@Mixin(LootTable.class)
public abstract class LootTableSplitterMixin {

    // 包装拆栈器输出 Consumer：拆栈副本按对象身份继承来源链
    @ModifyVariable(method = "createStackSplitter", at = @At("HEAD"), argsOnly = true, index = 1)
    private static Consumer<ItemStack> unsuspiciousblock$wrapSplitOutput(Consumer<ItemStack> output) {
        FunctionTraceSession session = LootSimulationScope.activeSession();
        // 作用域外原样返回，不创建包装对象
        return session == null ? output : LootSimulationScope.wrapSplitOutput(session, output);
    }

    // 包装拆栈器本身：记录本次拆栈的输入栈，供输出副本继承；算法仍由原版执行
    @ModifyReturnValue(method = "createStackSplitter", at = @At("RETURN"))
    private static Consumer<ItemStack> unsuspiciousblock$wrapSplitInput(Consumer<ItemStack> splitter) {
        FunctionTraceSession session = LootSimulationScope.activeSession();
        // 作用域外原样返回，不创建包装对象
        return session == null ? splitter : LootSimulationScope.wrapSplitInput(session, splitter);
    }
}
