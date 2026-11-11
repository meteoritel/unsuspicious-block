package com.meteorite.unsuspiciousblock.core.cat.state;

import com.meteorite.unsuspiciousblock.core.cat.CatBondStage;
import com.meteorite.unsuspiciousblock.core.cat.CatFavorAction;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/** 纯猫族记账：冷却、目标去重、羁绊与命数规则共用一个写入入口。 */
public final class CatBondLedger {
    private CatBondLedger() {}

    /** 行为带目标身份；其他变更使用对应事件或值。 */
    public sealed interface Mutation permits Behavior, Event, SetBond, AddBond, PassiveSnapshot {}

    /** HIT/KILL 的目标用于识别同猫同 tick 的致命攻击。 */
    public record Behavior(CatFavorAction action, @Nullable UUID catUuid) implements Mutation {
        public Behavior {
            if ((action == CatFavorAction.HIT_CAT || action == CatFavorAction.KILL_CAT) && catUuid == null) {
                throw new IllegalArgumentException("击打或击杀记账需要猫 UUID");
            }
        }
    }

    /** 不依赖世界或玩家实例的离散状态事件。 */
    public enum Event implements Mutation {
        ESTABLISH_RELATIONSHIP, QUEUE_FEED, CANCEL_FEED, SETTLE_FEED,
        GRANT_INITIAL_LIFE, MESSENGER_GIFT, CONSUME_LIFE,
        TOGGLE_DETERRENCE, TOGGLE_LIGHT_STEP, RESET
    }

    /** 调试设置保留夹取规则，不隐式建立关系。 */
    public record SetBond(int value) implements Mutation {}
    /** 调试增量与设置分别表达，保持命令原有语义。 */
    public record AddBond(int delta) implements Mutation {}
    /** 每 tick 的能力派生状态不落盘，也不触发线路同步。 */
    public record PassiveSnapshot(int abilityMask, int nightVisionCooldown, boolean hadHeroEffect) implements Mutation {}

    /** applied 表示事件已生效；同步只取实际线路字段变化。 */
    public record Change(int previousBond, int bond, int previousLives, int lives,
                         boolean previousRelationship, boolean relationship,
                         CatBondStage previousStage, CatBondStage stage, boolean applied) {
        public int delta() { return bond - previousBond; }
        public boolean requiresSync() {
            return bond != previousBond || lives != previousLives || relationship != previousRelationship;
        }
    }

    /** 所有时间判断显式使用 gameTime，恢复操作不经过玩法记账。 */
    public static Change apply(CatFavorState state, Mutation mutation, long gameTime) {
        int previousBond = state.getCatBond();
        int previousLives = state.getNineLivesCount();
        boolean previousRelationship = state.isRelationshipEstablished();
        boolean applied = switch (mutation) {
            case Behavior behavior -> applyBehavior(state, behavior, gameTime);
            case SetBond setting -> state.setCatBond(setting.value());
            case AddBond addition -> state.addCatBond(addition.delta());
            case PassiveSnapshot snapshot -> {
                state.setAbilityMask(snapshot.abilityMask());
                state.setNightVisionCheckCooldown(snapshot.nightVisionCooldown());
                state.setHadHeroEffect(snapshot.hadHeroEffect());
                yield true;
            }
            case Event event -> applyEvent(state, event, gameTime);
        };
        return new Change(previousBond, state.getCatBond(), previousLives, state.getNineLivesCount(),
                previousRelationship, state.isRelationshipEstablished(),
                CatBondStage.fromBond(previousBond), CatBondStage.fromBond(state.getCatBond()), applied);
    }

    // Behavior 构造器已保证 HIT/KILL 携带非空目标。
    @SuppressWarnings("DataFlowIssue")
    private static boolean applyBehavior(CatFavorState state, Behavior behavior, long gameTime) {
        if (!state.isRelationshipEstablished()) return false;
        CatFavorAction action = behavior.action();
        if (!action.isPenalty()) {
            if (!state.canTrigger(action, gameTime)) return false;
            state.addCatBond(action.favorDelta());
            // 满值行为也消耗冷却，避免降级后回补同一动作。
            state.markTriggered(action, gameTime);
            return true;
        }
        if (action == CatFavorAction.HIT_CAT) state.recordCatHit(behavior.catUuid(), gameTime);
        int delta = action.favorDelta();
        if (action == CatFavorAction.KILL_CAT && state.consumeMatchingCatHit(behavior.catUuid(), gameTime)) {
            delta -= CatFavorAction.HIT_CAT.favorDelta();
        }
        return state.addCatBond(delta);
    }

    private static boolean applyEvent(CatFavorState state, Event event, long gameTime) {
        return switch (event) {
            case ESTABLISH_RELATIONSHIP -> state.establishRelationship();
            case QUEUE_FEED -> { state.queueFeedReward(); yield true; }
            case CANCEL_FEED -> { state.cancelPendingFeedReward(); yield true; }
            case SETTLE_FEED -> state.consumePendingFeedReward()
                    && applyBehavior(state, new Behavior(CatFavorAction.FEED_CAT, null), gameTime);
            case GRANT_INITIAL_LIFE -> {
                if (state.getCatBond() < CatFavorState.MAX_FAVOR || state.isInitialBestFriendLifeGranted()) yield false;
                state.markInitialBestFriendLifeGranted();
                state.setNineLivesCount(Math.max(1, state.getNineLivesCount()));
                yield true;
            }
            case MESSENGER_GIFT -> {
                if (state.getCatBond() < CatFavorState.MAX_FAVOR || state.getNineLivesCount() >= 9) yield false;
                state.addOneLife();
                yield true;
            }
            case CONSUME_LIFE -> state.consumeOneLife();
            case TOGGLE_DETERRENCE -> { state.toggleDeterrence(); yield true; }
            case TOGGLE_LIGHT_STEP -> { state.toggleLightStepPressure(); yield true; }
            case RESET -> { state.clear(); yield true; }
        };
    }
}
