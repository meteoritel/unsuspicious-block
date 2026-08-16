package com.meteorite.unsuspiciousblock.journal.state;

import com.meteorite.unsuspiciousblock.Constants;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * 日志条目的战利品来源类型——替代旧版 enum 的类 + 注册表模式。
 * <p>
 * 外部模组可通过 {@link #register} 注册自定义来源类型，突破 enum 的封闭限制。
 * 日志结算行为由 {@code LootSettlementStrategy} 在每次会话提交时决定，来源类型只负责展示与序列化。
 * <p>
 * 序列化使用 {@link ResourceLocation} 格式（如 {@code unsuspiciousblock:archaeology}），
 * 旧格式名称的转换由 JournalNbtMigrator 集中处理。
 */
public final class LootSourceType {
    private static final Map<ResourceLocation, LootSourceType> REGISTRY = new LinkedHashMap<>();

    // 内建常量
    public static final LootSourceType ARCHAEOLOGY = register(
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "archaeology"),
            Component.translatable("screen.unsuspiciousblock.archaeology_journal.loot_source_type.archaeology"),
            () -> new ItemStack(Items.BRUSH),
            false);

    public static final LootSourceType LOOT_CONTAINER = register(
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "loot_container"),
            Component.translatable("screen.unsuspiciousblock.archaeology_journal.loot_source_type.loot_container"),
            () -> new ItemStack(Items.CHEST),
            false);

    public static final LootSourceType FISHING = register(
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "fishing"),
            Component.translatable("screen.unsuspiciousblock.archaeology_journal.loot_source_type.fishing"),
            () -> new ItemStack(Items.FISHING_ROD),
            true);

    public static final LootSourceType FOSSIL_HUNTER = register(
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "fossil_hunter"),
            Component.translatable("screen.unsuspiciousblock.archaeology_journal.loot_source_type.fossil_hunter"),
            () -> new ItemStack(Items.BONE),
            true);

    public static final LootSourceType DECORATED_POT = register(
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "decorated_pot"),
            Component.translatable("screen.unsuspiciousblock.archaeology_journal.loot_source_type.decorated_pot"),
            () -> new ItemStack(Items.DECORATED_POT),
            true);

    private final ResourceLocation id;
    private final Component displayName;
    private final Supplier<ItemStack> iconItem;
    private final boolean directLogCreation;

    private LootSourceType(ResourceLocation id, Component displayName, Supplier<ItemStack> iconItem,
                           boolean directLogCreation) {
        this.id = Objects.requireNonNull(id, "id");
        this.displayName = Objects.requireNonNull(displayName, "displayName");
        this.iconItem = Objects.requireNonNull(iconItem, "iconItem");
        this.directLogCreation = directLogCreation;
    }

    // 注册新的来源类型；若 id 已存在则返回已注册的实例（幂等）
    public static LootSourceType register(ResourceLocation id, Component displayName,
                                          Supplier<ItemStack> iconItem, boolean directLogCreation) {
        LootSourceType existing = REGISTRY.get(id);
        if (existing != null) {
            return existing;
        }
        LootSourceType type = new LootSourceType(id, displayName, iconItem, directLogCreation);
        REGISTRY.put(id, type);
        return type;
    }

    // 返回唯一标识符（如 unsuspiciousblock:archaeology）
    public ResourceLocation id() {
        return this.id;
    }

    // 返回本地化显示名
    public Component displayName() {
        return this.displayName;
    }

    // 返回图标物品栈
    public ItemStack iconItem() {
        return this.iconItem.get();
    }

    // 旧版结算提示；新代码应在提交 LootSession 时显式选择 LootSettlementStrategy
    @Deprecated
    public boolean isDirectLogCreation() {
        return this.directLogCreation;
    }

    // 返回注册表只读视图
    public static Map<ResourceLocation, LootSourceType> registry() {
        return Collections.unmodifiableMap(REGISTRY);
    }

    // 根据当前格式的 ResourceLocation 字符串反序列化
    @Nullable
    public static LootSourceType fromId(String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        // 新格式：ResourceLocation 字符串
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl != null) {
            LootSourceType type = REGISTRY.get(rl);
            if (type != null) {
                return type;
            }
        }
        return null;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof LootSourceType that)) return false;
        return this.id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return this.id.hashCode();
    }

    @Override
    public String toString() {
        return "LootSourceType{" + this.id + '}';
    }
}
