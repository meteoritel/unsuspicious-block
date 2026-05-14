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

/** 标本箱物品内的持久化状态——当前阶段先保存后端精确物品槽位。 */
public final class SpecimenBoxState {
    public static final int MAX_ACTIVE_TABLES = 6;
    public static final int BACKEND_SLOT_COUNT = 54;

    private static final String ROOT_KEY = "specimen_box";
    private static final String ENTRIES_KEY = "entries";
    private static final String SLOT_KEY = "slot";
    private static final String TABLE_KEY = "table";
    private static final String SIGNATURE_KEY = "signature";
    private static final String STACK_KEY = "stack";

    private final List<Entry> entries;

    private SpecimenBoxState(List<Entry> entries) {
        this.entries = entries;
    }

    public static SpecimenBoxState empty() {
        return new SpecimenBoxState(List.of());
    }

    public static SpecimenBoxState of(List<Entry> entries) {
        return new SpecimenBoxState(sanitize(entries));
    }

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

    public List<Entry> entries() {
        return this.entries;
    }

    public Set<ResourceLocation> activeTables() {
        LinkedHashSet<ResourceLocation> tables = new LinkedHashSet<>();
        for (Entry entry : this.entries) {
            tables.add(entry.tableId());
        }
        return Set.copyOf(tables);
    }

    public boolean canStoreInTable(ResourceLocation tableId) {
        if (tableId == null) {
            return false;
        }
        Set<ResourceLocation> tables = this.activeTables();
        return tables.contains(tableId) || tables.size() < MAX_ACTIVE_TABLES;
    }

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

    private static boolean isValidSlot(int slot) {
        return slot >= 0 && slot < BACKEND_SLOT_COUNT;
    }

    /** 单个后端精确物品槽位的持久化条目。 */
    public record Entry(int slot, ResourceLocation tableId, LootResultSignature signature, ItemStack stack) {
        public Entry {
            stack = stack.copy();
        }

        public Entry copy() {
            return new Entry(this.slot, this.tableId, this.signature, this.stack);
        }

        @Nullable
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
