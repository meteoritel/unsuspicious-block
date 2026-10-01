package com.meteorite.unsuspiciousblock.mixin.loottable;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.meteorite.unsuspiciousblock.loottable.simulation.FunctionTraceSession;
import com.meteorite.unsuspiciousblock.loottable.simulation.LootSimulationScope;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.functions.LootItemConditionalFunction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 把"外层条件通过并进入函数执行体"这一时机转交给 {@link FunctionTraceSession}。
 * <p>
 * 注入点已按原版字节码核对：{@code apply(ItemStack, LootContext)} 是 public final，
 * 条件通过时执行 {@code invokevirtual run:(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/level/storage/loot/LootContext;)Lnet/minecraft/world/item/ItemStack;}
 * （vanilla 与 neoforge merged jar 字节码一致）。因此本 Mixin 只包装该次 {@code run} 调用：
 * <ul>
 *   <li>语义严格为"条件通过并进入执行体"，不宣称物品已发生变化；</li>
 *   <li><b>不重新执行</b> {@code conditions.test}，原调用只发生一次，不额外消费随机数；</li>
 *   <li>作用域外（无活动捕获会话）直接透传，不创建任何追踪对象；</li>
 *   <li>业务流程全部在 {@link FunctionTraceSession}，本类只转交参数与返回值。</li>
 * </ul>
 * {@code method} 必须写完整描述符：该类同时存在编译器生成的桥接方法
 * {@code apply(Object, Object)}，只写方法名会同时命中桥接方法。
 */
@Mixin(LootItemConditionalFunction.class)
public abstract class LootItemConditionalFunctionMixin {

    // 包装 run 调用：记录器异常降级为"观测不可用"，原函数异常继续按既有模拟失败规则传播
    @WrapOperation(
            method = "apply(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/level/storage/loot/LootContext;)Lnet/minecraft/world/item/ItemStack;",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/storage/loot/functions/LootItemConditionalFunction;run(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/level/storage/loot/LootContext;)Lnet/minecraft/world/item/ItemStack;"))
    private static ItemStack unsuspiciousblock$captureConditionalRun(
            LootItemConditionalFunction function, ItemStack stack, LootContext context,
            Operation<ItemStack> original) {
        FunctionTraceSession session = LootSimulationScope.activeSession();
        // 作用域外零捕获：直接透传原调用
        if (session == null) {
            return original.call(function, stack, context);
        }
        return LootSimulationScope.enterFunction(session, function, stack,
                () -> original.call(function, stack, context));
    }
}
