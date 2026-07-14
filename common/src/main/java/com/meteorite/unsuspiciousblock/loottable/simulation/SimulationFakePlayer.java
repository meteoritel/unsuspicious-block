package com.meteorite.unsuspiciousblock.loottable.simulation;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.network.chat.Component;
import net.minecraft.stats.Stat;
import org.jetbrains.annotations.NotNull;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * 模拟用虚拟玩家——用于在无在线玩家时填充实体类 LootContextParam（THIS_ENTITY 等）。
 * 实现思路与 NeoForge 的 FakePlayer 一致：继承 ServerPlayer，将可能产生副作用的方法
 * （发包、统计、死亡、tick）重写为 no-op，避免虚拟玩家影响游戏状态。
 * 不设置 connection 字段（保持 null）：战利品函数仅读取玩家状态（luck、position、inventory 等），
 * 不会访问网络层 connection。ServerPlayer 构造器本身不访问 connection（NeoForge FakePlayer
 * 的字节码可证：super() 调用先于 connection 赋值）。
 * 该类为原版 ServerPlayer 子类，不依赖任何平台 API，NeoForge/Fabric 通用。
 */
public final class SimulationFakePlayer extends ServerPlayer {
    // 固定 UUID + 名称，保证 GameProfile 稳定可识别
    private static final UUID SIMULATION_UUID =
            UUID.nameUUIDFromBytes("unsuspiciousblock-simulation".getBytes(StandardCharsets.UTF_8));
    private static final String SIMULATION_NAME = "[USB-Simulation]";

    public SimulationFakePlayer(MinecraftServer server, ServerLevel level) {
        super(server, level, new GameProfile(SIMULATION_UUID, SIMULATION_NAME), ClientInformation.createDefault());
    }

    // 以下重写均为 no-op，避免虚拟玩家产生副作用

    @Override
    public void displayClientMessage(@NotNull Component component, boolean actionbar) {
        // 虚拟玩家无客户端，丢弃消息
    }

    @Override
    public void awardStat(@NotNull Stat<?> stat, int amount) {
        // 虚拟玩家不计入统计
    }

    @Override
    public void die(@NotNull DamageSource source) {
        // 虚拟玩家不会死亡
    }

    @Override
    public void tick() {
        // 虚拟玩家不参与 tick
    }

    @Override
    public boolean isInvulnerableTo(@NotNull DamageSource source) {
        // 虚拟玩家不可被伤害
        return true;
    }

    @Override
    public boolean canHarmPlayer(@NotNull Player other) {
        // 虚拟玩家不可被伤害
        return false;
    }
}
