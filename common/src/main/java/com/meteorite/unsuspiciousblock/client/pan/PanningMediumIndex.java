package com.meteorite.unsuspiciousblock.client.pan;

import com.meteorite.unsuspiciousblock.entity.ShimmerEntity;
import com.meteorite.unsuspiciousblock.pan.variant.PanningMedium;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/***
 * 淘洗介质索引——玩家 UUID 到该玩家正在淘洗的介质，供物品属性 {@code panning_medium} 选用摇洗帧组。
 * <p>
 * 变体本身不需要同步，但「谁在淘洗哪个介质」只有服务端知道：服务端把淘洗者集合同步到淘洗点实体上，
 * 客户端据此就可以为任意玩家（含远端第三人称视角）选出正确的帧组。
 * <p>
 * 索引按帧重建而不是增量维护：世界渲染本来就要收集附近的淘洗点，重建的成本只是清空一个哈希表
 * 加几次写入，也就不需要任何淘汰与失效逻辑。代价是只有进入收集范围的淘洗点参与，
 * 视野外过远的玩家会回落到水域帧组。
 */
public final class PanningMediumIndex {
    private static final Map<UUID, PanningMedium> MEDIUM_BY_PLAYER = new HashMap<>();

    private PanningMediumIndex() {
    }

    // 按本帧收集到的淘洗点重建索引；集合为空时索引也随之清空
    public static void refresh(Collection<ShimmerEntity> shimmers) {
        MEDIUM_BY_PLAYER.clear();
        for (ShimmerEntity shimmer : shimmers) {
            PanningMedium medium = shimmer.getVariant().glow().medium();
            for (UUID player : parsePanners(shimmer.getPannersSnapshot())) {
                MEDIUM_BY_PLAYER.put(player, medium);
            }
        }
    }

    // 该玩家正在淘洗的介质；未在淘洗时为 null
    @Nullable
    public static PanningMedium mediumOf(UUID playerId) {
        return MEDIUM_BY_PLAYER.get(playerId);
    }

    // 服务端同步的是逗号分隔的 UUID 列表；单条不可解析时跳过，不影响同一快照里的其它玩家
    private static List<UUID> parsePanners(String snapshot) {
        if (snapshot.isEmpty()) {
            return List.of();
        }
        List<UUID> players = new ArrayList<>();
        for (String part : snapshot.split(",")) {
            try {
                players.add(UUID.fromString(part));
            } catch (IllegalArgumentException ignored) {
                // 容忍格式错误的单条记录
            }
        }
        return players;
    }
}
