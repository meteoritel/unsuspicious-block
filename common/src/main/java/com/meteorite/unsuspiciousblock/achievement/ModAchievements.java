package com.meteorite.unsuspiciousblock.achievement;

/**
 * 模组所有成就的枚举注册表。
 * 每个枚举项定义了一条成就的 advancement 路径与 criterion 键，
 * 对应的 JSON 文件位于 data/unsuspiciousblock/advancement/ 下。
 * <p>
 * 使用方式：{@code AchievementManager.grant(player, ModAchievements.CACHE_ME_IF_YOU_CAN);}
 */
public enum ModAchievements implements ModAchievement {

    // ======================== 普通进度 ========================

    UNSUSPICIOUS_MINDS("story/unsuspicious_minds", "root"),
    TAKE_NOTE_TAKE_NOTE("adventure/take_note_take_note", "take_note"),
    PAPER_TRAIL("adventure/paper_trail", "paper_trail"),
    PENNY_FOR_YOUR_FINDS("adventure/penny_for_your_finds", "penny"),
    ANCIENT_SCHOLARSHIP("adventure/ancient_scholarship", "scholar"),
    IT_BELONGS_IN_A_MUSEUM("adventure/it_belongs_in_a_museum", "museum"),
    SHERD_COLLECTOR("adventure/sherd_collector", "sherd_collector"),
    TEMPLATE_COLLECTOR("adventure/template_collector", "template_collector"),

    // ======================== 挑战进度 ========================
    COMPLETIONISTS_DUST("challenges/completionists_dust", "completionist"),
    CACHE_ME_IF_YOU_CAN("challenges/cache_me_if_you_can", "reach_1024_entries");

    private final String path;
    private final String criterion;

    ModAchievements(String path, String criterion) {
        this.path = path;
        this.criterion = criterion;
    }

    @Override
    public String path() {
        return path;
    }

    @Override
    public String criterion() {
        return criterion;
    }
}
