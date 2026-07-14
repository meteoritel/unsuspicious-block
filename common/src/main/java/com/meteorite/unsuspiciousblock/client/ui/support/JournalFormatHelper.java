package com.meteorite.unsuspiciousblock.client.ui.support;

import com.meteorite.unsuspiciousblock.world.GameTimeFormatHelper;
import com.meteorite.unsuspiciousblock.loottable.signature.LootResultSignature;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableNames;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 考古日志 UI 的格式化与预览工具方法。
 */
public final class JournalFormatHelper {
    private JournalFormatHelper() {
    }

    public static Component formatGameTime(String key, long gameTime, long dayTime) {
        GameTimeFormatHelper.GameTimeParts parts = GameTimeFormatHelper.fromTime(gameTime, dayTime);
        return Component.translatable(key, parts.day(), parts.formattedClock());
    }

    public static String formatStructureName(@Nullable ResourceLocation id) {
        if (id == null) {
            return Component.translatable("screen.unsuspiciousblock.archaeology_journal.unknown_structure").getString();
        }
        return formatTranslatedIdentifier(id, "structure");
    }

    /**
     * 结构信息三级降级解析：
     * 1. structureId 非空 → 结构名
     * 2. structureId 为空但 tableId 为考古战利品表 → 战利品表展示名（对应 feature，如沙漠水井）
     * 3. 均为空 → 未知
     * 返回 StructureInfo 包含展示名、是否为 feature 降级、是否为未知。
     */
    public static StructureInfo formatStructureOrFeature(@Nullable ResourceLocation structureId,
                                                         @Nullable ResourceLocation tableId) {
        if (structureId != null) {
            return new StructureInfo(formatStructureName(structureId), false, false);
        }
        if (tableId != null && LootTableNames.isArchaeologyLootTable(tableId)) {
            return new StructureInfo(LootTableNames.resolveDisplayName(tableId).getString(), true, false);
        }
        return new StructureInfo(
                Component.translatable("screen.unsuspiciousblock.archaeology_journal.unknown_structure").getString(),
                false, true);
    }

    /** 结构信息解析结果：展示名 + 是否为 feature 降级 + 是否为未知 */
    public record StructureInfo(String displayName, boolean isFeature, boolean isUnknown) {
    }

    public static String formatBiomeName(@Nullable ResourceLocation id) {
        if (id == null) {
            return Component.translatable("screen.unsuspiciousblock.archaeology_journal.unknown_biome").getString();
        }
        return formatTranslatedIdentifier(id, "biome");
    }

    // 格式化维度名称：对原版维度使用本地化翻译，其他维度使用 formatTranslatedIdentifier
    public static String formatDimensionName(@Nullable ResourceLocation id) {
        if (id == null) {
            return Component.translatable("screen.unsuspiciousblock.archaeology_journal.unknown_dimension").getString();
        }
        return formatTranslatedIdentifier(id, "dimension");
    }

    @Nullable
    public static ItemStack createLootStack(String signatureKey, int count) {
        LootResultSignature signature = LootResultSignature.fromStoredKey(signatureKey);
        if (signature == null || count <= 0) {
            return null;
        }
        ItemStack stack = signature.createPreviewStack();
        if (stack.isEmpty()) {
            return null;
        }
        stack = stack.copy();
        stack.setCount(Math.max(1, Math.min(count, stack.getMaxStackSize())));
        return stack;
    }

    public static List<Map.Entry<String, Integer>> sortedLootEntries(Map<String, Integer> lootMap) {
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(lootMap.entrySet());
        entries.sort((left, right) -> {
            int cmp = Integer.compare(right.getValue(), left.getValue());
            if (cmp != 0) {
                return cmp;
            }
            return left.getKey().compareTo(right.getKey());
        });
        return entries;
    }

    private static String formatTranslatedIdentifier(ResourceLocation id, String type) {
        String translationKey = type + "." + id.getNamespace() + "." + id.getPath().replace('/', '.');
        if (Language.getInstance().has(translationKey)) {
            return Component.translatable(translationKey).getString();
        }
        return formatReadableIdentifier(id);
    }

    private static String formatReadableIdentifier(ResourceLocation id) {
        String readablePath = id.getPath().replace('_', ' ');
        if ("minecraft".equals(id.getNamespace())) {
            return readablePath;
        }
        return id.getNamespace() + ":" + readablePath;
    }
}