package com.meteorite.unsuspiciousblock.client.state;
import com.google.gson.*;
import com.meteorite.unsuspiciousblock.loottable.simulation.ScenarioParams;
import com.meteorite.unsuspiciousblock.client.ui.support.JournalUiPreferencesStore;
import net.minecraft.resources.ResourceLocation;
import java.util.*;
/** 按存档与玩家保存每表结构化选择；恢复时由当前目录再次校验，不读取旧全局配置。 */
public final class SimulationPreferenceStore {
    private SimulationPreferenceStore() {}
    /** 同一张表的所有场景共用一套参数。 */
    public record Selection(String scene, ScenarioParams params) {}
    public static Selection get(ResourceLocation table) {
        String json = JournalUiPreferencesStore.getSimulationPreference(table.toString());
        if (json == null) return null;
        try {
            JsonObject object = JsonParser.parseString(json).getAsJsonObject();
            Map<ResourceLocation, Integer> levels = new LinkedHashMap<>();
            object.getAsJsonObject("enchantments").entrySet().forEach(e ->
                    levels.put(ResourceLocation.parse(e.getKey()), e.getValue().getAsInt()));
            return new Selection(object.get("scene").getAsString(), new ScenarioParams(
                    object.get("luck").getAsFloat(), ResourceLocation.parse(object.get("tool").getAsString()),
                    levels, object.get("samples").getAsInt()));
        } catch (RuntimeException ignored) { return null; }
    }
    public static void put(ResourceLocation table, Selection selection) {
        JsonObject object = new JsonObject();
        object.addProperty("scene", selection.scene());
        object.addProperty("luck", selection.params().luck());
        object.addProperty("tool", selection.params().toolId().toString());
        object.addProperty("samples", selection.params().sampleCount());
        JsonObject levels = new JsonObject();
        selection.params().toolEnchantments().forEach((id, value) -> levels.addProperty(id.toString(), value));
        object.add("enchantments", levels);
        JournalUiPreferencesStore.setSimulationPreference(table.toString(), object.toString());
    }
    public static void flush() { JournalUiPreferencesStore.flushIfDirty(); }
}
