package com.meteorite.unsuspiciousblock.loottable.simulation;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 针对**一个结果签名**的运行时函数观测摘要（规划 §4.5.4 / §4.7）。
 * <p>
 * 它是"本次模拟观测到的函数"，与静态规则侧的函数描述树<br>
 * 分属两条轴：有链不代表平台后处理已被解释，无链也不代表没有函数执行过。
 * 因此本摘要默认不发布"完整生成过程已捕获"的结论：
 * {@code incomplete} 只要有一件产物没能建立对象身份归因就为真。
 *
 * @param chains      去重后的观测链（每结果有上限，超出时置 {@code truncated}）
 * @param truncated   本结果相关轮次触发了捕获预算上限，观测详情被裁剪
 * @param incomplete  至少有一件产物未能建立函数链归因（对象关系断裂，或链本身被截断）
 * @param unavailable 记录器自有异常导致本结果的观测不可用
 */
public record FunctionObservationSummary(List<ObservedFunctionChain> chains,
                                         boolean truncated,
                                         boolean incomplete,
                                         boolean unavailable) {

    public FunctionObservationSummary {
        chains = chains == null ? List.of() : List.copyOf(chains);
    }

    // 没有任何观测的空摘要：用于未参与捕获的输入
    public static FunctionObservationSummary empty() {
        return new FunctionObservationSummary(List.of(), false, false, false);
    }

    /** 单个结果签名的链聚合器；只做去重与上限裁剪，不参与概率计数。 */
    public static final class Accumulator {
        private final Set<ObservedFunctionChain> chains = new LinkedHashSet<>();
        private boolean truncated;
        private boolean incomplete;
        private boolean unavailable;

        // 追加一条观测链；返回 true 表示这是一条新链
        public boolean add(ObservedFunctionChain chain) {
            if (chain == null || chain.isEmpty()) {
                return false;
            }
            if (this.chains.size() >= FunctionTraceSession.MAX_CHAINS_PER_RESULT) {
                this.truncated = true;
                this.incomplete = true;
                return false;
            }
            if (!this.chains.add(chain)) {
                return false;
            }
            if (chain.state() == ObservedFunctionChain.State.PARTIAL) {
                this.incomplete = true;
            }
            return true;
        }

        // 产物存在但没能建立对象身份归因：必须标为未完整，不做相似栈回退
        public void markUnlinked() {
            this.incomplete = true;
        }

        // 本结果相关轮次触发了预算上限
        public void markTruncated() {
            this.truncated = true;
            this.incomplete = true;
        }

        // 记录器自有异常：降级为观测不可用
        public void markUnavailable() {
            this.unavailable = true;
            this.incomplete = true;
        }

        // 输出不可变摘要
        public FunctionObservationSummary toSummary() {
            return new FunctionObservationSummary(List.copyOf(this.chains),
                    this.truncated, this.incomplete, this.unavailable);
        }
    }
}
