package com.meteorite.unsuspiciousblock.loottable.analysis;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.DynamicOps;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.functions.LootItemFunction;
import org.jetbrains.annotations.Nullable;

import java.util.function.Supplier;

/**
 * 函数结构化描述的**统一入口**——把"取 handler → 走描述通道 → 兼容回退"这三步收敛到一处。
 * <p>
 * 之所以单独成类：投影器（静态规则）与运行时捕获记录器（执行事实）都需要给同一个
 * {@link LootItemFunction} 生成描述，两处各写一份必然在"未知函数怎么降级"上分叉。
 * <p>
 * 本类只做转交与降级，不含任何具体函数的语义——具体语义在 {@link LootFunctionHandlers}。
 */
public final class LootFunctionDescriptions {

    /**
     * 描述期的**条件解析上下文**。
     * <p>
     * 函数自身的条件要用 {@code LootParseUtil.parseConditions} 解析，而它需要 {@code RegistryOps}；
     * 但 {@link LootFunctionHandler#describe} 的签名里没有 ops，包装函数的内层条件因此拿不到注册表视图。
     * 投影层在遍历函数链前用 {@link #withConditionOps} 安装它，analysis 层在解析内层条件时取用。
     * 运行时捕获路径**不**安装，内层条件保持为空——运行时对象本来就没有对应的静态 JSON。
     */
    private static final ThreadLocal<DynamicOps<JsonElement>> CONDITION_OPS = new ThreadLocal<>();
    private static final ThreadLocal<LootFunctionPreviewContext> PREVIEW_CONTEXT = new ThreadLocal<>();
    private static final ThreadLocal<Integer> PREVIEW_DEPTH = ThreadLocal.withInitial(() -> 0);
    private static final ThreadLocal<Integer> PREVIEW_NODES = ThreadLocal.withInitial(() -> 0);

    private LootFunctionDescriptions() {
    }

    // 上下文仅在静态目录构建期间安装，退出时恢复，避免影响运行时掉落与捕获。
    public static void withPreviewContext(@Nullable LootFunctionPreviewContext context, Runnable action) {
        LootFunctionPreviewContext previous = PREVIEW_CONTEXT.get();
        PREVIEW_CONTEXT.set(context);
        try {
            action.run();
        } finally {
            if (previous == null) PREVIEW_CONTEXT.remove();
            else PREVIEW_CONTEXT.set(previous);
        }
    }

    @Nullable
    public static LootFunctionPreviewContext previewContext() {
        return PREVIEW_CONTEXT.get();
    }

    // 包装函数的预览有界递归；数量不确定不会改变组件身份，其他失败则保守返回空。
    @Nullable
    static ItemStack preview(ItemStack stack, LootItemFunction function) {
        DynamicOps<JsonElement> ops = conditionOps();
        int depth = PREVIEW_DEPTH.get();
        if (ops == null || depth >= 8) return null;
        if (depth == 0) PREVIEW_NODES.set(128);
        int remaining = PREVIEW_NODES.get();
        if (remaining <= 0) return null;
        PREVIEW_NODES.set(remaining - 1);
        PREVIEW_DEPTH.set(depth + 1);
        try {
            JsonElement source = net.minecraft.world.level.storage.loot.functions.LootItemFunctions.ROOT_CODEC
                    .encodeStart(ops, function).result().orElse(null);
            JsonObject object = normalizeSource(source);
            if (object == null || object.has("conditions") && !object.getAsJsonArray("conditions").isEmpty()) {
                return null;
            }
            ResourceLocation id = LootFunctionHandlers.keyOf(function);
            LootFunctionHandler handler = id != null ? LootFunctionHandlers.get(id) : null;
            if (handler == null) return null;
            LootFunctionInfo info = describe(id, function, object);
            if (!info.isResolved()) return null;
            if (handler.addsRandomness() && info.effect() != FunctionEffectKind.COUNT) return null;
            ItemStack result = handler.apply(stack.copy(), function);
            return result != null ? result : (info.effect() == FunctionEffectKind.COUNT ? stack.copy() : null);
        } finally {
            PREVIEW_DEPTH.set(depth);
            if (depth == 0) PREVIEW_NODES.remove();
        }
    }

