package com.meteorite.unsuspiciousblock.journal.tracking;

import com.meteorite.unsuspiciousblock.achievement.AchievementManager;
import com.meteorite.unsuspiciousblock.achievement.ModAchievements;
import com.meteorite.unsuspiciousblock.journal.catalog.ArchaeologyJournalServerCatalog;
import com.meteorite.unsuspiciousblock.journal.state.ArchaeologyJournalState;
import com.meteorite.unsuspiciousblock.journal.state.ArchaeologyJournalState.TableProgress;
import com.meteorite.unsuspiciousblock.journal.state.ArchaeologyJournalStateHolder;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.ItemDefinition;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.TableDefinition;
import com.meteorite.unsuspiciousblock.loottable.signature.LootResultSignature;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/***
 * 考古收集类成就检测器——在战利品解锁后核对玩家的考古日记进度，
 * 判定是否达成「集齐陶片 / 集齐样板 / 原版考古表全图鉴」并授予对应成就。
 * <p>
 * 原版考古陶片与纹饰样板的判定范围取自原版物品标签（#minecraft:decorated_pot_sherds 与
 * #minecraft:trim_templates），且仅保留 minecraft 命名空间条目，其他模组向标签添加的内容会被忽略；
 * 全图鉴成就仅要求原版考古战利品表（minecraft:archaeology/ 前缀）全部解锁，
 * 基于 rawCatalog 判定以避免渐进模拟导致的范围不稳定。
 * <p>
 * 提供两个入口：事件触发时 {@link #checkAndGrant} 实时检测；玩家加入时 {@link #checkAndGrantAll} 补发检测。
 */
public final class ArchaeologyChallengeChecker {

    // 集齐陶片的判定范围：原版陶片标签（仅 minecraft 命名空间条目生效）
    private static final TagKey<Item> SHERD_TAG = ItemTags.DECORATED_POT_SHERDS;

    // 集齐纹饰样板的判定范围：原版纹饰样板标签（仅 minecraft 命名空间条目生效）
    private static final TagKey<Item> TRIM_TEMPLATE_TAG = ItemTags.TRIM_TEMPLATES;

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

        // 集齐陶片 / 样板：按原版标签判定，忽略其他模组添加的条目；标签为空（尚未绑定）时不授予
        if (needSherd) {
            Set<ResourceLocation> sherds = vanillaTagItems(SHERD_TAG);
            if (!sherds.isEmpty() && allCollected(state, sherds)) {
                AchievementManager.grantIfNotAlready(player, ModAchievements.SHERD_COLLECTOR);
            }
        }
        if (needTemplate) {
            Set<ResourceLocation> templates = vanillaTagItems(TRIM_TEMPLATE_TAG);
            if (!templates.isEmpty() && allCollected(state, templates)) {
                AchievementManager.grantIfNotAlready(player, ModAchievements.TEMPLATE_COLLECTOR);
            }
        }

        // 全图鉴：要求解锁所有原版考古战利品表且表中每件物品都至少获得一次
        if (needCompletionist && isFullCatalogUnlocked(state)) {
            AchievementManager.grantIfNotAlready(player, ModAchievements.COMPLETIONISTS_DUST);
        }
    }

    // 补发入口：玩家加入世界时全量检测三项成就，避免 catalog 未就绪或事件漏触发时遗漏授予
    public static void checkAndGrantAll(ServerPlayer player) {
        ArchaeologyJournalState state = ArchaeologyJournalStateHolder.getState(player);
        if (state == null) {
            return;
        }
        checkAndGrant(player, state);
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

    // 判断原版考古战利品表（minecraft:archaeology/ 前缀）及其全部物品是否都已解锁。
    // 使用 rawCatalog（启动即完整）确定判定范围，避免渐进加载的 catalog 导致范围不稳定。
    private static boolean isFullCatalogUnlocked(ArchaeologyJournalState state) {
        Map<ResourceLocation, TableDefinition> rawCatalog = ArchaeologyJournalServerCatalog.getRawCatalog();
        boolean anyVanillaTable = false;
        for (TableDefinition table : rawCatalog.values()) {
            if (!isVanillaArchaeologyTable(table.id())) {
                continue;
            }
            anyVanillaTable = true;
            if (!state.isTableUnlocked(table.id())) {
                return false;
            }
            for (ItemDefinition item : table.items()) {
                if (!state.isItemUnlocked(table.id(), item.signature())) {
                    return false;
                }
            }
        }
        // 至少存在一张原版考古表且全部解锁才算达标，避免 rawCatalog 为空时误判
        return anyVanillaTable;
    }

    // 判断是否为原版考古战利品表：仅匹配 minecraft 命名空间下 archaeology/ 路径前缀
    private static boolean isVanillaArchaeologyTable(ResourceLocation tableId) {
        return "minecraft".equals(tableId.getNamespace())
                && tableId.getPath().startsWith("archaeology/");
    }

    // 读取物品标签中 minecraft 命名空间的物品 id 集合；模组添加的条目被忽略，
    // 标签尚未绑定或不含原版物品时返回空集（调用方需据此跳过授予）
    private static Set<ResourceLocation> vanillaTagItems(TagKey<Item> tag) {
        Set<ResourceLocation> ids = new HashSet<>();
        for (Holder<Item> holder : BuiltInRegistries.ITEM.getOrCreateTag(tag)) {
            holder.unwrapKey().ifPresent(key -> {
                ResourceLocation id = key.location();
                if ("minecraft".equals(id.getNamespace())) {
                    ids.add(id);
                }
            });
        }
        return ids;
    }
}
