package com.meteorite.unsuspiciousblock.cat;

import com.meteorite.unsuspiciousblock.entity.SpiritCat;
import com.meteorite.unsuspiciousblock.entity.ai.spiritcat.SpiritCatRole;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 调试灵体运行时绑定表，保证每名玩家每种职业最多绑定一个实体。
 */
public final class SpiritCatDebugRegistry {
    private static final Map<UUID, EnumMap<SpiritCatRole, UUID>> BINDINGS = new HashMap<>();

    private SpiritCatDebugRegistry() {
    }

    public static void bind(UUID playerUuid, SpiritCatRole role, UUID entityUuid) {
        BINDINGS.computeIfAbsent(playerUuid, ignored -> new EnumMap<>(SpiritCatRole.class))
                .put(role, entityUuid);
    }

    @Nullable
    public static SpiritCat find(MinecraftServer server, UUID playerUuid, SpiritCatRole role) {
        EnumMap<SpiritCatRole, UUID> playerBindings = BINDINGS.get(playerUuid);
        UUID entityUuid = playerBindings == null ? null : playerBindings.get(role);
        if (entityUuid == null) {
            return null;
        }
        for (ServerLevel level : server.getAllLevels()) {
            if (level.getEntity(entityUuid) instanceof SpiritCat cat && !cat.isRemoved()) {
                return cat;
            }
        }
        unbind(playerUuid, role, entityUuid);
        return null;
    }

    public static void unbind(UUID playerUuid, SpiritCatRole role, UUID entityUuid) {
        EnumMap<SpiritCatRole, UUID> playerBindings = BINDINGS.get(playerUuid);
        if (playerBindings == null || !entityUuid.equals(playerBindings.get(role))) {
            return;
        }
        playerBindings.remove(role);
        if (playerBindings.isEmpty()) {
            BINDINGS.remove(playerUuid);
        }
    }

    public static void removeEntity(UUID entityUuid) {
        BINDINGS.entrySet().removeIf(entry -> {
            entry.getValue().entrySet().removeIf(binding -> entityUuid.equals(binding.getValue()));
            return entry.getValue().isEmpty();
        });
    }
}
