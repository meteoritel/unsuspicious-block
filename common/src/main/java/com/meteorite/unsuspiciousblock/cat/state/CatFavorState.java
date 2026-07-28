package com.meteorite.unsuspiciousblock.cat.state;

import com.meteorite.unsuspiciousblock.cat.CatFavorAction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

/**
 * 玩家猫族关系持久化状态——保存关系是否建立、0-100 猫族羁绊、行为冷却与玩家偏好。
 * 通过 mixin 附加在玩家 NBT 中持久化，并负责从旧版猫之恩惠数据迁移。
 */
public final class CatFavorState {
    public static final int CURRENT_DATA_VERSION = 2;
    public static final int MIN_FAVOR = 0;
    public static final int MAX_FAVOR = 100;

    private static final String DATA_VERSION_TAG = "data_version";
    private static final String CAT_BOND_TAG = "cat_bond";
    private static final String RELATIONSHIP_ESTABLISHED_TAG = "relationship_established";
    private static final String COOLDOWNS_TAG = "cooldowns";
    private static final String DETERRENCE_DISABLED_TAG = "deterrence_disabled";
    private static final String NINE_LIVES_COUNT_TAG = "nine_lives_count";
    private static final String INITIAL_BEST_FRIEND_LIFE_GRANTED_TAG = "initial_best_friend_life_granted";
    private static final String LIGHT_STEP_DISABLED_TAG = "light_step_disabled";

    private static final String LEGACY_FAVOR_TAG = "favor";
    private static final String LEGACY_LIGHT_STEP_PRESSURE_PREVENTED_TAG = "light_step_pressure_prevented";

    private boolean relationshipEstablished;
    private int catBond;
    // 各累积行为上次触发的游戏时间（gameTime tick），用于冷却判定
    private final Map<CatFavorAction, Long> lastTriggerGameTime = new EnumMap<>(CatFavorAction.class);

    // ========== 持久化的玩家偏好 ==========
    // 是否手动关闭了「猫的威慑」被动（持久化，随重生保留）
    private boolean deterrenceDisabled;
    // 猫之九命：当前累积的额外命数（0-9）
    private int nineLivesCount;
    // 轻步总开关：true = 玩家主动关闭整个轻步能力。
    private boolean lightStepDisabled;
    // 是否已经领取过首次成为猫国挚友时授予的 1 条命。
    private boolean initialBestFriendLifeGranted;

    // ========== 瞬态运行时状态（不序列化，重生后重置） ==========
    // 上一 tick 是否拥有「村庄英雄」效果，用于击退袭击的上升沿检测
    private transient boolean hadHeroEffect;
    // 夜视分级检测冷却倒计时（tick）：空闲态 1s 侦测，激活态 5s 刷新
    private transient int nightVisionCheckCooldown;
    // 缓存的能力位掩码（由 CatPassiveAbilities 每 tick 计算，供 mixin 廉价查询）
    private transient int abilityMask;
    // 喂食奖励延迟到 tick 末结算，成功驯服时由驯服入口取消。
    private transient boolean pendingFeedReward;
    // 记录最近一次由玩家造成的猫伤害，用于致命一击扣分去重。
    private transient UUID recentlyHitCatUuid;
    private transient long recentlyHitCatGameTime = Long.MIN_VALUE;
    private transient UUID activeMessengerUuid;
    private transient UUID activeSwordsmanUuid;

    public boolean isRelationshipEstablished() {
        return this.relationshipEstablished;
    }

    // 建立猫族关系，返回状态是否发生变化。
    public boolean establishRelationship() {
        if (this.relationshipEstablished) {
            return false;
        }
        this.relationshipEstablished = true;
        return true;
    }

    public int getCatBond() {
        return this.catBond;
    }

    // 设置猫族羁绊并限制到 0-100。
    public boolean setCatBond(int value) {
        int clamped = Math.max(MIN_FAVOR, Math.min(MAX_FAVOR, value));
        if (clamped == this.catBond) {
            return false;
        }
        this.catBond = clamped;
        if (this.catBond < MAX_FAVOR) {
            this.nineLivesCount = 0;
        }
        return true;
    }

    // 兼容现有调用，后续阶段逐步迁移为猫族羁绊术语。
    public int getFavor() {
        return this.getCatBond();
    }

    // 兼容现有调用，后续阶段逐步迁移为猫族羁绊术语。
    public boolean setFavor(int value) {
        return this.setCatBond(value);
    }

    public boolean addCatBond(int delta) {
        return this.setCatBond(this.catBond + delta);
    }

