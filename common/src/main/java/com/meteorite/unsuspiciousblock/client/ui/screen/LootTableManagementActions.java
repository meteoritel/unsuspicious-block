package com.meteorite.unsuspiciousblock.client.ui.screen;

import com.google.gson.JsonElement;
import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.client.ui.support.ClientLootTableLanguageStore;
import com.meteorite.unsuspiciousblock.client.ui.support.LootTableManagementClientState;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.LootTableNameEdit;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.LootTableNames;
import com.meteorite.unsuspiciousblock.platform.Services;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 导入文件与当前表 ID、名称来源对照，生成可确认的逐项结果。 */
final class LootTableManagementActions {
    private LootTableManagementActions() {}

    /** 原生文件选择框；返回 null 表示用户取消。 */
    @Nullable
    static String chooseImportFile() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer filters = stack.mallocPointer(1);
            filters.put(stack.UTF8("*.json")).flip();
            return TinyFileDialogs.tinyfd_openFileDialog(
                    Component.translatable("screen.unsuspiciousblock.loot_table_management.import_dialog").getString(),
                    Services.PLATFORM.getGameDir().toAbsolutePath().toString(), filters, "JSON", false);
        }
    }

    static ImportResult prepareImport(Path file, String suggestedLanguage) {
        try {
            return new ImportResult.Ready(LootTableImportValidator.read(file, suggestedLanguage));
        } catch (LootTableImportValidator.ImportException exception) {
            Constants.LOG.warn("{}导入战利品表名称失败 {} ({})", Constants.LOG_TAG, file, exception.failure(), exception);
            return new ImportResult.Failed(exception.failure());
        }
    }

    static List<ImportRow> preview(Map<String, JsonElement> values, String language,
                                   Map<String, ResourceLocation> resolvedIds) {
        Set<ResourceLocation> registered = new HashSet<>();
        Map<String, List<ResourceLocation>> byLegacyKey = new HashMap<>();
        for (var entry : LootTableManagementClientState.entries()) {
            ResourceLocation id = entry.tableId();
            registered.add(id);
            byLegacyKey.computeIfAbsent(LootTableNames.createTranslationKey(id), ignored -> new ArrayList<>()).add(id);
        }
        List<ImportRow> rows = new ArrayList<>();
        Set<ResourceLocation> assigned = new HashSet<>();
        for (var entry : values.entrySet()) {
            String key = entry.getKey();
            List<ResourceLocation> candidates = byLegacyKey.getOrDefault(key, List.of());
            ResourceLocation id = resolvedIds.get(key);
            if (id == null && candidates.size() > 1) {
                rows.add(new ImportRow(key, ImportStatus.AMBIGUOUS, null, candidates.stream().sorted().toList()));
                continue;
            }
            if (id == null) id = candidates.size() == 1 ? candidates.getFirst() : ResourceLocation.tryParse(key);
            if (id == null || !registered.contains(id)
                    || candidates.isEmpty() && !resolvedIds.containsKey(key) && !key.equals(id.toString())) {
                rows.add(new ImportRow(key, ImportStatus.UNMATCHED, null, List.of()));
                continue;
            }
            JsonElement element = entry.getValue();
            String value = element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()
                    ? element.getAsString().trim() : "";
            if (value.isEmpty() || !LootTableNameEdit.isName(value) || !assigned.add(id)) {
                rows.add(new ImportRow(key, ImportStatus.INVALID, null, List.of()));
                continue;
            }
            String current = ClientLootTableLanguageStore.serverTranslation(language, id);
            var name = ClientLootTableLanguageStore.resolve(id, language);
            ImportStatus status = name.source() == ClientLootTableLanguageStore.Source.RESOURCE ? ImportStatus.RESOURCE
                    : current.equals(value) ? ImportStatus.UNCHANGED : current.isEmpty() ? ImportStatus.ADD : ImportStatus.UPDATE;
            rows.add(new ImportRow(id.toString(), status,
                    new LootTableNameEdit(id, language, value, current), List.of()));
        }
        return rows.stream().sorted(Comparator.comparing(ImportRow::status)).toList();
    }

    enum ImportStatus { AMBIGUOUS, INVALID, UNMATCHED, RESOURCE, ADD, UPDATE, UNCHANGED }

    record ImportRow(String key, ImportStatus status, @Nullable LootTableNameEdit edit, List<ResourceLocation> candidates) {}

    sealed interface ImportResult {
        record Ready(LootTableImportValidator.ParsedImport parsed) implements ImportResult {}
        record Failed(LootTableImportValidator.Failure reason) implements ImportResult {}
    }
}
