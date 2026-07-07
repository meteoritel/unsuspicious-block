package com.meteorite.unsuspiciousblock.journal.tracking;

import com.meteorite.unsuspiciousblock.achievement.AchievementManager;
import com.meteorite.unsuspiciousblock.achievement.ModAchievements;
import com.meteorite.unsuspiciousblock.journal.catalog.ArchaeologyJournalServerCatalog;
import com.meteorite.unsuspiciousblock.journal.state.ArchaeologyJournalState;
import com.meteorite.unsuspiciousblock.journal.state.ArchaeologyJournalState.TableProgress;
import com.meteorite.unsuspiciousblock.loottable.ArchaeologyLootTableCatalog.ItemDefinition;
import com.meteorite.unsuspiciousblock.loottable.ArchaeologyLootTableCatalog.TableDefinition;
import com.meteorite.unsuspiciousblock.loottable.LootResultSignature;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.Set;

/***
 * 考古收集类成就检测器——在战利品解锁后核对玩家的考古日记进度，
 * 判定是否达成「集齐陶片 / 集齐样板 / 全图鉴」并授予对应成就。
 * <p>
 * 原版考古陶片与纹饰样板数量固定，故采用穷举固定物品集合的判定方式，
 * 无需遍历完整目录；全图鉴成就因本就要求全表全物品，仍遍历服务端目录。
 */
public final class ArchaeologyChallengeChecker {

    // 20 种原版考古陶片
    private static final Set<ResourceLocation> POTTERY_SHERDS = Set.of(
            sherd("angler"), sherd("archer"), sherd("arms_up"), sherd("blade"),
            sherd("brewer"), sherd("burn"), sherd("danger"), sherd("explorer"),
            sherd("friend"), sherd("heart"), sherd("heartbreak"), sherd("howl"),
            sherd("miner"), sherd("mourner"), sherd("plenty"), sherd("prize"),
            sherd("sheaf"), sherd("shelter"), sherd("skull"), sherd("snort"));

    // 4 种原版考古纹饰样板（向导 / 牧民 / 塑造 / 雇主）
    private static final Set<ResourceLocation> TRIM_TEMPLATES = Set.of(
            template("wayfinder"), template("raiser"), template("shaper"), template("host"));

    private ArchaeologyChallengeChecker() {
    }

    // 核对并授予三项考古收集成就（已获得的成就会跳过计算）
    public static void checkAndGrant(ServerPlayer player, ArchaeologyJournalState state) {
        if (state == null) {
            return;
        }

        boolean needSherd = !AchievementManager.has(player, ModAchievements.SHERD_COLLECTOR);
        boolean needTemplate = !AchievementManager.has(player, ModAchievements.TEMPLATE_COLLECTOR);
        boolean needCompletionist = !AchievementManager.has(player, ModAchievements.COMPLETIONISTS_DUST);
        // 三项均已获得则无需计算
        if (!needSherd && !needTemplate && !needCompletionist) {
            return;
        }

        // 集齐陶片 / 样板：穷举固定物品集合判定
        if (needSherd && allCollected(state, POTTERY_SHERDS)) {
            AchievementManager.grantIfNotAlready(player, ModAchievements.SHERD_COLLECTOR);
        }
        if (needTemplate && allCollected(state, TRIM_TEMPLATES)) {
            AchievementManager.grantIfNotAlready(player, ModAchievements.TEMPLATE_COLLECTOR);
        }

        // 全图鉴：要求解锁所有原版考古战利品表且表中每件物品都至少获得一次
        if (needCompletionist && isFullCatalogUnlocked(state)) {
            AchievementManager.grantIfNotAlready(player, ModAchievements.COMPLETIONISTS_DUST);
        }
    }

    // 判断给定物品集合是否已在玩家日志中全部解锁
    private static boolean allCollected(ArchaeologyJournalState state, Set<ResourceLocation> itemIds) {
        for (ResourceLocation itemId : itemIds) {
            if (!isItemCollected(state, itemId)) {
                return false;
            }
        }
        return true;
    }

    // 判断某物品是否在玩家任意战利品表中已解锁（陶片/样板均为普通物品，使用 PLAIN 签名匹配）
    private static boolean isItemCollected(ArchaeologyJournalState state, ResourceLocation itemId) {
        LootResultSignature signature = LootResultSignature.plain(itemId);
        for (TableProgress table : state.getTables().values()) {
            if (table.isItemUnlocked(signature)) {
                return true;
            }
        }
        return false;
    }

    // 判断服务端目录中所有表及其全部物品是否都已解锁
    private static boolean isFullCatalogUnlocked(ArchaeologyJournalState state) {
        Map<ResourceLocation, TableDefinition> catalog = ArchaeologyJournalServerCatalog.getCatalog();
        if (catalog.isEmpty()) {
            return false;
        }
        for (TableDefinition table : catalog.values()) {
            ResourceLocation tableId = table.id();
            if (!state.isTableUnlocked(tableId)) {
                return false;
            }
            for (ItemDefinition item : table.items()) {
                if (!state.isItemUnlocked(tableId, item.signature())) {
                    return false;
                }
            }
        }
        return true;
    }

    private static ResourceLocation sherd(String name) {
        return ResourceLocation.withDefaultNamespace(name + "_pottery_sherd");
    }

    private static ResourceLocation template(String name) {
        return ResourceLocation.withDefaultNamespace(name + "_armor_trim_smithing_template");
    }
}