    // 兼容现有调用，后续阶段逐步迁移为猫族羁绊术语。
    public boolean addFavor(int delta) {
        return this.addCatBond(delta);
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

    // ========== 猫之九命命数（持久化） ==========
    public int getNineLivesCount() {
        return this.nineLivesCount;
    }

    // 设置命数，自动 clamp 到 0-9
    public void setNineLivesCount(int value) {
        this.nineLivesCount = Math.max(0, Math.min(9, value));
    }

    // 增加一条命（上限 9）
    public void addOneLife() {
        if (this.nineLivesCount < 9) {
            this.nineLivesCount++;
        }
    }

    // 消耗一条命，返回是否消耗成功（命数>0 时才可消耗）
    public boolean consumeOneLife() {
        if (this.nineLivesCount <= 0) {
            return false;
        }
        this.nineLivesCount--;
        return true;
    }

    public boolean isInitialBestFriendLifeGranted() {
        return this.initialBestFriendLifeGranted;
    }

    public void markInitialBestFriendLifeGranted() {
        this.initialBestFriendLifeGranted = true;
    }

    public UUID getActiveMessengerUuid() {
        return this.activeMessengerUuid;
    }

    public void setActiveMessengerUuid(UUID entityUuid) {
        this.activeMessengerUuid = entityUuid;
    }

    // 仅在 UUID 匹配时清除活跃信使，避免旧实体移除时覆盖新记录。
    public void clearActiveMessengerUuid(UUID entityUuid) {
        if (entityUuid.equals(this.activeMessengerUuid)) {
            this.activeMessengerUuid = null;
        }
    }

    public UUID getActiveSwordsmanUuid() {
        return this.activeSwordsmanUuid;
    }

    public void setActiveSwordsmanUuid(UUID entityUuid) {
        this.activeSwordsmanUuid = entityUuid;
    }

    // 仅在 UUID 匹配时清除活跃剑士，避免旧实体移除时覆盖新记录。
    public void clearActiveSwordsmanUuid(UUID entityUuid) {
        if (entityUuid.equals(this.activeSwordsmanUuid)) {
            this.activeSwordsmanUuid = null;
        }
    }

    // ========== 轻步总开关（持久化） ==========
    public boolean isLightStepPressurePrevented() {
        return !this.lightStepDisabled;
    }

    public boolean isLightStepDisabled() {
        return this.lightStepDisabled;
    }

    // 翻转整个轻步能力，返回翻转后是否启用。
    public boolean toggleLightStepPressure() {
        this.lightStepDisabled = !this.lightStepDisabled;
        return !this.lightStepDisabled;
    }

    // ========== 瞬态状态访问 ==========
    public boolean hadHeroEffect() {
        return this.hadHeroEffect;
    }

    public void setHadHeroEffect(boolean value) {
        this.hadHeroEffect = value;
    }

    public int getNightVisionCheckCooldown() {
        return this.nightVisionCheckCooldown;
    }

    public void setNightVisionCheckCooldown(int ticks) {
        this.nightVisionCheckCooldown = ticks;
    }

    public int getAbilityMask() {
        return this.abilityMask;
    }

    public void setAbilityMask(int mask) {
        this.abilityMask = mask;
    }

    public void queueFeedReward() {
        this.pendingFeedReward = true;
    }

    public void cancelPendingFeedReward() {
        this.pendingFeedReward = false;
    }

    // 取出并清除待结算的喂食奖励。
    public boolean consumePendingFeedReward() {
        boolean pending = this.pendingFeedReward;
        this.pendingFeedReward = false;
        return pending;
    }

    public void recordCatHit(UUID catUuid, long gameTime) {
        this.recentlyHitCatUuid = catUuid;
        this.recentlyHitCatGameTime = gameTime;
    }

    // 判断死亡是否来自同 tick 已扣过 5 点的致命攻击，并清除记录。
    public boolean consumeMatchingCatHit(UUID catUuid, long gameTime) {
        boolean matches = catUuid.equals(this.recentlyHitCatUuid)
                && gameTime == this.recentlyHitCatGameTime;
        this.recentlyHitCatUuid = null;
        this.recentlyHitCatGameTime = Long.MIN_VALUE;
        return matches;
    }

    // 清空所有状态
    public void clear() {
        this.relationshipEstablished = false;
        this.catBond = MIN_FAVOR;
        this.lastTriggerGameTime.clear();
        this.deterrenceDisabled = false;
        this.nineLivesCount = 0;
        this.lightStepDisabled = false;
        this.initialBestFriendLifeGranted = false;
        this.hadHeroEffect = false;
        this.nightVisionCheckCooldown = 0;
        this.abilityMask = 0;
        this.pendingFeedReward = false;
        this.recentlyHitCatUuid = null;
        this.recentlyHitCatGameTime = Long.MIN_VALUE;
        this.activeMessengerUuid = null;
        this.activeSwordsmanUuid = null;
    }

    // 从另一个状态复制全部数据（用于玩家重生时保留恩惠）
    public void copyFrom(CatFavorState other) {
        this.relationshipEstablished = other.relationshipEstablished;
        this.catBond = other.catBond;
        this.lastTriggerGameTime.clear();
        this.lastTriggerGameTime.putAll(other.lastTriggerGameTime);
        // 持久化偏好跟随重生；瞬态运行时状态不拷贝
        this.deterrenceDisabled = other.deterrenceDisabled;
        this.nineLivesCount = other.nineLivesCount;
        this.lightStepDisabled = other.lightStepDisabled;
        this.initialBestFriendLifeGranted = other.initialBestFriendLifeGranted;
    }

    // 序列化为 NBT
    public CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();
        tag.putInt(DATA_VERSION_TAG, CURRENT_DATA_VERSION);
        tag.putBoolean(RELATIONSHIP_ESTABLISHED_TAG, this.relationshipEstablished);
        tag.putInt(CAT_BOND_TAG, this.catBond);
        CompoundTag cooldowns = new CompoundTag();
        for (Map.Entry<CatFavorAction, Long> entry : this.lastTriggerGameTime.entrySet()) {
            cooldowns.putLong(entry.getKey().name(), entry.getValue());
        }
        tag.put(COOLDOWNS_TAG, cooldowns);
        tag.putBoolean(DETERRENCE_DISABLED_TAG, this.deterrenceDisabled);
        tag.putInt(NINE_LIVES_COUNT_TAG, this.nineLivesCount);
        tag.putBoolean(INITIAL_BEST_FRIEND_LIFE_GRANTED_TAG, this.initialBestFriendLifeGranted);
        tag.putBoolean(LIGHT_STEP_DISABLED_TAG, this.lightStepDisabled);
        return tag;
    }

