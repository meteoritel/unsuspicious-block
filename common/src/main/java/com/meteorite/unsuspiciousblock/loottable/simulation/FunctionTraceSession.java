package com.meteorite.unsuspiciousblock.loottable.simulation;

import com.meteorite.unsuspiciousblock.loottable.analysis.LootFunctionDescriptions;
import com.meteorite.unsuspiciousblock.loottable.analysis.LootFunctionInfo;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.functions.LootItemFunction;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 一次模拟任务内的**函数执行捕获会话**——把"这个产物经过了哪些函数"按对象身份旁路关联起来。
 * <p>
 * 边界（规划 §3.3 / §4.5 / §4.7）：
 * <ul>
 *   <li>只做旁路关联：{@link IdentityHashMap} 把物品对象映射到执行链，<b>不写入追踪组件或 NBT</b>，
 *       因此不干扰物品比较、堆叠与其他函数；</li>
 *   <li>对象关系断裂（第三方复制、平台后处理替换）时不做"相同物品 + 相同组件"回退归因，
 *       直接按未建立关联处理；</li>
 *   <li>所有预算都有显式截断标志，达到上限只暂停新增捕获，最终 drops 与概率计数仍完整消费；</li>
 *   <li>记录器自有异常降级为"观测不可用"，绝不向上传播；原函数异常由本类的调用约定
 *       以 {@code catch -> finally 语义 -> 原样抛出} 与前者在代码结构上分开（规划 §4.7）。</li>
 * </ul>
 * 本类不依赖平台类，也不保存任务级聚合数据——聚合留在 job 中，避免依赖上一个时间片的 ThreadLocal。
 */
public final class FunctionTraceSession {
    /** 单条链最多保留的执行节点数。 */
    public static final int MAX_CHAIN_NODES = 64;
    /** 最多允许的函数嵌套层次（执行帧深度）。 */
    public static final int MAX_NESTING_DEPTH = 16;
    /** 单个结果签名最多保留的观测链条数。 */
    public static final int MAX_CHAINS_PER_RESULT = 16;
    /** 本会话最多缓存的唯一函数描述数。 */
    public static final int MAX_DESCRIPTIONS = 4096;
    /** 单个输入最多保留的唯一链条目数（描述预算与链条目预算共同约束，不止限制最终输出）。 */
    public static final int MAX_CHAIN_ENTRIES = 4096;
    /** 单轮最多建立的栈对象关联数。 */
    public static final int MAX_STACK_LINKS = 4096;

    private static final ResourceLocation SET_CONTENTS =
            ResourceLocation.fromNamespaceAndPath("minecraft", "set_contents");
    private static final ResourceLocation MODIFY_CONTENTS =
            ResourceLocation.fromNamespaceAndPath("minecraft", "modify_contents");

    private final IdentityHashMap<ItemStack, TraceHandle> links = new IdentityHashMap<>();
    private final IdentityHashMap<LootItemFunction, LootFunctionInfo> descriptions = new IdentityHashMap<>();
    private final ArrayDeque<Frame> frames = new ArrayDeque<>();

    private ItemStack splitSource;
    private int containerDepth;
    private int suspendDepth;
    private boolean rollTruncated;
    private boolean rollPaused;
    private boolean unavailable;
    private long stackLinkCount;
    private long nodeCount;
    private long truncationCount;

    // 开始一轮抽取：清空对象身份关联与执行帧，按轮重置截断/暂停；会话级预算（描述字典）不回退
    public void startRoll() {
        try {
            this.links.clear();
            this.frames.clear();
            this.splitSource = null;
            this.containerDepth = 0;
            this.suspendDepth = 0;
            this.rollTruncated = false;
            this.rollPaused = false;
        } catch (RuntimeException failure) {
            markUnavailable();
        }
    }

    /**
     * 转交一次条件函数的执行：原调用**只执行一次**，不重掷条件、不重试。
     * <p>
     * 两类异常在结构上分开处理：
     * <ul>
     *   <li>记录器自有异常（进入/退出执行帧）就地降级为"观测不可用"，不影响原执行路径；</li>
     *   <li>原函数异常只用于退出执行帧，随后**原样传播**给既有模拟失败处理，不吞掉、不重试。</li>
     * </ul>
     */
    public ItemStack traceRun(LootItemFunction function, ItemStack input, Supplier<ItemStack> original) {
        Frame frame;
        try {
            frame = openFrame(function, input);
        } catch (RuntimeException failure) {
            markUnavailable();
            return original.get();
        }
        if (frame == null) {
            return original.get();
        }
        ItemStack output;
        try {
            output = original.get();
        } catch (RuntimeException | Error failure) {
            closeFrame(frame, null);
            throw failure;
        }
        closeFrame(frame, output);
        return output;
    }

