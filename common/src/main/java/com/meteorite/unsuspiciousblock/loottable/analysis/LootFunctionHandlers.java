package com.meteorite.unsuspiciousblock.loottable.analysis;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.meteorite.unsuspiciousblock.loottable.signature.LootResultSignature;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.world.item.component.ItemLore;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 战利品函数处理器注册表——管理所有已知 loot function 的静态解析逻辑。
 * <p>
 * 内建全部原版 1.21.1 的 40 个 loot function 处理器。
 * 外部模组可通过 {@link #register} 注册自定义 function 的处理器。
 * 未知 function 在查询时返回 null，由调用方标记为条件 + 近似签名。
 */
public final class LootFunctionHandlers {
    private static final Map<String, LootFunctionHandler> REGISTRY = new LinkedHashMap<>();
    private static final ResourceLocation BOOK_ID = ResourceLocation.fromNamespaceAndPath("minecraft", "book");

    /** 返回 null 且 addsRandomness=true 的通用 handler，用于无法静态求值的 function */
    private static final LootFunctionHandler NULL_RANDOM = new LootFunctionHandler() {
        @Override
        @Nullable
        public ItemStack apply(ItemStack previewStack, JsonObject functionJson) {
            return null;
        }

        @Override
        public boolean addsRandomness() {
            return true;
        }
    };

    static {
        // 类别 A：完整静态处理
        register("set_item", new SetItemHandler());
        register("set_components", new SetComponentsHandler());
        register("set_custom_data", new SetCustomDataHandler());
        register("set_name", new SetNameHandler());
        register("set_potion", new SetPotionHandler());

        // 类别 B：附魔类
        register("enchant_randomly", new EnchantRandomlyHandler());
        register("enchant_with_levels", new EnchantWithLevelsHandler());
        register("set_enchantments", new SetEnchantmentsHandler());
        register("enchanted_count_increase", new EnchantedCountIncreaseHandler());

        // 类别 C：可部分静态处理
        register("set_count", new SetCountHandler());
        register("set_damage", new SetDamageHandler());
        register("set_custom_model_data", new SetCustomModelDataHandler());
        register("set_lore", new SetLoreHandler());
        register("set_attributes", new SetAttributesHandler());
        register("toggle_tooltips", new ToggleTooltipsHandler());
        register("set_ominous_bottle_amplifier", new SetOminousBottleAmplifierHandler());

        // 类别 D：影响结果但无法静态求值
        register("copy_components", NULL_RANDOM);
        register("copy_custom_data", NULL_RANDOM);
        register("copy_name", NULL_RANDOM);
        register("copy_state", NULL_RANDOM);
        register("set_instrument", NULL_RANDOM);
        register("set_fireworks", NULL_RANDOM);
        register("set_firework_explosion", NULL_RANDOM);
        register("set_banner_pattern", NULL_RANDOM);
        register("set_book_cover", NULL_RANDOM);
        register("set_written_book_pages", NULL_RANDOM);
        register("set_writable_book_pages", NULL_RANDOM);
        register("fill_player_head", NULL_RANDOM);
        register("set_stew_effect", NULL_RANDOM);
        register("exploration_map", NULL_RANDOM);
        register("furnace_smelt", NULL_RANDOM);
        register("set_contents", NULL_RANDOM);
        register("modify_contents", NULL_RANDOM);
        register("set_loot_table", NULL_RANDOM);

        // 类别 E：数量/概率类
        register("limit_count", NULL_RANDOM);
        register("apply_bonus", NULL_RANDOM);
        register("explosion_decay", NULL_RANDOM);

        // 类别 F：元函数
        register("filtered", NULL_RANDOM);
        register("reference", NULL_RANDOM);
        register("sequence", NULL_RANDOM);
    }

    private LootFunctionHandlers() {
    }

    /**
     * 注册自定义 loot function 处理器。
     * 若已存在同名 handler 则覆盖（允许模组替换默认行为）。
     */
    public static void register(String functionName, LootFunctionHandler handler) {
        REGISTRY.put(functionName, handler);
    }

    /**
     * 获取指定 function 的处理器。
     *
     * @param functionName 已去除命名空间前缀的 function 名（如 "set_count"）
     * @return 对应的 handler；未注册时返回 null
     */
    @Nullable
    public static LootFunctionHandler get(String functionName) {
        return REGISTRY.get(functionName);
    }

    /**
     * 获取注册表只读视图。
     */
    public static Map<String, LootFunctionHandler> registry() {
        return Collections.unmodifiableMap(REGISTRY);
    }

    // ==================== 共享工具方法 ====================

    private static ResourceLocation itemIdOf(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem());
    }

    // 若预览栈为普通书，返回升级后的附魔书；否则返回原栈
    private static ItemStack promoteBookPreviewIfNeeded(ItemStack stack) {
        if (BOOK_ID.equals(itemIdOf(stack))) {
            return stack.transmuteCopy(Items.ENCHANTED_BOOK);
        }
        return stack;
    }

    @Nullable
    private static ResourceLocation parseFunctionItemId(JsonObject functionObject) {
        ResourceLocation itemId = ResourceLocation.tryParse(LootParseUtil.getString(functionObject, "item", ""));
        if (itemId != null) {
            return itemId;
        }
        return ResourceLocation.tryParse(LootParseUtil.getString(functionObject, "name", ""));
    }

    // ==================== 类别 A：完整静态处理 ====================

    /** 处理 set_item：将物品替换为指定物品 */
    private static final class SetItemHandler implements LootFunctionHandler {
        @Override
        @Nullable
        public ItemStack apply(ItemStack previewStack, JsonObject functionJson) {
            ResourceLocation functionItemId = parseFunctionItemId(functionJson);
            if (functionItemId != null && BuiltInRegistries.ITEM.get(functionItemId) != Items.AIR) {
                return previewStack.transmuteCopy(BuiltInRegistries.ITEM.get(functionItemId));
            }
            return null;
        }

        @Override
        public LootResultSignature deriveSignature(ItemStack previewStack, ResourceLocation itemId) {
            return LootResultSignature.plain(itemIdOf(previewStack));
        }

        @Override
        public boolean addsRandomness() {
            return false;
        }
    }

    /** 处理 set_components：应用精确 DataComponentPatch */
    private static final class SetComponentsHandler implements LootFunctionHandler {
        @Override
        @Nullable
        public ItemStack apply(ItemStack previewStack, JsonObject functionJson) {
            JsonElement element = functionJson.get("components");
            if (element == null || element.isJsonNull()) {
                return previewStack;
            }
            DataComponentPatch patch = DataComponentPatch.CODEC.parse(JsonOps.INSTANCE, element)
                    .result()
                    .orElse(null);
            if (patch == null) {
                return null;
            }
            try {
                previewStack.applyComponentsAndValidate(patch);
                return previewStack;
            } catch (RuntimeException exception) {
                return null;
            }
        }

        @Override
        public boolean addsRandomness() {
            return false;
        }
    }

    /** 处理 set_custom_data：合并自定义 NBT 数据 */
    private static final class SetCustomDataHandler implements LootFunctionHandler {
        @Override
        @Nullable
        public ItemStack apply(ItemStack previewStack, JsonObject functionJson) {
            JsonElement element = functionJson.get("tag");
            if (element == null || element.isJsonNull()) {
                return previewStack;
            }
            CompoundTag tag = TagParser.LENIENT_CODEC.parse(JsonOps.INSTANCE, element)
                    .result()
                    .orElse(null);
            if (tag == null) {
                return null;
            }
            CustomData.update(DataComponents.CUSTOM_DATA, previewStack, data -> data.merge(tag));
            return previewStack;
        }

        @Override
        public boolean addsRandomness() {
            return false;
        }
    }

    /** 处理 set_name：设置自定义名称或物品名称 */
    private static final class SetNameHandler implements LootFunctionHandler {
        @Override
        @Nullable
        public ItemStack apply(ItemStack previewStack, JsonObject functionJson) {
            if (functionJson.has("entity")) {
                return null;
            }
            JsonElement nameElement = functionJson.get("name");
            if (nameElement == null || nameElement.isJsonNull()) {
                return previewStack;
            }
            Component component = ComponentSerialization.CODEC.parse(JsonOps.INSTANCE, nameElement)
                    .result()
                    .orElse(null);
            if (component == null) {
                return null;
            }
            String target = LootParseUtil.getString(functionJson, "target", "custom_name");
            return switch (target) {
                case "custom_name" -> {
                    previewStack.set(DataComponents.CUSTOM_NAME, component);
                    yield previewStack;
                }
                case "item_name" -> {
                    previewStack.set(DataComponents.ITEM_NAME, component);
                    yield previewStack;
                }
                default -> null;
            };
        }

        @Override
        public boolean addsRandomness() {
            return false;
        }
    }

    /** 处理 set_potion：写入药水内容组件 */
    private static final class SetPotionHandler implements LootFunctionHandler {
        @Override
        @Nullable
        public ItemStack apply(ItemStack previewStack, JsonObject functionJson) {
            JsonElement idElement = functionJson.get("id");
            if (idElement == null || idElement.isJsonNull()) {
                return null;
            }
            Holder<Potion> potionHolder = Potion.CODEC.parse(JsonOps.INSTANCE, idElement)
                    .result()
                    .orElse(null);
            if (potionHolder == null) {
                return null;
            }
            previewStack.set(DataComponents.POTION_CONTENTS, new PotionContents(potionHolder));
            return previewStack;
        }

        @Override
        public boolean addsRandomness() {
            return false;
        }
    }

    // ==================== 类别 B：附魔类 ====================

    /** 处理 enchant_randomly：随机附魔 */
    private static final class EnchantRandomlyHandler implements LootFunctionHandler {
        @Override
        @Nullable
        public ItemStack apply(ItemStack previewStack, JsonObject functionJson) {
            return promoteBookPreviewIfNeeded(previewStack);
        }

        @Override
        public LootResultSignature deriveSignature(ItemStack previewStack, ResourceLocation itemId) {
            return LootResultSignature.enchantedApprox(itemIdOf(previewStack));
        }

        @Override
        public boolean addsRandomness() {
            return false;
        }
    }

    /** 处理 enchant_with_levels：等级附魔 */
    private static final class EnchantWithLevelsHandler implements LootFunctionHandler {
        @Override
        @Nullable
        public ItemStack apply(ItemStack previewStack, JsonObject functionJson) {
            return promoteBookPreviewIfNeeded(previewStack);
        }

        @Override
        public LootResultSignature deriveSignature(ItemStack previewStack, ResourceLocation itemId) {
            return LootResultSignature.enchantedApprox(itemIdOf(previewStack));
        }

        @Override
        public boolean addsRandomness() {
            return false;
        }
    }

    /** 处理 set_enchantments：设置附魔（含随机性） */
    private static final class SetEnchantmentsHandler implements LootFunctionHandler {
        @Override
        @Nullable
        public ItemStack apply(ItemStack previewStack, JsonObject functionJson) {
            return promoteBookPreviewIfNeeded(previewStack);
        }

        @Override
        public LootResultSignature deriveSignature(ItemStack previewStack, ResourceLocation itemId) {
            return LootResultSignature.enchantedApprox(itemIdOf(previewStack));
        }

        @Override
        public boolean addsRandomness() {
            return true;
        }
    }

    /** 处理 enchanted_count_increase：附魔等级影响数量（此前被 isEnchantLikeFunction 启发式捕获） */
    private static final class EnchantedCountIncreaseHandler implements LootFunctionHandler {
        @Override
        @Nullable
        public ItemStack apply(ItemStack previewStack, JsonObject functionJson) {
            return promoteBookPreviewIfNeeded(previewStack);
        }

        @Override
        public LootResultSignature deriveSignature(ItemStack previewStack, ResourceLocation itemId) {
            return LootResultSignature.enchantedApprox(itemIdOf(previewStack));
        }

        @Override
        public boolean addsRandomness() {
            return true;
        }
    }

    // ==================== 类别 C：可部分静态处理 ====================

    /**
     * 处理 set_count：设置物品数量。
     * 若 count 为固定值则直接应用；若为范围则返回 null（标记条件）。
     */
    private static final class SetCountHandler implements LootFunctionHandler {
        @Override
        @Nullable
        public ItemStack apply(ItemStack previewStack, JsonObject functionJson) {
            JsonElement countElement = functionJson.get("count");
            if (countElement == null) {
                return null;
            }
            if (countElement.isJsonPrimitive() && countElement.getAsJsonPrimitive().isNumber()) {
                previewStack.setCount(countElement.getAsInt());
                return previewStack;
            }
            // count 为范围对象（min/max），无法静态确定
            return null;
        }

        @Override
        @Nullable
        public Component describeHint(JsonObject functionJson) {
            JsonElement countElement = functionJson.get("count");
            if (countElement == null || !countElement.isJsonObject()) {
                return null;
            }
            JsonObject countObj = countElement.getAsJsonObject();
            int min, max;
            if (countObj.has("min") && countObj.has("max")) {
                min = countObj.get("min").getAsInt();
                max = countObj.get("max").getAsInt();
            } else if (countObj.has("n")) {
                // binomial 分布：0 到 n
                min = 0;
                max = countObj.get("n").getAsInt();
            } else {
                return null;
            }
            if (min == max) {
                return null;
            }
            return Component.translatable(
                    "screen.unsuspiciousblock.archaeology_journal.item_hint.set_count_range", min, max);
        }

        @Override
        public boolean addsRandomness() {
            return false;
        }
    }

    /** 处理 set_damage：设置物品耐久损伤 */
    private static final class SetDamageHandler implements LootFunctionHandler {
        @Override
        @Nullable
        public ItemStack apply(ItemStack previewStack, JsonObject functionJson) {
            JsonElement damageElement = functionJson.get("damage");
            if (damageElement == null) {
                return null;
            }
            if (damageElement.isJsonPrimitive() && damageElement.getAsJsonPrimitive().isNumber()) {
                int maxDamage = previewStack.getMaxDamage();
                if (maxDamage > 0) {
                    float fraction = damageElement.getAsFloat() / maxDamage;
                    previewStack.setDamageValue(Math.round(fraction * maxDamage));
                }
                return previewStack;
            }
            return null;
        }

        @Override
        @Nullable
        public Component describeHint(JsonObject functionJson) {
            JsonElement damageElement = functionJson.get("damage");
            if (damageElement == null || !damageElement.isJsonObject()) {
                return null;
            }
            JsonObject damageObj = damageElement.getAsJsonObject();
            float min, max;
            if (damageObj.has("min") && damageObj.has("max")) {
                min = damageObj.get("min").getAsFloat();
                max = damageObj.get("max").getAsFloat();
            } else {
                return null;
            }
            if (min == max) {
                return null;
            }
            return Component.translatable(
                    "screen.unsuspiciousblock.archaeology_journal.item_hint.set_damage_range",
                    Math.round(min * 100), Math.round(max * 100));
        }

        @Override
        public boolean addsRandomness() {
            return false;
        }
    }

    /** 处理 set_custom_model_data：设置自定义模型数据 */
    private static final class SetCustomModelDataHandler implements LootFunctionHandler {
        @Override
        @Nullable
        public ItemStack apply(ItemStack previewStack, JsonObject functionJson) {
            JsonElement valueElement = functionJson.get("value");
            if (valueElement != null && valueElement.isJsonPrimitive()
                    && valueElement.getAsJsonPrimitive().isNumber()) {
                previewStack.set(DataComponents.CUSTOM_MODEL_DATA,
                        new CustomModelData(valueElement.getAsInt()));
                return previewStack;
            }
            // 颜色数组或字符串等复杂值，无法静态确定
            return null;
        }

        @Override
        public boolean addsRandomness() {
            return false;
        }
    }

    /** 处理 set_lore：设置物品描述 */
    private static final class SetLoreHandler implements LootFunctionHandler {
        @Override
        @Nullable
        public ItemStack apply(ItemStack previewStack, JsonObject functionJson) {
            JsonElement loreElement = functionJson.get("lore");
            if (loreElement == null || !loreElement.isJsonArray()) {
                return null;
            }
            List<Component> loreList = new ArrayList<>();
            for (JsonElement element : loreElement.getAsJsonArray()) {
                ComponentSerialization.CODEC
                        .parse(JsonOps.INSTANCE, element)
                        .result().ifPresent(loreList::add);
            }
            if (!loreList.isEmpty()) {
                previewStack.set(DataComponents.LORE, new ItemLore(loreList));
                return previewStack;
            }
            return null;
        }

        @Override
        public boolean addsRandomness() {
            return false;
        }
    }

    /** 处理 set_attributes：设置属性修饰符（含随机范围） */
    private static final class SetAttributesHandler implements LootFunctionHandler {
        @Override
        @Nullable
        public ItemStack apply(ItemStack previewStack, JsonObject functionJson) {
            return null;
        }

        @Override
        public boolean addsRandomness() {
            return true;
        }
    }

    /** 处理 toggle_tooltips：切换工具提示可见性 */
    private static final class ToggleTooltipsHandler implements LootFunctionHandler {
        @Override
        @Nullable
        public ItemStack apply(ItemStack previewStack, JsonObject functionJson) {
            return previewStack;
        }

        @Override
        public boolean addsRandomness() {
            return false;
        }
    }

    /** 处理 set_ominous_bottle_amplifier：设置不祥之瓶增幅 */
    private static final class SetOminousBottleAmplifierHandler implements LootFunctionHandler {
        @Override
        @Nullable
        public ItemStack apply(ItemStack previewStack, JsonObject functionJson) {
            JsonElement amplifierElement = functionJson.get("amplifier");
            if (amplifierElement != null && amplifierElement.isJsonPrimitive()
                    && amplifierElement.getAsJsonPrimitive().isNumber()) {
                previewStack.set(DataComponents.OMINOUS_BOTTLE_AMPLIFIER, amplifierElement.getAsInt());
                return previewStack;
            }
            return null;
        }

        @Override
        public boolean addsRandomness() {
            return false;
        }
    }

    }