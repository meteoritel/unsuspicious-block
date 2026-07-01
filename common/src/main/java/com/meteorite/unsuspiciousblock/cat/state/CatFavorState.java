package com.meteorite.unsuspiciousblock.cat.state;

import com.meteorite.unsuspiciousblock.cat.CatFavorAction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

import java.util.EnumMap;
import java.util.Map;

/**
 * 玩家「猫之恩惠」持久化状态——保存 0-100 的恩惠值以及各类累积行为的冷却时间戳。
 * 通过 mixin 附加在玩家 NBT 中持久化，仿照 ArchaeologyJournalState 的序列化范式。
 */
public final class CatFavorState {
    // 恩惠值的取值范围
    public static final int MIN_FAVOR = 0;
    public static final int MAX_FAVOR = 100;

    private static final String FAVOR_TAG = "favor";
    private static final String COOLDOWNS_TAG = "cooldowns";
    private static final String DETERRENCE_DISABLED_TAG = "deterrence_disabled";

    // 当前恩惠值（0-100）
    private int favor;
    // 各累积行为上次触发的游戏时间（gameTime tick），用于冷却判定
    private final Map<CatFavorAction, Long> lastTriggerGameTime = new EnumMap<>(CatFavorAction.class);

    // ========== 持久化的玩家偏好 ==========
    // 是否手动关闭了「猫的威慑」被动（持久化，随重生保留）
    private boolean deterrenceDisabled;

    // ========== 瞬态运行时状态（不序列化，重生后重置） ==========
    // 上一 tick 是否拥有「村庄英雄」效果，用于击退袭击的上升沿检测
    private transient boolean hadHeroEffect;
    // 客户端是否请求开启夜视（环境亮度低于阈值）
    private transient boolean nightVisionRequested;
    // 夜视淡出倒计时（tick）：亮度恢复后延迟移除夜视
    private transient int nightVisionFadeTicks;
    // 猫之九命无敌窗口的截止游戏时间（gameTime tick），此前免疫所有伤害
    private transient long nineLivesInvulnUntil;
    // 缓存的能力位掩码（由 CatPassiveAbilities 每 tick 计算，供 mixin 廉价查询）
    private transient int abilityMask;

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
        return gameTime - last >= action.cooldownTicks();
    }

    // 记录指定行为在给定游戏时间被触发（刷新冷却起点）
    public void markTriggered(CatFavorAction action, long gameTime) {
        this.lastTriggerGameTime.put(action, gameTime);
    }

    // ========== 威慑开关（持久化） ==========
    public boolean isDeterrenceDisabled() {
        return this.deterrenceDisabled;
    }

    // 翻转威慑开关，返回翻转后的新状态
    public boolean toggleDeterrence() {
        this.deterrenceDisabled = !this.deterrenceDisabled;
        return this.deterrenceDisabled;
    }

    // ========== 瞬态状态访问 ==========
    public boolean hadHeroEffect() {
        return this.hadHeroEffect;
    }

    public void setHadHeroEffect(boolean value) {
        this.hadHeroEffect = value;
    }

    public boolean isNightVisionRequested() {
        return this.nightVisionRequested;
    }

    public void setNightVisionRequested(boolean value) {
        this.nightVisionRequested = value;
    }

    public int getNightVisionFadeTicks() {
        return this.nightVisionFadeTicks;
    }

    public void setNightVisionFadeTicks(int ticks) {
        this.nightVisionFadeTicks = ticks;
    }

    public long getNineLivesInvulnUntil() {
        return this.nineLivesInvulnUntil;
    }

    public void setNineLivesInvulnUntil(long gameTime) {
        this.nineLivesInvulnUntil = gameTime;
    }

    public int getAbilityMask() {
        return this.abilityMask;
    }

    public void setAbilityMask(int mask) {
        this.abilityMask = mask;
    }

    // 清空所有状态
    public void clear() {
        this.favor = MIN_FAVOR;
        this.lastTriggerGameTime.clear();
        this.deterrenceDisabled = false;
        this.hadHeroEffect = false;
        this.nightVisionRequested = false;
        this.nightVisionFadeTicks = 0;
        this.nineLivesInvulnUntil = 0L;
        this.abilityMask = 0;
    }

    // 从另一个状态复制全部数据（用于玩家重生时保留恩惠）
    public void copyFrom(CatFavorState other) {
        this.favor = other.favor;
        this.lastTriggerGameTime.clear();
        this.lastTriggerGameTime.putAll(other.lastTriggerGameTime);
        // 持久化偏好跟随重生；瞬态运行时状态不拷贝
        this.deterrenceDisabled = other.deterrenceDisabled;
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
        tag.putBoolean(DETERRENCE_DISABLED_TAG, this.deterrenceDisabled);
        return tag;
    }

    // 从 NBT 反序列化恢复状态
    public void readFrom(CompoundTag tag) {
        this.clear();
        this.favor = Math.max(MIN_FAVOR, Math.min(MAX_FAVOR, tag.getInt(FAVOR_TAG)));
        this.deterrenceDisabled = tag.getBoolean(DETERRENCE_DISABLED_TAG);
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