    // 读取某个产物对象的观测链；未建立对象关联时返回 null，调用方必须按"未完整"处理
    @Nullable
    public ObservedFunctionChain observe(ItemStack stack) {
        try {
            return observeInternal(stack);
        } catch (RuntimeException failure) {
            markUnavailable();
            return null;
        }
    }

    // 包装拆栈器的输出 Consumer：拆栈副本按对象身份继承来源链，未拆栈路径原对象已有链，不重复写入
    Consumer<ItemStack> wrapSplitOutput(Consumer<ItemStack> output) {
        return stack -> {
            try {
                inheritSplitTrace(stack);
            } catch (RuntimeException failure) {
                markUnavailable();
            }
            output.accept(stack);
        };
    }

    // 包装拆栈器本身：记录本次拆栈的输入栈，供输出副本继承来源；算法仍由原版执行
    Consumer<ItemStack> wrapSplitInput(Consumer<ItemStack> splitter) {
        return stack -> {
            ItemStack previous = this.splitSource;
            this.splitSource = stack;
            try {
                splitter.accept(stack);
            } finally {
                this.splitSource = previous;
            }
        };
    }

    // 本会话累计建立的栈对象关联数，读取后清零（供任务侧汇总指标）
    public long drainStackLinks() {
        long value = this.stackLinkCount;
        this.stackLinkCount = 0L;
        return value;
    }

    // 本会话累计创建的执行节点数，读取后清零
    public long drainNodes() {
        long value = this.nodeCount;
        this.nodeCount = 0L;
        return value;
    }

    // 本会话累计触发的预算截断次数，读取后清零
    public long drainTruncations() {
        long value = this.truncationCount;
        this.truncationCount = 0L;
        return value;
    }

    // 本轮是否触发了捕获预算上限（只暂停本轮新增捕获，不影响掉落与概率计数）
    public boolean isRollTruncated() {
        return this.rollTruncated;
    }

    // 记录器是否已降级为"观测不可用"
    public boolean isUnavailable() {
        return this.unavailable;
    }

    // ---------------------------------------------------------------- 内部实现

    // 建立执行帧；返回 null 表示本轮不再新增捕获（容器隔离、预算耗尽或已降级）
    @Nullable
    private Frame openFrame(LootItemFunction function, ItemStack input) {
        if (this.unavailable || this.rollPaused) {
            return null;
        }
        // 容器内容隔离：容器函数执行期间不采集子内容，避免把子内容函数串到外层容器上
        if (this.containerDepth > 0) {
            return null;
        }
        // 描述生成期间挂起捕获：handler 若在描述时做静态预览执行，不得被记成本轮真实执行
        if (this.suspendDepth > 0) {
            return null;
        }
        if (this.frames.size() >= MAX_NESTING_DEPTH) {
            markTruncated();
            return null;
        }
        boolean container = isContainerFunction(function);
        LootFunctionInfo info = describe(function);
        if (info == null) {
            return null;
        }
        TraceHandle prefix;
        if (this.frames.isEmpty()) {
            prefix = input == null || input.isEmpty() ? null : this.links.get(input);
        } else {
            // 内层执行帧的前缀包含外层节点与已完成的内层节点，包装层次不会被平铺重复
            prefix = this.frames.peek().preview();
        }
        TraceNode node = new TraceNode(info.functionType(), info, List.of(), !container);
        Frame frame = new Frame(node, prefix, container);
        this.frames.push(frame);
        if (container) {
            this.containerDepth++;
        }
        this.nodeCount++;
        return frame;
    }

    // 退出执行帧并关联输出产物；记录器自身失败只降级，不影响原执行路径
    private void closeFrame(Frame frame, @Nullable ItemStack output) {
        try {
            if (!this.frames.isEmpty() && this.frames.peek() == frame) {
                this.frames.pop();
            } else {
                // 帧不同步说明出现内部错误：放弃本轮后续归因，避免错误串联
                this.frames.clear();
                markUnavailable();
                return;
            }
            if (frame.container) {
                this.containerDepth = Math.max(0, this.containerDepth - 1);
            }
            TraceNode node = frame.nodeWithChildren();
            // 子节点预算在**追加时**消耗：超限只停止挂树并记录截断，
            // 不把无上限的子节点带进后续展平（原函数执行与 drops 统计不受影响）
            if (!this.frames.isEmpty() && !this.frames.peek().addChild(node)) {
                markTruncated();
            }
            // 空栈不是最终产物，也不允许共享 EMPTY 建立公共关联
            if (output == null || output.isEmpty() || this.rollPaused) {
                return;
            }
            if (this.links.size() >= MAX_STACK_LINKS) {
                markTruncated();
                this.rollPaused = true;
                return;
            }
            this.links.put(output, TraceHandle.of(frame.prefix, node));
            this.stackLinkCount++;
        } catch (RuntimeException failure) {
            markUnavailable();
        }
    }

