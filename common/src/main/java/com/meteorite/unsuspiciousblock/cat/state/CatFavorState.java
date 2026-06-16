package com.meteorite.unsuspiciousblock.cat.state;

import com.meteorite.unsuspiciousblock.cat.CatFavorAction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

import java.util.EnumMap;
import java.util.Map;

/**
 * 玩家「猫之恩惠」��持久化状态——保存 0-100 的恩惠值以及各类累积行为的冷却时间戳。
 * 通过 mixin 附加在玩家 NBT 中持久化，仿照 ArchaeologyJournalState 的序列化范式。
 */
public final class CatFavorState {
    // 恩惠值的取值范围
    public static final int MIN_FAVOR = 0;
    public static final int MAX_FAVOR = 100;

    private static final String FAVOR_TAG = "favor";
    private static final String COOLDOWNS_TAG = "cooldowns";

    // 当前恩惠值（0-100）
    private int favor;
    // 各累积行为上次触发的游戏时间（gameTime tick），用于冷却判定
    private final Map<CatFavorAction, Long> lastTriggerGameTime = new EnumMap<>(CatFavorAction.class);

    // 获取当前恩惠值
    public int getFavor() {
        return this.favor;
    }

    // 设置恩惠值（自动 clamp 到 0-100），返回是否发生了变化
    public boolean setFavor(int value) {
        int clamped = Math.max(MIN_FAVOR, Math.min(MAX_FAVOR, value));
        if (clamped == this.favor) {
            return false;
        }
        this.favor = clamped;
        return true;
    }

    // 在当前恩惠值基础上增减指定量（可为负），返回是否发生了变化
    public boolean addFavor(int delta) {
        return this.setFavor(this.favor + delta);
    }

    // 判断指定行为在给定游戏时间是否已过冷却（可再次累积）
    public boolean canTrigger(CatFavorAction action, long gameTime) {
        Long last = this.lastTriggerGameTime.get(action);
        if (last == null) {
            return true;
        }
        return gameTime - last >= CatFavorAction.COOLDOWN_TICKS;
    }

    // 记录指定行为在给定游戏时间被触发（刷新冷却起点）
    public void markTriggered(CatFavorAction action, long gameTime) {
        this.lastTriggerGameTime.put(action, gameTime);
    }

    // 清空所有状态
    public void clear() {
        this.favor = MIN_FAVOR;
        this.lastTriggerGameTime.clear();
    }

    // 从另一个状态复制全部数据（用于玩家重生时保留恩惠）
    public void copyFrom(CatFavorState other) {
        this.favor = other.favor;
        this.lastTriggerGameTime.clear();
        this.lastTriggerGameTime.putAll(other.lastTriggerGameTime);
    }

    // 序列化为 NBT
    public CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();
        tag.putInt(FAVOR_TAG, this.favor);
        CompoundTag cooldowns = new CompoundTag();
        for (Map.Entry<CatFavorAction, Long> entry : this.lastTriggerGameTime.entrySet()) {
            cooldowns.putLong(entry.getKey().name(), entry.getValue());
        }
        tag.put(COOLDOWNS_TAG, cooldowns);
        return tag;
    }

    // 从 NBT 反序列化恢复状态
    public void readFrom(CompoundTag tag) {
        this.clear();
        this.favor = Math.max(MIN_FAVOR, Math.min(MAX_FAVOR, tag.getInt(FAVOR_TAG)));
        if (tag.contains(COOLDOWNS_TAG, Tag.TAG_COMPOUND)) {
            CompoundTag cooldowns = tag.getCompound(COOLDOWNS_TAG);
            for (String key : cooldowns.getAllKeys()) {
                CatFavorAction action = tryParseAction(key);
                if (action != null) {
                    this.lastTriggerGameTime.put(action, cooldowns.getLong(key));
                }
            }
        }
    }

    // 安全解析行为枚举（忽略未知/已废弃的键）
    private static CatFavorAction tryParseAction(String name) {
        try {
            return CatFavorAction.valueOf(name);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