    // 从 NBT 反序列化，并在缺少数据版本时执行旧格式迁移。
    public void readFrom(CompoundTag tag) {
        this.clear();
        if (!tag.contains(DATA_VERSION_TAG, Tag.TAG_INT)) {
            this.readLegacy(tag);
            return;
        }
        this.relationshipEstablished = tag.getBoolean(RELATIONSHIP_ESTABLISHED_TAG);
        this.catBond = Math.max(MIN_FAVOR, Math.min(MAX_FAVOR, tag.getInt(CAT_BOND_TAG)));
        if (this.catBond > MIN_FAVOR) {
            this.relationshipEstablished = true;
        }
        this.deterrenceDisabled = tag.getBoolean(DETERRENCE_DISABLED_TAG);
        if (tag.contains(NINE_LIVES_COUNT_TAG, Tag.TAG_INT)) {
            this.nineLivesCount = Math.max(0, Math.min(9, tag.getInt(NINE_LIVES_COUNT_TAG)));
        }
        this.initialBestFriendLifeGranted = tag.getBoolean(INITIAL_BEST_FRIEND_LIFE_GRANTED_TAG);
        this.lightStepDisabled = tag.getBoolean(LIGHT_STEP_DISABLED_TAG);
        if (this.catBond < MAX_FAVOR) {
            this.nineLivesCount = 0;
        }
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

    // 迁移旧 favor 格式：冷却清空，旧压力板偏好映射为整个轻步开关。
    private void readLegacy(CompoundTag tag) {
        this.catBond = Math.max(MIN_FAVOR, Math.min(MAX_FAVOR, tag.getInt(LEGACY_FAVOR_TAG)));
        this.relationshipEstablished = this.catBond > MIN_FAVOR;
        this.deterrenceDisabled = tag.getBoolean(DETERRENCE_DISABLED_TAG);
        if (this.catBond == MAX_FAVOR && tag.contains(NINE_LIVES_COUNT_TAG, Tag.TAG_INT)) {
            this.nineLivesCount = Math.max(0, Math.min(9, tag.getInt(NINE_LIVES_COUNT_TAG)));
        }
        this.initialBestFriendLifeGranted = this.catBond == MAX_FAVOR;
        if (tag.contains(LEGACY_LIGHT_STEP_PRESSURE_PREVENTED_TAG, Tag.TAG_BYTE)) {
            this.lightStepDisabled = !tag.getBoolean(LEGACY_LIGHT_STEP_PRESSURE_PREVENTED_TAG);
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