    // 拆栈副本继承来源链：已有链的栈不覆盖，来源未知时不猜测归因
    private void inheritSplitTrace(ItemStack stack) {
        if (stack == null || stack.isEmpty() || this.unavailable || this.rollPaused) {
            return;
        }
        if (this.links.containsKey(stack)) {
            return;
        }
        ItemStack source = this.splitSource;
        if (source == null || source.isEmpty()) {
            return;
        }
        TraceHandle handle = this.links.get(source);
        if (handle == null) {
            return;
        }
        if (this.links.size() >= MAX_STACK_LINKS) {
            markTruncated();
            this.rollPaused = true;
            return;
        }
        this.links.put(stack, handle);
        this.stackLinkCount++;
    }

    @Nullable
    private ObservedFunctionChain observeInternal(ItemStack stack) {
        if (this.unavailable || this.rollPaused || stack == null || stack.isEmpty()) {
            return null;
        }
        TraceHandle handle = this.links.get(stack);
        if (handle == null) {
            return null;
        }
        List<ResourceLocation> types = new ArrayList<>(handle.chain().size());
        boolean contentExpanded = true;
        for (TraceNode node : handle.chain()) {
            types.add(node.functionType());
            if (!node.contentExpanded()) {
                contentExpanded = false;
            }
        }
        ObservedFunctionChain.State state = handle.truncated()
                ? ObservedFunctionChain.State.PARTIAL
                : ObservedFunctionChain.State.COMPLETE;
        return new ObservedFunctionChain(types, state, contentExpanded);
    }

    // 按唯一函数对象实例缓存描述：Codec 编码与本地化拼装不进入 Mixin 热路径
    @Nullable
    private LootFunctionInfo describe(LootItemFunction function) {
        LootFunctionInfo cached = this.descriptions.get(function);
        if (cached != null) {
            return cached;
        }
        if (this.descriptions.size() >= MAX_DESCRIPTIONS) {
            markTruncated();
            return null;
        }
        // 描述生成期间挂起捕获：既避免预览执行被记成真实执行，也避免 handler 重入导致递归
        this.suspendDepth++;
        LootFunctionInfo info;
        try {
            ResourceLocation functionId = BuiltInRegistries.LOOT_FUNCTION_TYPE.getKey(function.getType());
            info = LootFunctionDescriptions.describe(functionId, function, null);
        } finally {
            this.suspendDepth = Math.max(0, this.suspendDepth - 1);
        }
        this.descriptions.put(function, info);
        return info;
    }

    // 容器类函数用注册表 key 判断，不依赖具体实现类；只隔离内容采集，容器节点本身仍记录
    private static boolean isContainerFunction(LootItemFunction function) {
        ResourceLocation functionId = BuiltInRegistries.LOOT_FUNCTION_TYPE.getKey(function.getType());
        return SET_CONTENTS.equals(functionId) || MODIFY_CONTENTS.equals(functionId);
    }

    // 记录一次显式截断：只暂停本轮新增捕获，概率与 drops 统计不受影响
    private void markTruncated() {
        this.rollTruncated = true;
        this.truncationCount++;
    }

    // 记录器自身异常：降级为观测不可用，不再继续捕获
    private void markUnavailable() {
        this.unavailable = true;
        this.rollPaused = true;
        this.frames.clear();
        this.containerDepth = 0;
    }

    /** 一次函数执行的执行帧；节点在退出时才定型（内层节点先完成）。 */
    private static final class Frame {
        private final TraceNode node;
        private final TraceHandle prefix;
        private final boolean container;
        private final List<TraceNode> children = new ArrayList<>();

        private Frame(TraceNode node, TraceHandle prefix, boolean container) {
            this.node = node;
            this.prefix = prefix;
            this.container = container;
        }

        // 内层执行帧的前缀：本帧前缀 + 本节点 + 已完成的内层节点
        private TraceHandle preview() {
            return TraceHandle.of(this.prefix, nodeWithChildren());
        }

        // 定型本帧节点（带上已完成的内层节点）
        private TraceNode nodeWithChildren() {
            return this.node.withChildren(this.children);
        }

        // 追加一个已完成的内层执行节点；达到单链节点预算后拒绝新增（调用方据此记录截断），
        // 超出的内层节点不可能再进入任何展平结果，因此不再挂到树上
        private boolean addChild(TraceNode child) {
            if (this.children.size() >= FunctionTraceSession.MAX_CHAIN_NODES) {
                return false;
            }
            this.children.add(child);
            return true;
        }
    }
}