    // ROOT_CODEC 的数组是内联 sequence；只新建包装对象，不修改原始 JSON。
    @Nullable
    public static JsonObject normalizeSource(@Nullable JsonElement element) {
        if (element == null) {
            return null;
        }
        if (element.isJsonObject()) {
            return element.getAsJsonObject();
        }
        if (element.isJsonArray()) {
            JsonObject sequence = new JsonObject();
            sequence.addProperty("function", "minecraft:sequence");
            sequence.add("functions", element.getAsJsonArray());
            return sequence;
        }
        return null;
    }

    // 在给定 ops 下执行一段描述工作；退出时恢复外层上下文，异常路径也不泄漏
    public static <T> T withConditionOps(DynamicOps<JsonElement> ops, Supplier<T> action) {
        DynamicOps<JsonElement> previous = CONDITION_OPS.get();
        CONDITION_OPS.set(ops);
        try {
            return action.get();
        } finally {
            if (previous == null) {
                CONDITION_OPS.remove();
            } else {
                CONDITION_OPS.set(previous);
            }
        }
    }

    /** 当前描述上下文的条件解析 ops；{@code null} 表示不在静态描述期（例如运行时捕获）。 */
    @Nullable
    public static DynamicOps<JsonElement> conditionOps() {
        return CONDITION_OPS.get();
    }

    /**
     * 为已解析的 function 生成结构化描述。
     *
     * @param functionId 注册表 key；为 null 表示连类型都取不到
     * @param function   类型化 function 对象
     * @param source     原始 JSON；可能为 null（运行时对象没有对应 JSON）
     * @return 永远非 null 的描述。未知 handler 或未迁移 handler 会降级为
     *         {@link FunctionFidelity#UNRESOLVED} / {@link FunctionFidelity#PARTIAL}
     */
    public static LootFunctionInfo describe(@Nullable ResourceLocation functionId,
                                            LootItemFunction function,
                                            @Nullable JsonObject source) {
        if (functionId == null) {
            return unresolved(null, function);
        }
        LootFunctionHandler handler = LootFunctionHandlers.get(functionId);
        if (handler == null) {
            return unresolved(functionId, function);
        }
        LootFunctionInfo described = null;
        if (source != null) {
            try {
                described = handler.describe(function, source);
            } catch (RuntimeException ignored) {
                // 描述失败必须降级为"未解析"，不得影响掉落与统计
                described = null;
            }
        }
        if (described != null) {
            return described;
        }
        return partialFromHint(functionId, function, source, handler);
    }

    // 未迁移到描述通道的 handler：把兼容摘要包成部分解析，保证"只有一个描述来源"
    private static LootFunctionInfo partialFromHint(ResourceLocation functionId, LootItemFunction function,
                                                    @Nullable JsonObject source, LootFunctionHandler handler) {
        Component hint = null;
        try {
            hint = source != null ? handler.describeHint(function, source) : handler.describeHint(function);
        } catch (RuntimeException ignored) {
            hint = null;
        }
        if (hint == null) {
            return unresolved(functionId, function);
        }
        return new LootFunctionInfo(functionId, hint, FunctionFidelity.PARTIAL,
                FunctionEffectKind.UNKNOWN);
    }

    /** 未解析函数的统一描述：保留可取得的注册名，明确标记未解析。 */
    public static LootFunctionInfo unresolved(@Nullable ResourceLocation functionId,
                                              @Nullable LootItemFunction function) {
        Component description = Component.translatable(
                "screen.unsuspiciousblock.archaeology_journal.function.unresolved",
                functionId != null ? functionId.toString() : "?");
        ResourceLocation type = functionId != null
                ? functionId
                : ResourceLocation.fromNamespaceAndPath("unknown", "unresolved_function");
        return new LootFunctionInfo(type, description, FunctionFidelity.UNRESOLVED,
                FunctionEffectKind.UNKNOWN);
    }
}
