package com.meteorite.unsuspiciousblock.client.state;
import com.google.gson.*;
import com.meteorite.unsuspiciousblock.loottable.simulation.ScenarioParams;
import com.meteorite.unsuspiciousblock.platform.Services;
import com.meteorite.unsuspiciousblock.platform.services.IClientSimulationPreference;
import net.minecraft.resources.ResourceLocation;
import java.util.*;
/** 每表保存结构化选择；恢复时由当前目录再次校验，不盲信旧 inputKey。 */
public final class SimulationPreferenceStore {
    private static final IClientSimulationPreference STORAGE = Services.load(IClientSimulationPreference.class);
    private static final Map<String, String> VALUES = new LinkedHashMap<>(STORAGE.load());
    private static boolean dirty;
    private SimulationPreferenceStore() {}
    public record Selection(String scene, ScenarioParams params) {}
    public static Selection get(ResourceLocation table) {
        String json = VALUES.get(table.toString());
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
        VALUES.put(table.toString(), object.toString());
        while (VALUES.size() > 1024) VALUES.remove(VALUES.keySet().iterator().next());
        dirty = true;
    }
    public static void flush() { if (dirty) { STORAGE.save(VALUES); dirty = false; } }
}

