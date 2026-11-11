package com.meteorite.unsuspiciousblock.core.cat.state;

/** 猫族玩法的只读视图；持久化恢复与灵体实体槽位独立接线。 */
public interface CatFavorView {
    boolean isRelationshipEstablished();
    int getCatBond();
    int getFavor();
    int getNineLivesCount();
    boolean isDeterrenceDisabled();
    boolean isLightStepEnabled();
    boolean hadHeroEffect();
    int getNightVisionCheckCooldown();
    int getAbilityMask();
}
