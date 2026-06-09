package com.meteorite.unsuspiciousblock.client.ui.helper;

import com.meteorite.unsuspiciousblock.util.GameTimeFormatHelper;
import com.meteorite.unsuspiciousblock.journal.state.TriggerType;
import com.meteorite.unsuspiciousblock.loottable.LootResultSignature;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
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
        return Component.translatable(key, parts.day(), parts.hour(), parts.minute());
    }

    public static Component formatTriggerType(@Nullable TriggerType triggerType) {
        return (triggerType != null ? triggerType : TriggerType.UNKNOWN).displayName();
    }

    public static String formatBlockName(@Nullable ResourceLocation blockId) {
        if (blockId == null) {
            return Component.translatable("screen.unsuspiciousblock.archaeology_journal.unknown_source_block").getString();
        }
        Item item = BuiltInRegistries.ITEM.get(blockId);
        if (item != Items.AIR || blockId.equals(ResourceLocation.withDefaultNamespace("air"))) {
            return new ItemStack(item).getHoverName().getString();
        }
        return formatTranslatedIdentifier(blockId, "block");
    }

    public static String formatStructureName(@Nullable ResourceLocation id) {
        if (id == null) {
            return Component.translatable("screen.unsuspiciousblock.archaeology_journal.unknown_structure").getString();
        }
        return formatTranslatedIdentifier(id, "structure");
    }

    public static String formatBiomeName(@Nullable ResourceLocation id) {
        if (id == null) {
            return Component.translatable("screen.unsuspiciousblock.archaeology_journal.unknown_biome").getString();
        }
        return formatTranslatedIdentifier(id, "biome");
    }

    public static ItemStack createSourceStack(@Nullable ResourceLocation sourceBlockId) {
        if (sourceBlockId == null) {
            return ItemStack.EMPTY;
        }
        Item item = BuiltInRegistries.ITEM.get(sourceBlockId);
        if (item == Items.AIR && !sourceBlockId.equals(ResourceLocation.withDefaultNamespace("air"))) {
            return ItemStack.EMPTY;
        }
        return new ItemStack(item);
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