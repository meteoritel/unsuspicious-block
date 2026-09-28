package com.meteorite.unsuspiciousblock.client.ui.screen;

import com.google.gson.JsonElement;
import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.client.ui.support.ClientLootTableLanguageStore;
import com.meteorite.unsuspiciousblock.client.ui.support.LootTableManagementClientState;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableNames;
import com.meteorite.unsuspiciousblock.network.payload.c2s.RequestLootTableManagementPayload;
import com.meteorite.unsuspiciousblock.network.payload.c2s.UpdateLootTableTranslationsPayload;
import com.meteorite.unsuspiciousblock.network.payload.c2s.UpdateTrackedLootTablePayload;
import com.meteorite.unsuspiciousblock.network.payload.s2c.SyncLootTableManagementPayload;
import com.meteorite.unsuspiciousblock.platform.Services;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 战利品表管理页的客户端动作层。
 * <p>
 * 承接协议组包、原生文件对话框与导入流程，使 {@link LootTableManagementScreen} 只保留绘制、
 * 控件状态与转调。语言 JSON 的读取与文件级校验在 {@link LootTableImportValidator} 完成，
 * 这里只做"与当前连接快照对照后生成预览"的编排；失败原因分类交给 Screen 挑文案，异常在本层记录日志。
 */
final class LootTableManagementActions {
    /** 单条本地化名称长度上限，与服务端 payload 约定一致。 */
    private static final int MAX_NAME_LENGTH = 128;

    /** 请求服务端下发管理页快照。 */
    void requestSnapshot() {
        Services.NETWORK.sendToServer(new RequestLootTableManagementPayload());
    }

    /** 提交追踪状态切换。 */
    void submitTracking(ResourceLocation tableId, boolean tracked) {
        Services.NETWORK.sendToServer(new UpdateTrackedLootTablePayload(tableId, tracked));
    }

    /** 提交本地化名称草稿：Screen 只把草稿转成提交项，协议组包在这里。 */
    void submitNameDrafts(List<NameDraft> drafts) {
        List<UpdateLootTableTranslationsPayload.Entry> entries = new ArrayList<>(drafts.size());
        for (NameDraft draft : drafts) {
            entries.add(new UpdateLootTableTranslationsPayload.Entry(
                    draft.tableId(), draft.languageCode(), draft.value()));
        }
        Services.NETWORK.sendToServer(new UpdateLootTableTranslationsPayload(entries));
    }

    /** 提交已确认的导入预览；空预览不发包。 */
    void submitImport(ImportPreview preview) {
        if (preview.entries().isEmpty()) return;
        Services.NETWORK.sendToServer(new UpdateLootTableTranslationsPayload(preview.entries()));
    }

    /** 原生文件选择框；返回 null 表示用户取消。 */
    @Nullable
    String chooseImportFile() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer filters = stack.mallocPointer(1);
            filters.put(stack.UTF8("*.json")).flip();
            return TinyFileDialogs.tinyfd_openFileDialog(
                    Component.translatable("screen.unsuspiciousblock.loot_table_management.import_dialog").getString(),
                    Services.PLATFORM.getGameDir().toAbsolutePath().toString(), filters, "JSON", false);
        }
    }

    // 读取并校验导入文件，再与当前连接快照对照生成预览；失败原因分类并记录日志
    ImportResult prepareImport(Path file, String fallbackLanguageCode) {
        LootTableImportValidator.ParsedImport parsed;
        try {
            parsed = LootTableImportValidator.read(file, fallbackLanguageCode);
        } catch (LootTableImportValidator.ImportException exception) {
            Constants.LOG.warn("战利品表语言导入失败：{}（原因 {}，详情 {}）",
                    file, exception.failure(), exception.detail(), exception);
            return new ImportResult.Failed(exception.failure());
        }
        return new ImportResult.Ready(buildPreview(parsed));
    }

    // 预览统计与当前服务端条目、资源包翻译对照：资源包已有译名的不覆盖，未知/歧义键计入未匹配
    private ImportPreview buildPreview(LootTableImportValidator.ParsedImport parsed) {
        String languageCode = parsed.languageCode();
        Map<String, ResourceLocation> tableByKey = new LinkedHashMap<>();
        Set<String> ambiguousKeys = new HashSet<>();
        for (SyncLootTableManagementPayload.Entry entry : LootTableManagementClientState.entries()) {
            String key = LootTableNames.createTranslationKey(entry.tableId());
            if (tableByKey.putIfAbsent(key, entry.tableId()) != null) {
                ambiguousKeys.add(key);
            }
        }

        List<UpdateLootTableTranslationsPayload.Entry> accepted = new ArrayList<>();
        int added = 0;
        int updated = 0;
        int resourceSkipped = 0;
        int unmatched = 0;
        int invalid = 0;
        for (Map.Entry<String, JsonElement> jsonEntry : parsed.entries().entrySet()) {
            ResourceLocation tableId = tableByKey.get(jsonEntry.getKey());
            if (tableId == null || ambiguousKeys.contains(jsonEntry.getKey())) {
                unmatched++;
                continue;
            }
            if (!jsonEntry.getValue().isJsonPrimitive()
                    || !jsonEntry.getValue().getAsJsonPrimitive().isString()) {
                invalid++;
                continue;
            }
            String value = jsonEntry.getValue().getAsString().trim();
            if (value.isEmpty() || value.length() > MAX_NAME_LENGTH) {
                invalid++;
                continue;
            }
            String translationKey = LootTableNames.createTranslationKey(tableId);
            if (ClientLootTableLanguageStore.findResourceTranslation(languageCode, translationKey) != null) {
                resourceSkipped++;
                continue;
            }
            if (storedName(languageCode, translationKey).isEmpty()) {
                added++;
            } else {
                updated++;
            }
            accepted.add(new UpdateLootTableTranslationsPayload.Entry(tableId, languageCode, value));
        }
        return new ImportPreview(languageCode, List.copyOf(accepted),
                added, updated, resourceSkipped, unmatched, invalid);
    }

    private static String storedName(String languageCode, String translationKey) {
        return LootTableManagementClientState.translations()
                .getOrDefault(languageCode, Map.of()).getOrDefault(translationKey, "");
    }

    /** 待提交的本地化名称草稿：语言码 + 表 id + 名称。 */
    record NameDraft(String languageCode, ResourceLocation tableId, String value) {
    }

    /** JSON 导入预览及其可提交条目。 */
    record ImportPreview(String languageCode, List<UpdateLootTableTranslationsPayload.Entry> entries,
                         int added, int updated, int resourceSkipped, int unmatched, int invalid) {
    }

    /** 导入准备结果：成功给预览，失败给可映射到文案键的原因（异常已在本层记录）。 */
    sealed interface ImportResult {
        record Ready(ImportPreview preview) implements ImportResult {
        }

        record Failed(LootTableImportValidator.Failure reason) implements ImportResult {
        }
    }
}
