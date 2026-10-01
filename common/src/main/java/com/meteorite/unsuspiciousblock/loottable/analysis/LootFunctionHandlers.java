package com.meteorite.unsuspiciousblock.loottable.analysis;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.level.storage.loot.IntRange;
import net.minecraft.world.level.storage.loot.functions.ApplyBonusCount;
import net.minecraft.world.level.storage.loot.functions.ExplorationMapFunction;
import net.minecraft.world.level.storage.loot.functions.LimitCount;
import net.minecraft.world.level.storage.loot.functions.LootItemFunction;
import net.minecraft.world.level.storage.loot.functions.SetBookCoverFunction;
import net.minecraft.world.level.storage.loot.functions.SetComponentsFunction;
import net.minecraft.world.level.storage.loot.functions.SetCustomDataFunction;
import net.minecraft.world.level.storage.loot.functions.SetCustomModelDataFunction;
import net.minecraft.world.level.storage.loot.functions.SetFireworksFunction;
import net.minecraft.world.level.storage.loot.functions.SetItemCountFunction;
import net.minecraft.world.level.storage.loot.functions.SetItemDamageFunction;
import net.minecraft.world.level.storage.loot.functions.SetItemFunction;
import net.minecraft.world.level.storage.loot.functions.SetLoreFunction;
import net.minecraft.world.level.storage.loot.functions.SetNameFunction;
import net.minecraft.world.level.storage.loot.functions.SetOminousBottleAmplifierFunction;
import net.minecraft.world.level.storage.loot.functions.SetPotionFunction;
import net.minecraft.world.level.storage.loot.functions.SetStewEffectFunction;
import net.minecraft.world.level.storage.loot.providers.number.ConstantValue;
import net.minecraft.world.level.storage.loot.providers.number.NumberProvider;
import net.minecraft.world.level.storage.loot.providers.number.UniformGenerator;
import com.meteorite.unsuspiciousblock.loottable.signature.LootResultSignature;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.meteorite.unsuspiciousblock.loottable.analysis.LootFunctionDescribeSupport.append;
import static com.meteorite.unsuspiciousblock.loottable.analysis.LootFunctionDescribeSupport.arrayOf;
import static com.meteorite.unsuspiciousblock.loottable.analysis.LootFunctionDescribeSupport.bool;
import static com.meteorite.unsuspiciousblock.loottable.analysis.LootFunctionDescribeSupport.describeNested;
import static com.meteorite.unsuspiciousblock.loottable.analysis.LootFunctionDescribeSupport.enchantName;
import static com.meteorite.unsuspiciousblock.loottable.analysis.LootFunctionDescribeSupport.filterableText;
import static com.meteorite.unsuspiciousblock.loottable.analysis.LootFunctionDescribeSupport.fn;
import static com.meteorite.unsuspiciousblock.loottable.analysis.LootFunctionDescribeSupport.fnVariant;
import static com.meteorite.unsuspiciousblock.loottable.analysis.LootFunctionDescribeSupport.field;
import static com.meteorite.unsuspiciousblock.loottable.analysis.LootFunctionDescribeSupport.hint;
import static com.meteorite.unsuspiciousblock.loottable.analysis.LootFunctionDescribeSupport.idOf;
import static com.meteorite.unsuspiciousblock.loottable.analysis.LootFunctionDescribeSupport.info;
import static com.meteorite.unsuspiciousblock.loottable.analysis.LootFunctionDescribeSupport.intValue;
import static com.meteorite.unsuspiciousblock.loottable.analysis.LootFunctionDescribeSupport.join;
import static com.meteorite.unsuspiciousblock.loottable.analysis.LootFunctionDescribeSupport.joinIds;
import static com.meteorite.unsuspiciousblock.loottable.analysis.LootFunctionDescribeSupport.knownRange;
import static com.meteorite.unsuspiciousblock.loottable.analysis.LootFunctionDescribeSupport.listSize;
import static com.meteorite.unsuspiciousblock.loottable.analysis.LootFunctionDescribeSupport.literalOf;
import static com.meteorite.unsuspiciousblock.loottable.analysis.LootFunctionDescribeSupport.longValue;
import static com.meteorite.unsuspiciousblock.loottable.analysis.LootFunctionDescribeSupport.modeName;
import static com.meteorite.unsuspiciousblock.loottable.analysis.LootFunctionDescribeSupport.numberMeta;
import static com.meteorite.unsuspiciousblock.loottable.analysis.LootFunctionDescribeSupport.numberOrNull;
import static com.meteorite.unsuspiciousblock.loottable.analysis.LootFunctionDescribeSupport.numberText;
import static com.meteorite.unsuspiciousblock.loottable.analysis.LootFunctionDescribeSupport.objectOf;
import static com.meteorite.unsuspiciousblock.loottable.analysis.LootFunctionDescribeSupport.Options;
import static com.meteorite.unsuspiciousblock.loottable.analysis.LootFunctionDescribeSupport.operationParam;
import static com.meteorite.unsuspiciousblock.loottable.analysis.LootFunctionDescribeSupport.options;
import static com.meteorite.unsuspiciousblock.loottable.analysis.LootFunctionDescribeSupport.param;
import static com.meteorite.unsuspiciousblock.loottable.analysis.LootFunctionDescribeSupport.plainText;
import static com.meteorite.unsuspiciousblock.loottable.analysis.LootFunctionDescribeSupport.predicateText;
import static com.meteorite.unsuspiciousblock.loottable.analysis.LootFunctionDescribeSupport.slotList;
import static com.meteorite.unsuspiciousblock.loottable.analysis.LootFunctionDescribeSupport.slotNames;
import static com.meteorite.unsuspiciousblock.loottable.analysis.LootFunctionDescribeSupport.sourceParam;
import static com.meteorite.unsuspiciousblock.loottable.analysis.LootFunctionDescribeSupport.string;
import static com.meteorite.unsuspiciousblock.loottable.analysis.LootFunctionDescribeSupport.stringArray;
import static com.meteorite.unsuspiciousblock.loottable.analysis.LootFunctionDescribeSupport.targetParam;
import static com.meteorite.unsuspiciousblock.loottable.analysis.LootFunctionDescribeSupport.trimNumber;
import static com.meteorite.unsuspiciousblock.loottable.analysis.LootFunctionDescribeSupport.typeId;
import static com.meteorite.unsuspiciousblock.loottable.analysis.LootFunctionDescribeSupport.typeName;
import static com.meteorite.unsuspiciousblock.loottable.analysis.LootFunctionDescribeSupport.withChildren;
import static com.meteorite.unsuspiciousblock.loottable.analysis.LootFunctionDescribeSupport.withFidelity;
import static com.meteorite.unsuspiciousblock.loottable.analysis.LootFunctionDescribeSupport.yesNo;

/**
 * 战利品函数处理器注册表——管理所有已知 loot function 的静态解析逻辑。
 * <p>
 * 内建全部原版 1.21.1 的 40 个 loot function 处理器。自描述通道引入后，每个处理器给出三件
 * 互相独立的东西：<b>结构化静态描述</b>（{@link LootFunctionHandler#describe}，回答"规则声明了什么"）、
 * <b>静态预览</b>（{@link LootFunctionHandler#apply}）与<b>签名派生</b>。
 * 描述始终尝试生成，预览失败不影响描述的存在（规划 F02 / D15）。
 * <p>
 * 外部模组可通过 {@link #register} 注册自定义 function 的处理器。未知 function 在查询时返回 null，
 * 由 {@link LootFunctionDescriptions} 统一降级为"未解析"描述并保留可取得的注册名。
 * <p>
 * 语义修正（规划 §1.3）：{@code enchanted_count_increase} 归为数量函数（不再提升书预览、
 * 不再派生附魔签名）；{@code sequence}/{@code filtered}/{@code reference} 展开为包装描述并保留子节点；
 * 容器类函数归为容器效果。动态数值一律按"动态"表达，绝不猜成固定值。
 * <p>
 * 函数自身的条件不在本层解析：它需要 DynamicOps 与注册表视图，由投影侧在拿到
 * {@code RegistryOps} 之后补挂到对应函数节点上（规划 F04）。
 */
public final class LootFunctionHandlers {
    private static final Map<ResourceLocation, LootFunctionHandler> REGISTRY = new LinkedHashMap<>();
    private static final ResourceLocation BOOK_ID = ResourceLocation.fromNamespaceAndPath("minecraft", "book");

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

        // 类别 D：影响结果但无法静态求值（描述仍完整）
        register("copy_components", new CopyComponentsHandler());
        register("copy_custom_data", new CopyCustomDataHandler());
        register("copy_name", new CopyNameHandler());
        register("copy_state", new CopyStateHandler());
        register("set_instrument", new SetInstrumentHandler());
        register("set_fireworks", new SetFireworksHandler());
        register("set_firework_explosion", new SetFireworkExplosionHandler());
        register("set_banner_pattern", new SetBannerPatternHandler());
        register("set_book_cover", new SetBookCoverHandler());
        register("set_written_book_pages", new SetWrittenBookPagesHandler());
        register("set_writable_book_pages", new SetWritableBookPagesHandler());
        register("fill_player_head", new FillPlayerHeadHandler());
        register("set_stew_effect", new SetStewEffectHandler());
        register("exploration_map", new ExplorationMapHandler());
        register("furnace_smelt", new FurnaceSmeltHandler());
        register("set_contents", new SetContentsHandler());
        register("modify_contents", new ModifyContentsHandler());
        register("set_loot_table", new SetLootTableHandler());

