package com.meteorite.unsuspiciousblock.client.state;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.meteorite.unsuspiciousblock.client.ui.support.JournalUiPreferencesStore;
import com.meteorite.unsuspiciousblock.core.loottable.simulation.ScenarioParams;
import com.meteorite.unsuspiciousblock.core.loottable.simulation.SimulationInput;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 按存档与玩家保存每表结构化选择（已勾选条件集 + 装备与幸运参数）；恢复时由当前目录再次校验。 */
public final class SimulationPreferenceStore {
    private SimulationPreferenceStore() {
    }

    /** 同一张表的当前条件勾选集合与参数。 */
    public record Selection(List<String> conditions, ScenarioParams params) {
        public Selection {
            conditions = SimulationInput.canonicalize(conditions);
        }
    }

    /** 读取已保存的单表选择；不存在或格式损坏时返回 {@code null}。 */
    public static @Nullable Selection get(ResourceLocation table) {
        String json = JournalUiPreferencesStore.getSimulationPreference(table.toString());
        if (json == null) {
            return null;
        }
        try {
            JsonObject object = JsonParser.parseString(json).getAsJsonObject();
            List<String> conditions = new ArrayList<>();
            if (object.has("conditions") && object.get("conditions").isJsonArray()) {
                for (JsonElement element : object.getAsJsonArray("conditions")) {
                    if (element.isJsonPrimitive()) {
                        conditions.add(element.getAsString());
                    }
                }
            }
            Map<ResourceLocation, Integer> levels = new LinkedHashMap<>();
            if (object.has("enchantments") && object.get("enchantments").isJsonObject()) {
                object.getAsJsonObject("enchantments").entrySet().forEach(entry ->
                        levels.put(ResourceLocation.parse(entry.getKey()), entry.getValue().getAsInt()));
            }
            return new Selection(conditions, new ScenarioParams(
                    object.get("luck").getAsFloat(),
                    ResourceLocation.parse(object.get("tool").getAsString()),
                    levels,
                    object.get("samples").getAsInt()));
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /** 保存单表当前条件勾选集合与参数。 */
    public static void put(ResourceLocation table, Selection selection) {
        JsonObject object = new JsonObject();
        JsonArray conditions = new JsonArray();
        for (String id : selection.conditions()) {
            conditions.add(id);
        }
        object.add("conditions", conditions);
        object.addProperty("luck", selection.params().luck());
        object.addProperty("tool", selection.params().toolId().toString());
        object.addProperty("samples", selection.params().sampleCount());
        JsonObject levels = new JsonObject();
        selection.params().toolEnchantments().forEach((id, value) -> levels.addProperty(id.toString(), value));
        object.add("enchantments", levels);
        JournalUiPreferencesStore.setSimulationPreference(table.toString(), object.toString());
    }

    /** 若存在脏数据则立即刷盘。 */
    public static void flush() {
        JournalUiPreferencesStore.flushIfDirty();
    }
}
