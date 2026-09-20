package com.meteorite.unsuspiciousblock.client.state;

import com.meteorite.unsuspiciousblock.loottable.catalog.CatalogTableDto;
import com.meteorite.unsuspiciousblock.network.payload.s2c.ScenarioRequestRejectedPayload;
import com.meteorite.unsuspiciousblock.network.payload.s2c.SyncScenarioResultPayload;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 按需模拟结果的客户端缓存。
 * <p>
 * 键是 {@code (表, 输入键)}：同一个输入可能被多个玩家分别请求，但按内容去重后服务端只算一次，
 * 因此客户端这边也只按内容存。**代次与表哈希都要校验**（决策 36）——切参数或 {@code /reload}
 * 之后旧结果可能后到，不校验就会把上一代的数据画到当前界面上。
 * <p>
 * 容量有界且按插入顺序淘汰最旧项，避免玩家在一张表上反复调参把内存顶起来；
 * 被淘汰只是"要重算一次"，不影响正确性。
 */
public final class ScenarioSimulationClientState {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** 保留的结果条数上限——纯 UI 用途，超出按最旧淘汰。 */
    private static final int MAX_RESULTS = 64;

    private static final Map<String, CatalogTableDto> RESULTS =
            new LinkedHashMap<>(16, 0.75F, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, CatalogTableDto> eldest) {
                    return size() > MAX_RESULTS;
                }
            };

    private ScenarioSimulationClientState() {
    }

    /** 结果键的构造只有一份实现：客户端与服务端的 inputKey 是同一个字符串。 */
    public static String keyOf(ResourceLocation tableId, String inputKey) {
        return tableId + "#" + inputKey;
    }

    /** 接收按需模拟结果。 */
    public static void receive(SyncScenarioResultPayload payload) {
        RESULTS.put(keyOf(payload.table().id(), payload.inputKey()), payload.table());
    }

    /**
     * 接收拒绝回执。
     * <p>
     * 目前只写日志：回执的**用途**（界面上解释"为什么点了没反应"）属 P2 的参数区与快捷切换下拉。
     * 这里刻意不先建一份无人读取的状态——那会让"已受理"与"被拒绝"在下一阶段更难分辨。
     */
    public static void receiveRejection(ScenarioRequestRejectedPayload payload) {
        LOGGER.debug("按需模拟请求被拒绝：table={}, input={}, reason={}",
                payload.tableId(), payload.inputKey(), payload.reason());
    }

    /** 取某个输入下的结果；未收到时返回 {@code null}（界面上表现为"尚未计算"）。 */
    @Nullable
    public static CatalogTableDto result(ResourceLocation tableId, String inputKey) {
        return RESULTS.get(keyOf(tableId, inputKey));
    }

    /** 断开连接或数据包重载时清空——结果绑定在某一代目录上，跨代复用会画错数字。 */
    public static void clear() {
        RESULTS.clear();
    }
}