        // 类别 E：数量/概率类
        register("limit_count", new LimitCountHandler());
        register("apply_bonus", new ApplyBonusHandler());
        register("explosion_decay", new ExplosionDecayHandler());

        // 类别 F：元函数（包装）
        register("filtered", new FilteredHandler());
        register("reference", new ReferenceHandler());
        register("sequence", new SequenceHandler());
    }

    private LootFunctionHandlers() {
    }

    /**
     * 注册自定义 loot function 处理器。
     *
     * @param functionPath function 注册名（如 "set_count"，不含 "minecraft:" 前缀）
     */
    public static void register(String functionPath, LootFunctionHandler handler) {
        REGISTRY.put(ResourceLocation.fromNamespaceAndPath("minecraft", functionPath), handler);
    }

    /**
     * 以完整 ResourceLocation 注册自定义 loot function 处理器。
     */
    public static void register(ResourceLocation functionId, LootFunctionHandler handler) {
        REGISTRY.put(functionId, handler);
    }

    /**
     * 获取指定 function 的处理器。
     *
     * @param functionId function 的完整注册表 key
     * @return 对应的 handler；未注册时返回 null
     */
    @Nullable
    public static LootFunctionHandler get(ResourceLocation functionId) {
        return REGISTRY.get(functionId);
    }

    /**
     * 获取注册表只读视图。
     */
    public static Map<ResourceLocation, LootFunctionHandler> registry() {
        return Collections.unmodifiableMap(REGISTRY);
    }

    /** 根据 function 对象推导其注册表 key */
    static ResourceLocation keyOf(LootItemFunction function) {
        return BuiltInRegistries.LOOT_FUNCTION_TYPE.getKey(function.getType());
    }

    // ==================== 反射工具 ====================

    /**
     * 通过反射读取原版类的 package-private 字段。
     * 仅用于静态分析，不修改对象状态。
     */
    @Nullable
    @SuppressWarnings("unchecked")
    private static <T> T reflectField(Object obj, String fieldName) {
        Class<?> type = obj.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(fieldName);
                field.setAccessible(true);
                return (T) field.get(obj);
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            } catch (ReflectiveOperationException | RuntimeException exception) {
                return null;
            }
        }
        return null;
    }

    /**
     * 将反射字段安全转换为目标类型，同时兼容原版 Codec 使用的 Optional 字段。
     */
    @Nullable
    static <T> T unwrapFieldValue(@Nullable Object fieldValue, Class<T> expectedType) {
        Object value = fieldValue instanceof Optional<?> optional
                ? optional.orElse(null)
                : fieldValue;
        return expectedType.isInstance(value) ? expectedType.cast(value) : null;
    }

    // ==================== 共享工具方法 ====================

    private static ResourceLocation itemIdOf(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem());
    }

    private static ItemStack promoteBookPreviewIfNeeded(ItemStack stack) {
        if (BOOK_ID.equals(itemIdOf(stack))) {
            return stack.transmuteCopy(Items.ENCHANTED_BOOK);
        }
        return stack;
    }

    // 效果展示名：优先用注册表里的效果名，取不到时退回 id 文本
    private static Component mobEffectName(@Nullable ResourceLocation effectId) {
        if (effectId == null) {
            return Component.literal("?");
        }
        MobEffect effect = BuiltInRegistries.MOB_EFFECT.get(effectId);
        return effect != null ? effect.getDisplayName() : Component.literal(effectId.toString());
    }

    // NBT 提供器的来源类别：对象取 type 路径，字符串直接规范化
    private static String nbtSourceName(@Nullable JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return "unknown";
        }
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
            return LootParseUtil.normalizeType(element.getAsString());
        }
        String type = typeName(objectOf(element));
        return type.isEmpty() ? "unknown" : type;
    }

    // 旗帜图案层数：对象取 layers 长度，数组直接取长度，其余视为未知
    private static int bannerLayerCount(@Nullable JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return -1;
        }
        if (element.isJsonArray()) {
            return element.getAsJsonArray().size();
        }
        JsonObject object = objectOf(element);
        return object != null ? listSize(object, "layers") : -1;
    }

    // ==================== 类别 A：完整静态处理 ====================

    /** 处理 set_item：将物品替换为指定物品 */
    private static final class SetItemHandler implements LootFunctionHandler {
        @Override
        @Nullable
        public ItemStack apply(ItemStack previewStack, LootItemFunction function) {
            if (!(function instanceof SetItemFunction)) {
                return null;
            }
            Object itemHolderRaw = reflectField(function, "item");
            if (!(itemHolderRaw instanceof Holder<?> itemHolder)) {
                return null;
            }
            ResourceLocation itemId = itemHolder.unwrapKey().map(ResourceKey::location).orElse(null);
            if (itemId != null && BuiltInRegistries.ITEM.get(itemId) != Items.AIR) {
                return previewStack.transmuteCopy(BuiltInRegistries.ITEM.get(itemId));
            }
            return null;
        }

        // 描述：写出被替换成的目标物品；取不到目标物品时降级为部分解析
        @Override
        public LootFunctionInfo describe(LootItemFunction function, JsonObject source) {
            ResourceLocation itemId = idOf(field(source, "item"));
            return info(typeId(function, "set_item"),
                    fn("set_item", itemId != null ? literalOf(itemId) : param("dynamic")),
                    itemId != null ? FunctionFidelity.FULL : FunctionFidelity.PARTIAL,
                    FunctionEffectKind.ITEM_TRANSFORM,
                    "item", itemId != null ? itemId.toString() : "dynamic");
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
        public ItemStack apply(ItemStack previewStack, LootItemFunction function) {
            if (!(function instanceof SetComponentsFunction)) {
                return null;
            }
            DataComponentPatch patch = reflectField(function, "components");
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

        // 描述：只写组件条目数量与效果类别，不把大型组件内容塞进描述
        @Override
        public LootFunctionInfo describe(LootItemFunction function, JsonObject source) {
            int count = listSize(source, "components");
            Component description = count >= 0 ? fn("set_components", count) : fnVariant("set_components", "dynamic");
            return info(typeId(function, "set_components"), description,
                    count >= 0 ? FunctionFidelity.FULL : FunctionFidelity.PARTIAL,
                    FunctionEffectKind.COMPONENT,
                    "component_count", count >= 0 ? Integer.toString(count) : "dynamic");
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
        public ItemStack apply(ItemStack previewStack, LootItemFunction function) {
            if (!(function instanceof SetCustomDataFunction)) {
                return null;
            }
            CompoundTag tag = reflectField(function, "tag");
            if (tag == null) {
                return previewStack;
            }
            CustomData.update(DataComponents.CUSTOM_DATA, previewStack, data -> data.merge(tag));
            return previewStack;
        }

        // 描述：写出声明的键集合；这是"声明的自定义数据"，不从运行时实体复制
        @Override
        public LootFunctionInfo describe(LootItemFunction function, JsonObject source) {
            JsonObject tag = objectOf(field(source, "tag"));
            if (tag == null) {
                return info(typeId(function, "set_custom_data"), fnVariant("set_custom_data", "empty"),
                        FunctionFidelity.PARTIAL, FunctionEffectKind.COMPONENT, "tag_keys", "unknown");
            }
            List<String> keys = new ArrayList<>(tag.keySet());
            return info(typeId(function, "set_custom_data"),
                    fn("set_custom_data", Component.literal(joinIds(keys))),
                    FunctionEffectKind.COMPONENT, "tag_keys", joinIds(keys));
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
        public ItemStack apply(ItemStack previewStack, LootItemFunction function) {
            if (!(function instanceof SetNameFunction)) {
                return null;
            }
            Component component = unwrapFieldValue(reflectField(function, "name"), Component.class);
            if (component == null) {
                return previewStack;
            }
            SetNameFunction.Target target = unwrapFieldValue(
                    reflectField(function, "target"), SetNameFunction.Target.class);
            if (target == null) {
                return previewStack;
            }
            return switch (target) {
                case CUSTOM_NAME -> {
                    previewStack.set(DataComponents.CUSTOM_NAME, component);
                    yield previewStack;
                }
                case ITEM_NAME -> {
                    previewStack.set(DataComponents.ITEM_NAME, component);
                    yield previewStack;
                }
            };
        }

        // 描述：写出名称文本与作用目标；name 缺省时如实写"未指定"
        @Override
        public LootFunctionInfo describe(LootItemFunction function, JsonObject source) {
            String target = string(source, "target", "custom_name");
            JsonElement nameElement = field(source, "name");
            Component nameText = nameElement == null
                    ? param("absent")
                    : Component.literal(plainText(nameElement));
            return info(typeId(function, "set_name"), fn("set_name", nameText, targetParam(target)),
                    FunctionEffectKind.COMPONENT,
                    "target", target,
                    "name", nameElement == null ? "absent" : plainText(nameElement),
                    "entity", string(source, "entity", "absent"));
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
        public ItemStack apply(ItemStack previewStack, LootItemFunction function) {
            if (!(function instanceof SetPotionFunction)) {
                return null;
            }
            Holder<Potion> potionHolder = reflectField(function, "potion");
            if (potionHolder == null) {
                return null;
            }
            previewStack.set(DataComponents.POTION_CONTENTS, new PotionContents(potionHolder));
            return previewStack;
        }

        // 描述：写出药水 id
        @Override
        public LootFunctionInfo describe(LootItemFunction function, JsonObject source) {
            ResourceLocation potionId = idOf(field(source, "id"));
            return info(typeId(function, "set_potion"), fn("set_potion", literalOf(potionId)),
                    potionId != null ? FunctionFidelity.FULL : FunctionFidelity.PARTIAL,
                    FunctionEffectKind.COMPONENT,
                    "potion", potionId != null ? potionId.toString() : "dynamic");
        }

        @Override
        public boolean addsRandomness() {
            return false;
        }
    }

    // ==================== 类别 B：附魔类 ====================

    private static final class EnchantRandomlyHandler implements LootFunctionHandler {
        @Override
        @Nullable
        public ItemStack apply(ItemStack previewStack, LootItemFunction function) {
            return promoteBookPreviewIfNeeded(previewStack);
        }

        // 描述：可选附魔集合必须区分"缺省"与"显式空"，并写出 only_compatible
        @Override
        public LootFunctionInfo describe(LootItemFunction function, JsonObject source) {
            Options optionSummary = options(source, "options");
            boolean onlyCompatible = bool(source, "only_compatible", true);
            Component description = onlyCompatible
                    ? fn("enchant_randomly", optionSummary.text())
                    : fnVariant("enchant_randomly", "incompatible", optionSummary.text());
            return info(typeId(function, "enchant_randomly"), description, FunctionEffectKind.COMPONENT,
                    "options", optionSummary.metadata(),
                    "only_compatible", Boolean.toString(onlyCompatible));
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

    private static final class EnchantWithLevelsHandler implements LootFunctionHandler {
        @Override
        @Nullable
        public ItemStack apply(ItemStack previewStack, LootItemFunction function) {
            return promoteBookPreviewIfNeeded(previewStack);
        }

        // 描述：levels 是附魔过程的等级输入，不是最终附魔等级的承诺；动态等级仍算完整描述
        @Override
        public LootFunctionInfo describe(LootItemFunction function, JsonObject source) {
            JsonElement levels = field(source, "levels");
            Options optionSummary = options(source, "options");
            return info(typeId(function, "enchant_with_levels"),
                    fn("enchant_with_levels", numberText(levels), optionSummary.text()),
                    FunctionEffectKind.COMPONENT,
                    "levels", numberMeta(levels),
                    "options", optionSummary.metadata());
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

    private static final class SetEnchantmentsHandler implements LootFunctionHandler {
        @Override
        @Nullable
        public ItemStack apply(ItemStack previewStack, LootItemFunction function) {
            return promoteBookPreviewIfNeeded(previewStack);
        }

        // 描述：等级是 NumberProvider，常量与动态分开表达；add=true 依赖输入已有等级（F08）
        @Override
        public LootFunctionInfo describe(LootItemFunction function, JsonObject source) {
            boolean add = bool(source, "add", false);
            JsonObject enchantments = objectOf(field(source, "enchantments"));
            List<Component> parts = new ArrayList<>();
            List<String> metaParts = new ArrayList<>();
            if (enchantments != null) {
                int index = 0;
                for (Map.Entry<String, JsonElement> entry : enchantments.entrySet()) {
                    if (index++ >= 8) {
                        break;
                    }
                    parts.add(append(Component.literal(entry.getKey() + "="), numberText(entry.getValue())));
                    metaParts.add(entry.getKey() + "=" + numberMeta(entry.getValue()));
                }
            }
            if (parts.isEmpty()) {
                // 空映射不是"清空"：原版 SetEnchantmentsFunction 只对声明项 set/累加，未声明项保持原值，
                // 所以 enchantments={} 且 add=false 是**空操作**（文案 key 沿用 clear，值已改为空操作语义）
                Component description = add
                        ? fnVariant("set_enchantments", "add_empty")
                        : fnVariant("set_enchantments", "clear");
                return info(typeId(function, "set_enchantments"), description, FunctionEffectKind.COMPONENT,
                        "enchantments", "empty", "add", Boolean.toString(add));
            }
            Component joined = join(Component.literal(", "), parts);
            Component description = add
                    ? fnVariant("set_enchantments", "add", joined)
                    : fn("set_enchantments", joined);
            return info(typeId(function, "set_enchantments"), description, FunctionEffectKind.COMPONENT,
                    "enchantments", joinIds(metaParts), "add", Boolean.toString(add));
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

    private static final class EnchantedCountIncreaseHandler implements LootFunctionHandler {
        // 该函数读取攻击实体身上的附魔等级来增加数量：不改变物品身份，也不提升书预览（F03）
        @Override
        @Nullable
        public ItemStack apply(ItemStack previewStack, LootItemFunction function) {
            return null;
        }

        // 描述：附魔、每次增量的提供器、上限与"攻击实体"上下文
        @Override
        public LootFunctionInfo describe(LootItemFunction function, JsonObject source) {
            ResourceLocation enchantmentId = idOf(field(source, "enchantment"));
            JsonElement countElement = field(source, "count");
            int limit = intValue(source, "limit", 0);
            Component description = limit > 0
                    ? fnVariant("enchanted_count_increase", "limit",
                    enchantName(enchantmentId), numberText(countElement), limit)
                    : fn("enchanted_count_increase", enchantName(enchantmentId), numberText(countElement));
            return info(typeId(function, "enchanted_count_increase"), description, FunctionEffectKind.COUNT,
                    "enchantment", enchantmentId != null ? enchantmentId.toString() : "dynamic",
                    "count", numberMeta(countElement),
                    "limit", Integer.toString(limit),
                    "context", "attacking_entity");
        }

        @Override
        public boolean addsRandomness() {
            return true;
        }
    }

    // ==================== 类别 C：可部分静态处理 ====================

    /** 处理 set_count：若 count 为固定值则直接应用；若为范围则返回 null */
    private static final class SetCountHandler implements LootFunctionHandler {
        @Override
        public LootConditionHandler.UncertaintyLevel uncertaintyLevel(LootItemFunction function) {
            NumberProvider value = reflectField(function, "value");
            if (value instanceof ConstantValue) {
                return LootConditionHandler.UncertaintyLevel.NONE;
            }
            if (value instanceof UniformGenerator(NumberProvider min, NumberProvider max)
                    && min instanceof ConstantValue(float lower)
                    && max instanceof ConstantValue(float upper)
                    && Float.isFinite(lower) && Float.isFinite(upper) && lower <= upper) {
                return lower == upper ? LootConditionHandler.UncertaintyLevel.NONE
                        : LootConditionHandler.UncertaintyLevel.PROBABILISTIC;
            }
            return LootConditionHandler.UncertaintyLevel.RUNTIME;
        }

        @Override
        @Nullable
        public ItemStack apply(ItemStack previewStack, LootItemFunction function) {
            if (!(function instanceof SetItemCountFunction)) {
                return null;
            }
            NumberProvider value = reflectField(function, "value");
            if (value instanceof ConstantValue(float value1)) {
                Boolean add = reflectField(function, "add");
                if (Boolean.TRUE.equals(add)) {
                    previewStack.grow(Math.round(value1));
                } else {
                    previewStack.setCount(Math.round(value1));
                }
                return previewStack;
            }
            return null;
        }

        // 描述：常量与范围复用既有提示文案，动态提供器按"动态"表达，不猜成固定值
        @Override
        public LootFunctionInfo describe(LootItemFunction function, JsonObject source) {
            JsonElement countElement = field(source, "count");
            double[] range = knownRange(countElement);
            boolean add = bool(source, "add", false);
            Component description;
            if (add) {
                description = fnVariant("set_count", "add", numberText(countElement));
            } else if (range != null && (range.length == 1 || range[0] == range[1])) {
                description = hint("set_count", trimNumber(range[0]));
            } else if (range != null) {
                description = hint("set_count_range", trimNumber(range[0]), trimNumber(range[1]));
            } else {
                description = fnVariant("set_count", "dynamic");
            }
            return info(typeId(function, "set_count"), description, FunctionEffectKind.COUNT,
                    "count", numberMeta(countElement), "add", Boolean.toString(add));
        }

        @Override
        @Nullable
        public Component describeHint(LootItemFunction function) {
            if (!(function instanceof SetItemCountFunction)) {
                return null;
            }
            NumberProvider value = reflectField(function, "value");
            if (value instanceof UniformGenerator uniform) {
                NumberProvider min = reflectField(uniform, "min");
                NumberProvider max = reflectField(uniform, "max");
                if (min instanceof ConstantValue(float value1) && max instanceof ConstantValue(float value2)) {
                    int minInt = Math.round(value1);
                    int maxInt = Math.round(value2);
                    if (minInt == maxInt) {
                        return Component.translatable(
                                "screen.unsuspiciousblock.archaeology_journal.item_hint.set_count", minInt);
                    }
                    return Component.translatable(
                            "screen.unsuspiciousblock.archaeology_journal.item_hint.set_count_range", minInt, maxInt);
                }
            }
            return null;
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
        public ItemStack apply(ItemStack previewStack, LootItemFunction function) {
            if (!(function instanceof SetItemDamageFunction)) {
                return null;
            }
            NumberProvider damage = reflectField(function, "damage");
            if (damage instanceof ConstantValue(float value)) {
                int maxDamage = previewStack.getMaxDamage();
                if (maxDamage > 0) {
                    float remainingDurability = 1.0F - (float) previewStack.getDamageValue() / maxDamage;
                    Boolean add = reflectField(function, "add");
                    float resultDurability = Boolean.TRUE.equals(add)
                            ? remainingDurability + value
                            : value;
                    previewStack.setDamageValue(Mth.floor(
                            (1.0F - Mth.clamp(resultDurability, 0.0F, 1.0F)) * maxDamage));
                }
                return previewStack;
            }
            return null;
        }

        // 描述：耐久比例按声明表达，区间复用既有文案；动态提供器不猜成固定值
        @Override
        public LootFunctionInfo describe(LootItemFunction function, JsonObject source) {
            JsonElement damageElement = field(source, "damage");
            double[] range = knownRange(damageElement);
            boolean add = bool(source, "add", false);
            boolean isRange = range != null && range.length == 2 && range[0] != range[1];
            Component valueText;
            if (range == null) {
                valueText = numberText(damageElement);
            } else if (!isRange) {
                valueText = fnVariant("value", "percent", trimNumber(Math.round(range[0] * 100)));
            } else {
                valueText = fnVariant("value", "percent_range",
                        trimNumber(Math.round(range[0] * 100)), trimNumber(Math.round(range[1] * 100)));
            }
            Component description;
            if (add) {
                description = fnVariant("set_damage", "add", valueText);
            } else if (isRange) {
                description = hint("set_damage_range",
                        Math.round(range[0] * 100), Math.round(range[1] * 100));
            } else {
                description = fn("set_damage", valueText);
            }
            return info(typeId(function, "set_damage"), description, FunctionEffectKind.COMPONENT,
                    "damage", numberMeta(damageElement), "add", Boolean.toString(add));
        }

        @Override
        @Nullable
        public Component describeHint(LootItemFunction function) {
            if (!(function instanceof SetItemDamageFunction)) {
                return null;
            }
            NumberProvider damage = reflectField(function, "damage");
            if (damage instanceof UniformGenerator uniform) {
                NumberProvider min = reflectField(uniform, "min");
                NumberProvider max = reflectField(uniform, "max");
                if (min instanceof ConstantValue(float minFloat) && max instanceof ConstantValue(float maxFloat)) {
                    if (minFloat == maxFloat) {
                        return null;
                    }
                    return Component.translatable(
                            "screen.unsuspiciousblock.archaeology_journal.item_hint.set_damage_range",
                            Math.round(minFloat * 100), Math.round(maxFloat * 100));
                }
            }
            return null;
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
        public ItemStack apply(ItemStack previewStack, LootItemFunction function) {
            if (!(function instanceof SetCustomModelDataFunction)) {
                return null;
            }
            NumberProvider value = reflectField(function, "value");
            if (value instanceof ConstantValue(float value1)) {
                previewStack.set(DataComponents.CUSTOM_MODEL_DATA,
                        new CustomModelData(Math.round(value1)));
                return previewStack;
            }
            return null;
        }

        // 描述：写出模型数据提供器；其他可选字段不影响本函数的类型与效果
        @Override
        public LootFunctionInfo describe(LootItemFunction function, JsonObject source) {
            JsonElement value = field(source, "value");
            return info(typeId(function, "set_custom_model_data"),
                    fn("set_custom_model_data", numberText(value)),
                    FunctionEffectKind.COMPONENT, "value", numberMeta(value));
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
        public ItemStack apply(ItemStack previewStack, LootItemFunction function) {
            if (!(function instanceof SetLoreFunction)) {
                return null;
            }
            List<Component> loreList = reflectField(function, "lore");
            if (loreList != null && !loreList.isEmpty()) {
                previewStack.set(DataComponents.LORE, new ItemLore(loreList));
                return previewStack;
            }
            return null;
        }

        // 描述：只写行数与列表操作模式，不把长文本全量塞进描述
        @Override
        public LootFunctionInfo describe(LootItemFunction function, JsonObject source) {
            int lines = listSize(source, "lore");
            String mode = modeName(field(source, "mode"), "replace_all");
            Component description;
            if (lines < 0) {
                description = fnVariant("set_lore", "dynamic");
            } else if (mode.equals("append") || mode.equals("insert")) {
                description = fnVariant("set_lore", "append", lines);
            } else {
                description = fn("set_lore", lines);
            }
            return info(typeId(function, "set_lore"), description, FunctionEffectKind.COMPONENT,
                    "line_count", lines >= 0 ? Integer.toString(lines) : "dynamic",
                    "mode", mode);
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
        public ItemStack apply(ItemStack previewStack, LootItemFunction function) {
            return null;
        }

        // 描述：属性、数值提供器、槽位与替换/追加方式逐条写出（§4.4 高价值字段）
        @Override
        public LootFunctionInfo describe(LootItemFunction function, JsonObject source) {
            JsonArray modifiers = arrayOf(field(source, "modifiers"));
            boolean replace = bool(source, "replace", true);
            if (modifiers == null || modifiers.isEmpty()) {
                Component description = replace
                        ? fnVariant("set_attributes", "clear")
                        : fnVariant("set_attributes", "empty");
                return info(typeId(function, "set_attributes"), description, FunctionEffectKind.COMPONENT,
                        "modifier_count", "0", "replace", Boolean.toString(replace));
            }
            List<Component> parts = new ArrayList<>();
            List<String> metaParts = new ArrayList<>();
            int limit = Math.min(modifiers.size(), 8);
            for (int index = 0; index < limit; index++) {
                JsonObject modifier = objectOf(modifiers.get(index));
                if (modifier == null) {
                    continue;
                }
                String attribute = string(modifier, "attribute", "?");
                String operation = string(modifier, "operation", "add_value");
                JsonElement amount = field(modifier, "amount");
                JsonElement slot = field(modifier, "slot");
                parts.add(fnVariant("set_attributes", "entry", Component.literal(attribute),
                        operationParam(operation), numberText(amount), slotList(slot)));
                metaParts.add(attribute + "/" + operation + "/" + numberMeta(amount) + "/" + slotNames(slot));
            }
            Component joined = join(Component.literal("; "), parts);
            Component description = replace
                    ? fn("set_attributes", joined)
                    : fnVariant("set_attributes", "append", joined);
            return info(typeId(function, "set_attributes"), description, FunctionEffectKind.COMPONENT,
                    "modifier_count", Integer.toString(modifiers.size()),
                    "replace", Boolean.toString(replace),
                    "modifiers", joinIds(metaParts));
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
        public ItemStack apply(ItemStack previewStack, LootItemFunction function) {
            return previewStack;
        }

        // 描述：写出被切换的组件条目数量与显示状态，不展开每个组件的内部值
        @Override
        public LootFunctionInfo describe(LootItemFunction function, JsonObject source) {
            JsonObject toggles = objectOf(field(source, "toggles"));
            int count = toggles == null ? -1 : toggles.size();
            Component description = count >= 0
                    ? fn("toggle_tooltips", count)
                    : fnVariant("toggle_tooltips", "dynamic");
            List<String> metaParts = new ArrayList<>();
            if (toggles != null) {
                for (Map.Entry<String, JsonElement> entry : toggles.entrySet()) {
                    metaParts.add(entry.getKey() + "=" + plainText(entry.getValue()));
                }
            }
            return info(typeId(function, "toggle_tooltips"), description, FunctionEffectKind.COMPONENT,
                    "toggle_count", count >= 0 ? Integer.toString(count) : "dynamic",
                    "toggles", joinIds(metaParts));
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
        public ItemStack apply(ItemStack previewStack, LootItemFunction function) {
            if (!(function instanceof SetOminousBottleAmplifierFunction)) {
                return null;
            }
            NumberProvider amplifier = reflectField(function, "amplifierGenerator");
            if (amplifier instanceof ConstantValue(float value)) {
                previewStack.set(DataComponents.OMINOUS_BOTTLE_AMPLIFIER, Math.round(value));
                return previewStack;
            }
            return null;
        }

        // 描述：增幅同样是 NumberProvider，常量与动态分开表达
        @Override
        public LootFunctionInfo describe(LootItemFunction function, JsonObject source) {
            JsonElement amplifier = field(source, "amplifier");
            return info(typeId(function, "set_ominous_bottle_amplifier"),
                    fn("set_ominous_bottle_amplifier", numberText(amplifier)),
                    FunctionEffectKind.COMPONENT, "amplifier", numberMeta(amplifier));
        }

        @Override
        public boolean addsRandomness() {
            return false;
        }
    }

    // ==================== 类别 D：新增描述 handler ====================

    /** 处理 copy_components：从上下文来源复制组件到物品 */
    private static final class CopyComponentsHandler implements LootFunctionHandler {
        @Override
        @Nullable
        public ItemStack apply(ItemStack previewStack, LootItemFunction function) {
            return null;
        }

        // 描述：来源类别 + 包含/排除过滤，不展开实例实体的完整内容
        @Override
        public LootFunctionInfo describe(LootItemFunction function, JsonObject source) {
            String sourceName = string(source, "source", "unknown");
            int include = listSize(source, "include");
            int exclude = listSize(source, "exclude");
            Component description = fn("copy_components", sourceParam(sourceName));
            if (include >= 0) {
                description = append(description, fnVariant("copy_components", "include", include));
            }
            if (exclude >= 0) {
                description = append(description, fnVariant("copy_components", "exclude", exclude));
            }
            return info(typeId(function, "copy_components"), description, FunctionEffectKind.COMPONENT,
                    "source", sourceName,
                    "include", include >= 0 ? Integer.toString(include) : "absent",
                    "exclude", exclude >= 0 ? Integer.toString(exclude) : "absent");
        }

        @Override
        public boolean addsRandomness() {
            return true;
        }
    }

    /** 处理 copy_custom_data：从上下文来源复制自定义数据 */
    private static final class CopyCustomDataHandler implements LootFunctionHandler {
        @Override
        @Nullable
        public ItemStack apply(ItemStack previewStack, LootItemFunction function) {
            return null;
        }

        // 描述：来源提供器与复制路径数量
        @Override
        public LootFunctionInfo describe(LootItemFunction function, JsonObject source) {
            String sourceName = nbtSourceName(field(source, "source"));
            int operations = listSize(source, "ops");
            Component operationText = operations >= 0 ? Component.literal(Integer.toString(operations)) : param("dynamic");
            return info(typeId(function, "copy_custom_data"),
                    fn("copy_custom_data", sourceParam(sourceName), operationText),
                    FunctionEffectKind.COMPONENT,
                    "source", sourceName,
                    "op_count", operations >= 0 ? Integer.toString(operations) : "dynamic");
        }

        @Override
        public boolean addsRandomness() {
            return true;
        }
    }

    /** 处理 copy_name：从上下文来源复制名称 */
    private static final class CopyNameHandler implements LootFunctionHandler {
        @Override
        @Nullable
        public ItemStack apply(ItemStack previewStack, LootItemFunction function) {
            return null;
        }

        // 描述：复制来源类别，不展开实例实体 NBT 或玩家名称
        @Override
        public LootFunctionInfo describe(LootItemFunction function, JsonObject source) {
            String sourceName = string(source, "source", "unknown");
            return info(typeId(function, "copy_name"), fn("copy_name", sourceParam(sourceName)),
                    FunctionEffectKind.COMPONENT, "source", sourceName);
        }

        @Override
        public boolean addsRandomness() {
            return true;
        }
    }

    /** 处理 copy_state：从方块实体复制方块状态属性 */
    private static final class CopyStateHandler implements LootFunctionHandler {
        @Override
        @Nullable
        public ItemStack apply(ItemStack previewStack, LootItemFunction function) {
            return null;
        }

        // 描述：方块与声明的属性名集合
        @Override
        public LootFunctionInfo describe(LootItemFunction function, JsonObject source) {
            ResourceLocation blockId = idOf(field(source, "block"));
            List<String> properties = stringArray(source, "properties");
            return info(typeId(function, "copy_state"),
                    fn("copy_state", literalOf(blockId), Component.literal(joinIds(properties))),
                    blockId != null ? FunctionFidelity.FULL : FunctionFidelity.PARTIAL,
                    FunctionEffectKind.COMPONENT,
                    "block", blockId != null ? blockId.toString() : "dynamic",
                    "properties", joinIds(properties));
        }

        @Override
        public boolean addsRandomness() {
            return true;
        }
    }

    /** 处理 set_instrument：从标签池中设置山羊角乐器 */
    private static final class SetInstrumentHandler implements LootFunctionHandler {
        @Override
        @Nullable
        public ItemStack apply(ItemStack previewStack, LootItemFunction function) {
            return null;
        }

        // 描述：乐器标签池
        @Override
        public LootFunctionInfo describe(LootItemFunction function, JsonObject source) {
            String options = string(source, "options", "");
            Component description = options.isEmpty()
                    ? fnVariant("set_instrument", "dynamic")
                    : fn("set_instrument", Component.literal(options));
            return info(typeId(function, "set_instrument"), description, FunctionEffectKind.COMPONENT,
                    "options", options.isEmpty() ? "dynamic" : options);
        }

        @Override
        public boolean addsRandomness() {
            return true;
        }
    }

    /** 处理 set_firework_explosion：替换烟花爆炸效果 */
    private static final class SetFireworkExplosionHandler implements LootFunctionHandler {
        @Override
        @Nullable
        public ItemStack apply(ItemStack previewStack, LootItemFunction function) {
            return null;
        }

        // 描述：形状、颜色/褪色数量与拖尾、闪烁开关
        @Override
        public LootFunctionInfo describe(LootItemFunction function, JsonObject source) {
            String shape = string(source, "shape", "small_ball");
            int colors = listSize(source, "colors");
            int fadeColors = listSize(source, "fade_colors");
            boolean trail = bool(source, "trail", false);
            boolean twinkle = bool(source, "twinkle", false);
            Component colorText = colors >= 0 ? Component.literal(Integer.toString(colors)) : param("dynamic");
            Component fadeText = fadeColors >= 0 ? Component.literal(Integer.toString(fadeColors)) : param("dynamic");
            return info(typeId(function, "set_firework_explosion"),
                    fn("set_firework_explosion", Component.literal(shape), colorText, fadeText,
                            yesNo(trail), yesNo(twinkle)),
                    FunctionEffectKind.COMPONENT,
                    "shape", shape,
                    "colors", colors >= 0 ? Integer.toString(colors) : "dynamic",
                    "fade_colors", fadeColors >= 0 ? Integer.toString(fadeColors) : "dynamic",
                    "trail", Boolean.toString(trail),
                    "twinkle", Boolean.toString(twinkle));
        }

        @Override
        public boolean addsRandomness() {
            return true;
        }
    }

    /** 处理 set_banner_pattern：设置旗帜图案层 */
    private static final class SetBannerPatternHandler implements LootFunctionHandler {
        @Override
        @Nullable
        public ItemStack apply(ItemStack previewStack, LootItemFunction function) {
            return null;
        }

        // 描述：图案层数与覆盖/追加方式
        @Override
        public LootFunctionInfo describe(LootItemFunction function, JsonObject source) {
            int layers = bannerLayerCount(field(source, "patterns"));
            boolean appendFlag = bool(source, "append", false);
            Component description = layers < 0
                    ? fnVariant("set_banner_pattern", "dynamic")
                    : (appendFlag ? fnVariant("set_banner_pattern", "append", layers)
                    : fn("set_banner_pattern", layers));
            return info(typeId(function, "set_banner_pattern"), description, FunctionEffectKind.COMPONENT,
                    "pattern_count", layers >= 0 ? Integer.toString(layers) : "dynamic",
                    "append", Boolean.toString(appendFlag));
        }

        @Override
        public boolean addsRandomness() {
            return true;
        }
    }

    /** 处理 set_written_book_pages：设置成书页面 */
    private static final class SetWrittenBookPagesHandler implements LootFunctionHandler {
        @Override
        @Nullable
        public ItemStack apply(ItemStack previewStack, LootItemFunction function) {
            return null;
        }

        // 描述：页数与列表操作模式，不把页文本全量塞进描述
        @Override
        public LootFunctionInfo describe(LootItemFunction function, JsonObject source) {
            int pages = listSize(source, "pages");
            String mode = modeName(field(source, "mode"), "replace_all");
            Component description = pages < 0
                    ? fnVariant("set_written_book_pages", "dynamic")
                    : ((mode.equals("append") || mode.equals("insert"))
                    ? fnVariant("set_written_book_pages", "append", pages)
                    : fn("set_written_book_pages", pages));
            return info(typeId(function, "set_written_book_pages"), description, FunctionEffectKind.COMPONENT,
                    "page_count", pages >= 0 ? Integer.toString(pages) : "dynamic",
                    "mode", mode);
        }

        @Override
        public boolean addsRandomness() {
            return true;
        }
    }

    /** 处理 set_writable_book_pages：设置书与笔页面 */
    private static final class SetWritableBookPagesHandler implements LootFunctionHandler {
        @Override
        @Nullable
        public ItemStack apply(ItemStack previewStack, LootItemFunction function) {
            return null;
        }

        // 描述：页数与列表操作模式，不把页文本全量塞进描述
        @Override
        public LootFunctionInfo describe(LootItemFunction function, JsonObject source) {
            int pages = listSize(source, "pages");
            String mode = modeName(field(source, "mode"), "replace_all");
            Component description = pages < 0
                    ? fnVariant("set_writable_book_pages", "dynamic")
                    : ((mode.equals("append") || mode.equals("insert"))
                    ? fnVariant("set_writable_book_pages", "append", pages)
                    : fn("set_writable_book_pages", pages));
            return info(typeId(function, "set_writable_book_pages"), description, FunctionEffectKind.COMPONENT,
                    "page_count", pages >= 0 ? Integer.toString(pages) : "dynamic",
                    "mode", mode);
        }

        @Override
        public boolean addsRandomness() {
            return true;
        }
    }

    /** 处理 furnace_smelt：熔炼物品（产物由运行时配方决定） */
    private static final class FurnaceSmeltHandler implements LootFunctionHandler {
        @Override
        @Nullable
        public ItemStack apply(ItemStack previewStack, LootItemFunction function) {
            return null;
        }

        // 描述：只说明"按熔炼配方变换"，不另建静态配方执行器（§4.4）
        @Override
        public LootFunctionInfo describe(LootItemFunction function, JsonObject source) {
            return info(typeId(function, "furnace_smelt"), fn("furnace_smelt"),
                    FunctionEffectKind.ITEM_TRANSFORM);
        }

        @Override
        public boolean addsRandomness() {
            return true;
        }
    }

    /** 处理 set_contents：设置容器内容（内容待生成，不并入外层掉落） */
    private static final class SetContentsHandler implements LootFunctionHandler {
        @Override
        @Nullable
        public ItemStack apply(ItemStack previewStack, LootItemFunction function) {
            return null;
        }

        // 描述：容器组件与条目规则数量；不展开内层逐物品规则（F09）
        @Override
        public LootFunctionInfo describe(LootItemFunction function, JsonObject source) {
            String component = string(source, "component", "");
            int entries = listSize(source, "entries");
            Component componentText = component.isEmpty() ? param("dynamic") : Component.literal(component);
            Component entryText = entries >= 0 ? Component.literal(Integer.toString(entries)) : param("dynamic");
            return info(typeId(function, "set_contents"), fn("set_contents", componentText, entryText),
                    FunctionEffectKind.CONTAINER,
                    "component", component.isEmpty() ? "dynamic" : component,
                    "entry_count", entries >= 0 ? Integer.toString(entries) : "dynamic");
        }

        @Override
        public boolean addsRandomness() {
            return true;
        }
    }

    /** 处理 modify_contents：修改已有容器内容 */
    private static final class ModifyContentsHandler implements LootFunctionHandler {
        @Override
        @Nullable
        public ItemStack apply(ItemStack previewStack, LootItemFunction function) {
            return null;
        }

        // 描述：容器组件 + 可读的内层规则；仍按容器效果处理，不并入外层掉落（F09）
        @Override
        public LootFunctionInfo describe(LootItemFunction function, JsonObject source) {
            String component = string(source, "component", "");
            LootItemFunction inner = reflectField(function, "modifier");
            LootFunctionInfo child = describeNested(inner, field(source, "modifier"));
            Component componentText = component.isEmpty() ? param("dynamic") : Component.literal(component);
            LootFunctionInfo base = info(typeId(function, "modify_contents"),
                    fn("modify_contents", componentText),
                    FunctionEffectKind.CONTAINER,
                    "component", component.isEmpty() ? "dynamic" : component,
                    "child_count", child != null ? "1" : "0");
            return child != null ? withChildren(base, List.of(child)) : base;
        }

        @Override
        public boolean addsRandomness() {
            return true;
        }
    }

    /** 处理 set_loot_table：给容器方块实体写入待生成的战利品表 */
    private static final class SetLootTableHandler implements LootFunctionHandler {
        @Override
        @Nullable
        public ItemStack apply(ItemStack previewStack, LootItemFunction function) {
            return null;
        }

        // 描述：被引用的表、方块实体类型与种子；不执行被引用表（F09）
        @Override
        public LootFunctionInfo describe(LootItemFunction function, JsonObject source) {
            ResourceLocation tableId = idOf(field(source, "name"));
            String type = string(source, "type", "");
            long seed = longValue(source, "seed", 0L);
            Component typeText = type.isEmpty() ? param("absent") : Component.literal(type);
            return info(typeId(function, "set_loot_table"),
                    fn("set_loot_table", literalOf(tableId), typeText, seed),
                    tableId != null ? FunctionFidelity.FULL : FunctionFidelity.PARTIAL,
                    FunctionEffectKind.CONTAINER,
                    "name", tableId != null ? tableId.toString() : "dynamic",
                    "type", type.isEmpty() ? "absent" : type,
                    "seed", Long.toString(seed));
        }

        @Override
        public boolean addsRandomness() {
            return true;
        }
    }

    // ==================== 类别 E：数量/概率类 ====================

    /** 处理 apply_bonus：展示附魔加成公式与适用于该公式的参数 */
    private static final class ApplyBonusHandler implements LootFunctionHandler {
        @Override
        @Nullable
        public ItemStack apply(ItemStack previewStack, LootItemFunction function) {
            return null;
        }

        // 描述：附魔 + 公式 + 该公式自己的参数；未知公式标部分解析（§4.4）
        @Override
        public LootFunctionInfo describe(LootItemFunction function, JsonObject source) {
            ResourceLocation enchantmentId = idOf(field(source, "enchantment"));
            String formulaType = formulaTypeOf(source);
            // 原版 ApplyBonusCount 用 dispatchOptionalValue("formula", "parameters", ...)：
            // formula 是 ResourceLocation 字符串，公式参数位于**同级 parameters 对象**里
            JsonObject parameterSource = parametersOf(source);
            Component base = hint("apply_bonus", enchantName(enchantmentId),
                    Component.translatable(LootFunctionDescribeSupport.KEY_PREFIX + "formula." + formulaType));
            Component params;
            String paramsMeta;
            boolean known;
            switch (formulaType) {
                case "binomial_with_bonus_count" -> {
                    int extra = intValue(parameterSource, "extra", 0);
                    Double probability = parameterSource != null
                            ? numberOrNull(parameterSource.get("probability")) : null;
                    params = fnVariant("apply_bonus", "binomial", extra,
                            trimNumber(probability != null ? probability : 0));
                    paramsMeta = "extra=" + extra + ",probability=" + trimNumber(probability != null ? probability : 0);
                    known = true;
                }
                case "uniform_bonus_count" -> {
                    int multiplier = intValue(parameterSource, "bonusMultiplier", 0);
                    params = fnVariant("apply_bonus", "uniform", multiplier);
                    paramsMeta = "bonusMultiplier=" + multiplier;
                    known = true;
                }
                case "ore_drops" -> {
                    params = fnVariant("apply_bonus", "ore_drops");
                    paramsMeta = "none";
                    known = true;
                }
                default -> {
                    params = param("dynamic");
                    paramsMeta = "unknown";
                    known = false;
                }
            }
            Component description = known ? append(base, fnVariant("apply_bonus", "params", params)) : base;
            return info(typeId(function, "apply_bonus"), description,
                    known ? FunctionFidelity.FULL : FunctionFidelity.PARTIAL,
                    FunctionEffectKind.COUNT,
                    "enchantment", enchantmentId != null ? enchantmentId.toString() : "dynamic",
                    "formula", formulaType,
                    "formula_params", paramsMeta);
        }

        @Override
        @Nullable
        public Component describeHint(LootItemFunction function, JsonObject source) {
            if (!(function instanceof ApplyBonusCount)) {
                return null;
            }
            Holder<Enchantment> enchantmentHolder = reflectField(function, "enchantment");
            ResourceLocation enchantmentId = enchantmentHolder != null
                    ? enchantmentHolder.unwrapKey().map(ResourceKey::location).orElse(null)
                    : null;
            Component enchantmentName = enchantmentName(enchantmentId);

            // 公式名是字符串（"minecraft:xxx" 或裸名 "xxx"），必须先规范化命名空间再查文案；
            // 旧实现误以为 formula 是内嵌 type 的对象，带命名空间的名字匹配不到本地化 key
            String formulaType = formulaTypeOf(source);
            Component formulaName = Component.translatable(
                    "screen.unsuspiciousblock.archaeology_journal.formula." + formulaType);

            return Component.translatable(
                    "screen.unsuspiciousblock.archaeology_journal.item_hint.apply_bonus",
                    enchantmentName, formulaName);
        }

        // 公式名：按字符串读取并规范化命名空间（"minecraft:xxx" 与 "xxx" 等价）；
        // 兼容旧的内嵌对象写法（对象里的 type 字段），两者都读不到时返回 "unknown"
        private static String formulaTypeOf(@Nullable JsonObject source) {
            String formulaType = LootParseUtil.normalizeType(string(source, "formula", ""));
            if (formulaType.isEmpty()) {
                formulaType = typeName(objectOf(field(source, "formula")));
            }
            return formulaType.isEmpty() ? "unknown" : formulaType;
        }

        // 公式参数：优先读同级 parameters 对象；缺失时回退读内嵌 formula 对象（兼容旧写法）
        @Nullable
        private static JsonObject parametersOf(@Nullable JsonObject source) {
            JsonObject parameters = objectOf(field(source, "parameters"));
            return parameters != null ? parameters : objectOf(field(source, "formula"));
        }

        // 兼容摘要里的附魔名称：与描述通道保持同一口径
        private static Component enchantmentName(@Nullable ResourceLocation id) {
            return id == null
                    ? Component.literal("?")
                    : Component.translatable("enchantment." + id.getNamespace() + "." + id.getPath());
        }

        @Override
        public boolean addsRandomness() {
            return true;
        }
    }

    /** 处理 limit_count：展示数量限制范围 */
    private static final class LimitCountHandler implements LootFunctionHandler {
        @Override
        @Nullable
        public ItemStack apply(ItemStack previewStack, LootItemFunction function) {
            return null;
        }

        // 描述：上限/下限/固定值复用既有文案；动态限制不猜成固定值
        @Override
        public LootFunctionInfo describe(LootItemFunction function, JsonObject source) {
            JsonObject limit = objectOf(field(source, "limit"));
            Double min = limit != null ? numberOrNull(limit.get("min")) : null;
            Double max = limit != null ? numberOrNull(limit.get("max")) : null;
            Component description;
            if (min != null && max != null) {
                description = Math.round(min) == Math.round(max)
                        ? hint("limit_count_exact", Math.round(min))
                        : hint("limit_count", Math.round(min), Math.round(max));
            } else if (max != null) {
                description = hint("limit_count_max", Math.round(max));
            } else if (min != null) {
                description = hint("limit_count_min", Math.round(min));
            } else {
                description = fnVariant("limit_count", "dynamic");
            }
            boolean known = min != null || max != null;
            return info(typeId(function, "limit_count"), description,
                    known ? FunctionFidelity.FULL : FunctionFidelity.PARTIAL,
                    FunctionEffectKind.COUNT,
                    "min", min != null ? trimNumber(min) : "dynamic",
                    "max", max != null ? trimNumber(max) : "dynamic");
        }

        @Override
        @Nullable
        public Component describeHint(LootItemFunction function) {
            if (!(function instanceof LimitCount)) {
                return null;
            }
            IntRange limiter = reflectField(function, "limiter");
            if (limiter == null) {
                return null;
            }
            NumberProvider min = reflectField(limiter, "min");
            NumberProvider max = reflectField(limiter, "max");

            Integer minVal = (min instanceof ConstantValue(float value)) ? Math.round(value) : null;
            Integer maxVal = (max instanceof ConstantValue(float value)) ? Math.round(value) : null;

            if (minVal != null && maxVal != null) {
                if (minVal.equals(maxVal)) {
                    return Component.translatable(
                            "screen.unsuspiciousblock.archaeology_journal.item_hint.limit_count_exact", minVal);
                }
                return Component.translatable(
                        "screen.unsuspiciousblock.archaeology_journal.item_hint.limit_count", minVal, maxVal);
            } else if (maxVal != null) {
                return Component.translatable(
                        "screen.unsuspiciousblock.archaeology_journal.item_hint.limit_count_max", maxVal);
            } else if (minVal != null) {
                return Component.translatable(
                        "screen.unsuspiciousblock.archaeology_journal.item_hint.limit_count_min", minVal);
            }
            return null;
        }

        @Override
        public boolean addsRandomness() {
            return true;
        }
    }

    /** 处理 explosion_decay：按爆炸半径概率销毁物品 */
    private static final class ExplosionDecayHandler implements LootFunctionHandler {
        @Override
        @Nullable
        public ItemStack apply(ItemStack previewStack, LootItemFunction function) {
            return null;
        }

        // 描述：原版爆炸衰减语义，无参数
        @Override
        public LootFunctionInfo describe(LootItemFunction function, JsonObject source) {
            return info(typeId(function, "explosion_decay"), fn("explosion_decay"),
                    FunctionEffectKind.COUNT);
        }

        @Override
        public boolean addsRandomness() {
            return true;
        }
    }

    /** 处理 fill_player_head：用上下文玩家填充头颅 */
    private static final class FillPlayerHeadHandler implements LootFunctionHandler {
        @Override
        @Nullable
        public ItemStack apply(ItemStack previewStack, LootItemFunction function) {
            return null;
        }

        // 描述：复用既有玩家头颅文案，并记录来源实体类别
        @Override
        public LootFunctionInfo describe(LootItemFunction function, JsonObject source) {
            return info(typeId(function, "fill_player_head"), hint("fill_player_head"),
                    FunctionEffectKind.COMPONENT,
                    "entity", string(source, "entity", "this"));
        }

        @Override
        public Component describeHint(LootItemFunction function) {
            return Component.translatable(
                    "screen.unsuspiciousblock.archaeology_journal.item_hint.fill_player_head");
        }

        @Override
        public boolean addsRandomness() {
            return true;
        }
    }

    /** 处理 set_stew_effect：设置炖菜效果与持续时间 */
    private static final class SetStewEffectHandler implements LootFunctionHandler {
        @Override
        @Nullable
        public ItemStack apply(ItemStack previewStack, LootItemFunction function) {
            return null;
        }

        // 描述：效果名 + 持续时间提供器；动态持续时间按动态表达（§4.4）
        @Override
        public LootFunctionInfo describe(LootItemFunction function, JsonObject source) {
            JsonArray effects = arrayOf(field(source, "effects"));
            if (effects == null || effects.isEmpty()) {
                return info(typeId(function, "set_stew_effect"), fnVariant("set_stew_effect", "none"),
                        FunctionFidelity.PARTIAL, FunctionEffectKind.COMPONENT, "effects", "empty");
            }
            List<Component> parts = new ArrayList<>();
            List<String> metaParts = new ArrayList<>();
            int limit = Math.min(effects.size(), 8);
            for (int index = 0; index < limit; index++) {
                JsonObject entry = objectOf(effects.get(index));
                if (entry == null) {
                    continue;
                }
                ResourceLocation effectId = idOf(field(entry, "type"));
                JsonElement duration = field(entry, "duration");
                parts.add(fnVariant("set_stew_effect", "entry", mobEffectName(effectId), numberText(duration)));
                metaParts.add((effectId != null ? effectId.toString() : "dynamic") + "=" + numberMeta(duration));
            }
            Component joined = join(Component.literal(", "), parts);
            return info(typeId(function, "set_stew_effect"), hint("set_stew_effect", joined),
                    FunctionEffectKind.COMPONENT, "effects", joinIds(metaParts));
        }

        @Override
        @Nullable
        public Component describeHint(LootItemFunction function) {
            if (!(function instanceof SetStewEffectFunction)) {
                return null;
            }
            List<?> effects = reflectField(function, "effects");
            if (effects == null || effects.isEmpty()) {
                return null;
            }
            List<Component> effectNames = new ArrayList<>();
            for (Object entry : effects) {
                Holder<MobEffect> effectHolder = reflectField(entry, "effect");
                if (effectHolder != null) {
                    effectNames.add(effectHolder.value().getDisplayName());
                }
            }
            if (effectNames.isEmpty()) {
                return null;
            }
            Component joined = join(Component.literal("，"), effectNames);
            return Component.translatable(
                    "screen.unsuspiciousblock.archaeology_journal.item_hint.set_stew_effect", joined);
        }

        @Override
        public boolean addsRandomness() {
            return true;
        }
    }

    /** 处理 exploration_map：展示探索地图目的地 */
    private static final class ExplorationMapHandler implements LootFunctionHandler {
        @Override
        @Nullable
        public ItemStack apply(ItemStack previewStack, LootItemFunction function) {
            return null;
        }

        // 描述：目的地 id；缺省时复用"默认目的地"文案
        @Override
        public LootFunctionInfo describe(LootItemFunction function, JsonObject source) {
            ResourceLocation destination = idOf(field(source, "destination"));
            Component description = destination != null
                    ? hint("exploration_map", Component.literal(destination.getPath()))
                    : hint("exploration_map_default");
            return info(typeId(function, "exploration_map"), description, FunctionEffectKind.COMPONENT,
                    "destination", destination != null ? destination.toString() : "default");
        }

        @Override
        @Nullable
        public Component describeHint(LootItemFunction function) {
            if (!(function instanceof ExplorationMapFunction)) {
                return null;
            }
            // destination 是 ResourceKey<MapDecorationType>，通过反射获取
            Object destination = reflectField(function, "destination");
            if (destination == null) {
                return Component.translatable(
                        "screen.unsuspiciousblock.archaeology_journal.item_hint.exploration_map_default");
            }
            // ResourceKey 有 location() 方法
            try {
                java.lang.reflect.Method locationMethod = destination.getClass().getMethod("location");
                ResourceLocation loc = (ResourceLocation) locationMethod.invoke(destination);
                String tagPath = loc.getPath();
                return Component.translatable(
                        "screen.unsuspiciousblock.archaeology_journal.item_hint.exploration_map", tagPath);
            } catch (Exception e) {
                return Component.translatable(
                        "screen.unsuspiciousblock.archaeology_journal.item_hint.exploration_map_default");
            }
        }

        @Override
        public boolean addsRandomness() {
            return true;
        }
    }

    /** 处理 set_fireworks：展示烟花飞行时间与爆炸数量 */
    private static final class SetFireworksHandler implements LootFunctionHandler {
        @Override
        @Nullable
        public ItemStack apply(ItemStack previewStack, LootItemFunction function) {
            return null;
        }

        // 描述：飞行时间复用既有文案；缺省时只说明爆炸效果数量，不猜飞行时间
        @Override
        public LootFunctionInfo describe(LootItemFunction function, JsonObject source) {
            JsonElement flight = field(source, "flight_duration");
            int explosions = listSize(source, "explosions");
            boolean hasFlight = flight != null && flight.isJsonPrimitive() && flight.getAsJsonPrimitive().isNumber();
            Component description;
            if (hasFlight) {
                description = hint("set_fireworks_flight", flight.getAsInt());
            } else if (explosions >= 0) {
                description = fn("set_fireworks", explosions);
            } else {
                description = fnVariant("set_fireworks", "none");
            }
            return info(typeId(function, "set_fireworks"), description, FunctionEffectKind.COMPONENT,
                    "flight_duration", hasFlight ? plainText(flight) : "absent",
                    "explosions", explosions >= 0 ? Integer.toString(explosions) : "absent");
        }

        @Override
        @Nullable
        public Component describeHint(LootItemFunction function) {
            if (!(function instanceof SetFireworksFunction)) {
                return null;
            }
            NumberProvider flightDuration = reflectField(function, "flightDuration");
            if (flightDuration instanceof ConstantValue(float value)) {
                int duration = Math.round(value);
                return Component.translatable(
                        "screen.unsuspiciousblock.archaeology_journal.item_hint.set_fireworks_flight", duration);
            }
            return null;
        }

        @Override
        public boolean addsRandomness() {
            return true;
        }
    }

    /** 处理 set_book_cover：展示成书标题与作者 */
    private static final class SetBookCoverHandler implements LootFunctionHandler {
        @Override
        @Nullable
        public ItemStack apply(ItemStack previewStack, LootItemFunction function) {
            return null;
        }

        // 描述：标题/作者复用既有文案；都缺省时如实写"无标题与作者"
        @Override
        public LootFunctionInfo describe(LootItemFunction function, JsonObject source) {
            String title = filterableText(field(source, "title"));
            String author = string(source, "author", "");
            Component description;
            if (!title.isEmpty() && !author.isEmpty()) {
                description = hint("set_book_cover", title, author);
            } else if (!title.isEmpty()) {
                description = hint("set_book_cover_title_only", title);
            } else if (!author.isEmpty()) {
                description = hint("set_book_cover_author_only", author);
            } else {
                description = fnVariant("set_book_cover", "empty");
            }
            return info(typeId(function, "set_book_cover"), description, FunctionEffectKind.COMPONENT,
                    "title", title.isEmpty() ? "absent" : title,
                    "author", author.isEmpty() ? "absent" : author,
                    "generation", Integer.toString(intValue(source, "generation", -1)));
        }

        @Override
        @Nullable
        public Component describeHint(LootItemFunction function) {
            if (!(function instanceof SetBookCoverFunction)) {
                return null;
            }
            // title 是 Filterable<String>，author 是 Optional<String>
            Object titleObj = reflectField(function, "title");
            String title = null;
            if (titleObj != null) {
                // Filterable 有 raw() 方法
                try {
                    java.lang.reflect.Method rawMethod = titleObj.getClass().getMethod("raw");
                    Object raw = rawMethod.invoke(titleObj);
                    if (raw instanceof String s) {
                        title = s;
                    }
                } catch (Exception ignored) {
                }
            }
            String author = null;
            Object authorRaw = reflectField(function, "author");
            if (authorRaw instanceof Optional<?> opt) {
                author = (String) opt.orElse(null);
            }

            if (title != null && author != null) {
                return Component.translatable(
                        "screen.unsuspiciousblock.archaeology_journal.item_hint.set_book_cover",
                        title, author);
            } else if (title != null) {
                return Component.translatable(
                        "screen.unsuspiciousblock.archaeology_journal.item_hint.set_book_cover_title_only",
                        title);
            } else if (author != null) {
                return Component.translatable(
                        "screen.unsuspiciousblock.archaeology_journal.item_hint.set_book_cover_author_only",
                        author);
            }
            return null;
        }

        @Override
        public boolean addsRandomness() {
            return true;
        }
    }

    // ==================== 类别 F：包装函数 ====================

    /** 处理 filtered：仅当物品匹配过滤器时才应用内层 modifier */
    private static final class FilteredHandler implements LootFunctionHandler {
        @Override
        @Nullable
        public ItemStack apply(ItemStack previewStack, LootItemFunction function) {
            return null;
        }

        // 描述：包装效果 + item_filter 元数据（键名固定）+ 内层 modifier 子节点；
        // 静态展开不代表无条件应用，运行时只报告实际进入的节点（§4.4）
        @Override
        public LootFunctionInfo describe(LootItemFunction function, JsonObject source) {
            String filter = predicateText(field(source, "item_filter"));
            LootItemFunction inner = reflectField(function, "modifier");
            LootFunctionInfo child = describeNested(inner, field(source, "modifier"));
            LootFunctionInfo base = info(typeId(function, "filtered"),
                    fn("filtered", Component.literal(filter)),
                    FunctionEffectKind.WRAPPER,
                    "item_filter", filter,
                    "child_count", child != null ? "1" : "0");
            if (child == null) {
                return withFidelity(base, FunctionFidelity.PARTIAL);
            }
            LootFunctionInfo withChild = withChildren(base, List.of(child));
            return child.fidelity() == FunctionFidelity.FULL
                    ? withChild
                    : withFidelity(withChild, FunctionFidelity.PARTIAL);
        }

        @Override
        public boolean addsRandomness() {
            return true;
        }
    }

    /** 处理 reference：引用数据包里的物品修饰器 */
    private static final class ReferenceHandler implements LootFunctionHandler {
        @Override
        @Nullable
        public ItemStack apply(ItemStack previewStack, LootItemFunction function) {
            return null;
        }

        // 描述：保留 modifier id；引用目标位于数据包注册表，describe 阶段没有注册表视图，
        // 不反射整个服务器注册表，因此标部分解析（§4.4）
        @Override
        public LootFunctionInfo describe(LootItemFunction function, JsonObject source) {
            ResourceLocation modifierId = idOf(field(source, "name"));
            Component description = modifierId != null
                    ? fn("reference", literalOf(modifierId))
                    : fnVariant("reference", "unresolved");
            return info(typeId(function, "reference"), description, FunctionFidelity.PARTIAL,
                    FunctionEffectKind.WRAPPER,
                    "modifier", modifierId != null ? modifierId.toString() : "unknown");
        }

        @Override
        public boolean addsRandomness() {
            return true;
        }
    }

    /** 处理 sequence：按声明顺序依次执行内部函数 */
    private static final class SequenceHandler implements LootFunctionHandler {
        @Override
        @Nullable
        public ItemStack apply(ItemStack previewStack, LootItemFunction function) {
            return null;
        }

        // 描述：按原顺序递归描述内部函数，顺序与重复次数保留在 children 中；
        // 子节点的函数自身条件由投影侧在拿到 DynamicOps 后补挂（F04）
        @Override
        public LootFunctionInfo describe(LootItemFunction function, JsonObject source) {
            List<LootItemFunction> inner = reflectField(function, "functions");
            JsonArray elements = arrayOf(field(source, "functions"));
            List<LootFunctionInfo> children = new ArrayList<>();
            boolean complete = inner != null;
            if (inner != null) {
                for (int index = 0; index < inner.size(); index++) {
                    JsonElement element = elements != null && index < elements.size() ? elements.get(index) : null;
                    LootFunctionInfo child = describeNested(inner.get(index), element);
                    if (child == null) {
                        children.add(LootFunctionDescriptions.unresolved(null, inner.get(index)));
                        complete = false;
                    } else {
                        children.add(child);
                        if (!child.isResolved()) {
                            complete = false;
                        }
                    }
                }
            }
            int count = !children.isEmpty() ? children.size() : (elements != null ? elements.size() : -1);
            Component description = count >= 0 ? fn("sequence", count) : fnVariant("sequence", "dynamic");
            LootFunctionInfo base = info(typeId(function, "sequence"), description,
                    FunctionEffectKind.WRAPPER,
                    "child_count", count >= 0 ? Integer.toString(count) : "dynamic");
            LootFunctionInfo withChild = children.isEmpty() ? base : withChildren(base, children);
            return complete ? withChild : withFidelity(withChild, FunctionFidelity.PARTIAL);
        }

        @Override
        public boolean addsRandomness() {
            return true;
        }
    }
}
