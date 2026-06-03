package com.meteorite.unsuspiciousblock.specimen;

import com.meteorite.unsuspiciousblock.loottable.LootResultSignature;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 标本箱物品内的持久化状态——管理物品 NBT 中的后端槽位数据。
 * 负责 Entries 的序列化/反序列化、激活表限制（MAX_ACTIVE_TABLES）、
 * 脏槽清理（sanitize），以及与 ItemStack CUSTOM_DATA 的读写。
 */
public final class SpecimenBoxState {
    // 最大激活表数——超过后不能再存入新表物品
    public static final int MAX_ACTIVE_TABLES = 6;
    // 后端隐藏容器总槽位数（54 = 双箱子容量）
    public static final int BACKEND_SLOT_COUNT = 54;

    // NBT 键名常量
    private static final String ROOT_KEY = "specimen_box";
    private static final String ENTRIES_KEY = "entries";
    private static final String SLOT_KEY = "slot";
    private static final String TABLE_KEY = "table";
    private static final String SIGNATURE_KEY = "signature";
    private static final String STACK_KEY = "stack";

    // 已清理的 Entry 列表（只读）
    private final List<Entry> entries;

    private SpecimenBoxState(List<Entry> entries) {
        this.entries = entries;
    }

    // 创建空状态
    public static SpecimenBoxState empty() {
        return new SpecimenBoxState(List.of());
    }

    // 从 Entry 列表创建，自动执行 sanitize 清理
    public static SpecimenBoxState of(List<Entry> entries) {
        return new SpecimenBoxState(sanitize(entries));
    }

    // 从载体物品的 CUSTOM_DATA 中读取并反序列化状态
    public static SpecimenBoxState read(ItemStack carrier, HolderLookup.Provider registries) {
        CustomData customData = carrier.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
        CompoundTag rootTag = customData.copyTag();
        if (!rootTag.contains(ROOT_KEY, Tag.TAG_COMPOUND)) {
            return empty();
        }

        CompoundTag specimenTag = rootTag.getCompound(ROOT_KEY);
        ListTag entriesTag = specimenTag.getList(ENTRIES_KEY, Tag.TAG_COMPOUND);
        List<Entry> loadedEntries = new ArrayList<>(entriesTag.size());
        for (int index = 0; index < entriesTag.size(); index++) {
            Entry entry = Entry.fromTag(entriesTag.getCompound(index), registries);
            if (entry != null) {
                loadedEntries.add(entry);
            }
        }
        return new SpecimenBoxState(sanitize(loadedEntries));
    }

    // 获取已清理的 Entry 列表
    public List<Entry> entries() {
        return this.entries;
    }

    // 收集当前所有条目涉及的激活表 ID
    public Set<ResourceLocation> activeTables() {
        LinkedHashSet<ResourceLocation> tables = new LinkedHashSet<>();
        for (Entry entry : this.entries) {
            tables.add(entry.tableId());
        }
        return Set.copyOf(tables);
    }

    // 检查是否还能存入指定表（已激活则返回 true，未激活且未达上限也返回 true）
    public boolean canStoreInTable(ResourceLocation tableId) {
        if (tableId == null) {
            return false;
        }
        Set<ResourceLocation> tables = this.activeTables();
        return tables.contains(tableId) || tables.size() < MAX_ACTIVE_TABLES;
    }

    // 将状态序列化写入载体物品的 CUSTOM_DATA
    public void writeTo(ItemStack carrier, HolderLookup.Provider registries) {
        List<Entry> sanitizedEntries = sanitize(this.entries);
        CustomData.update(DataComponents.CUSTOM_DATA, carrier, tag -> {
            if (sanitizedEntries.isEmpty()) {
                tag.remove(ROOT_KEY);
                return;
            }

            CompoundTag specimenTag = new CompoundTag();
            ListTag entriesTag = new ListTag();
            for (Entry entry : sanitizedEntries) {
                entriesTag.add(entry.toTag(registries));
            }
            specimenTag.put(ENTRIES_KEY, entriesTag);
            tag.put(ROOT_KEY, specimenTag);
        });
    }

    // 清理已损坏、重复、超限图表的脏数据槽位
    private static List<Entry> sanitize(List<Entry> entries) {
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }

        Entry[] slots = new Entry[BACKEND_SLOT_COUNT];
        LinkedHashSet<ResourceLocation> activeTables = new LinkedHashSet<>();
        for (Entry entry : entries) {
            if (entry == null || entry.stack().isEmpty() || !isValidSlot(entry.slot())) {
                continue;
            }
            if (slots[entry.slot()] != null) {
                continue;
            }

            ResourceLocation tableId = entry.tableId();
            if (!activeTables.contains(tableId) && activeTables.size() >= MAX_ACTIVE_TABLES) {
                continue;
            }
            activeTables.add(tableId);
            slots[entry.slot()] = entry.copy();
        }

        List<Entry> sanitizedEntries = new ArrayList<>();
        for (Entry entry : slots) {
            if (entry != null) {
                sanitizedEntries.add(entry);
            }
        }
        return List.copyOf(sanitizedEntries);
    }

    // 检查槽位索引是否在合法范围内
    private static boolean isValidSlot(int slot) {
        return slot >= 0 && slot < BACKEND_SLOT_COUNT;
    }

    /** 单个后端精确物品槽位的持久化条目。 */
    public record Entry(int slot, ResourceLocation tableId, LootResultSignature signature, ItemStack stack) {
        public Entry {
            stack = stack.copy();
        }

        // 复制当前条目
        public Entry copy() {
            return new Entry(this.slot, this.tableId, this.signature, this.stack);
        }

        @Nullable
        // 从 NBT Compound 反序列化条目
        public static Entry fromTag(CompoundTag tag, HolderLookup.Provider registries) {
            int slot = tag.getInt(SLOT_KEY);
            ResourceLocation tableId = ResourceLocation.tryParse(tag.getString(TABLE_KEY));
            LootResultSignature signature = LootResultSignature.fromStoredKey(tag.getString(SIGNATURE_KEY));
            if (!isValidSlot(slot) || tableId == null || signature == null || !tag.contains(STACK_KEY, Tag.TAG_COMPOUND)) {
                return null;
            }

            ItemStack stack = ItemStack.parseOptional(registries, tag.getCompound(STACK_KEY));
            if (stack.isEmpty()) {
                return null;
            }
            ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
            if (!signature.itemId().equals(itemId)) {
                return null;
            }
            return new Entry(slot, tableId, signature, stack);
        }

        // 将条目序列化为 NBT Compound
        public CompoundTag toTag(HolderLookup.Provider registries) {
            CompoundTag tag = new CompoundTag();
            tag.putInt(SLOT_KEY, this.slot);
            tag.putString(TABLE_KEY, this.tableId.toString());
            tag.putString(SIGNATURE_KEY, this.signature.toStoredKey());
            tag.put(STACK_KEY, this.stack.save(registries));
            return tag;
        }
    }
}
