package com.meteorite.unsuspiciousblock.specimen;

import com.meteorite.unsuspiciousblock.item.ModItems;
import com.meteorite.unsuspiciousblock.loottable.LootResultSignature;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 标本箱隐藏后端容器——负责精确槽位内容与载体物品回写。 */
public class SpecimenBoxStorage extends SimpleContainer {
    private final Player owner;
    private final InteractionHand carrierHand;
    private final int carrierSlotIndex;
    private final List<SpecimenBoxState.Entry> slotEntries;
    private boolean suppressWriteBack;

    public SpecimenBoxStorage(Player owner, InteractionHand carrierHand, int carrierSlotIndex) {
        super(SpecimenBoxState.BACKEND_SLOT_COUNT);
        this.owner = owner;
        this.carrierHand = carrierHand;
        this.carrierSlotIndex = carrierSlotIndex;
        this.slotEntries = new ArrayList<>(Collections.nCopies(SpecimenBoxState.BACKEND_SLOT_COUNT, null));
        if (!owner.level().isClientSide()) {
            this.loadFromCarrier();
        }
    }

    @Nullable
    public SpecimenBoxState.Entry getEntry(int slot) {
        return this.isValidSlot(slot) ? this.slotEntries.get(slot) : null;
    }

    public void setEntry(int slot, ResourceLocation tableId, LootResultSignature signature, ItemStack stack) {
        if (!this.isValidSlot(slot)) {
            return;
        }
        if (stack.isEmpty()) {
            this.clearEntry(slot);
            return;
        }

        this.slotEntries.set(slot, new SpecimenBoxState.Entry(slot, tableId, signature, stack));
        super.setItem(slot, stack.copy());
    }

    public void clearEntry(int slot) {
        if (!this.isValidSlot(slot)) {
            return;
        }
        this.slotEntries.set(slot, null);
        super.setItem(slot, ItemStack.EMPTY);
    }

    public boolean canStoreInTable(ResourceLocation tableId) {
        return this.toState().canStoreInTable(tableId);
    }

    public int getActiveTableCount() {
        return this.toState().activeTables().size();
    }

    public List<SpecimenBoxState.Entry> snapshotEntries() {
        return this.toState().entries();
    }

    public void flush() {
        this.writeBack();
    }

    @Override
    public void setItem(int slot, @NotNull ItemStack stack) {
        if (!this.isValidSlot(slot)) {
            return;
        }
        if (stack.isEmpty()) {
            this.slotEntries.set(slot, null);
        } else {
            SpecimenBoxState.Entry entry = this.slotEntries.get(slot);
            if (entry == null || !ItemStack.isSameItemSameComponents(entry.stack(), stack)) {
                this.slotEntries.set(slot, null);
            }
        }
        super.setItem(slot, stack);
    }

    @Override
    public void clearContent() {
        this.slotEntries.replaceAll(ignored -> null);
        super.clearContent();
    }

    @Override
    public void setChanged() {
        super.setChanged();
        if (!this.suppressWriteBack) {
            this.writeBack();
        }
    }

    private void loadFromCarrier() {
        this.suppressWriteBack = true;
        this.clearContent();
        SpecimenBoxState state = SpecimenBoxState.read(this.resolveCarrierStack(), this.owner.level().registryAccess());
        for (SpecimenBoxState.Entry entry : state.entries()) {
            if (!this.isValidSlot(entry.slot())) {
                continue;
            }
            this.slotEntries.set(entry.slot(), entry.copy());
            super.setItem(entry.slot(), entry.stack().copy());
        }
        this.suppressWriteBack = false;
    }

    private void writeBack() {
        if (this.owner.level().isClientSide()) {
            return;
        }

        ItemStack carrier = this.resolveCarrierStack();
        if (!carrier.is(ModItems.SPECIMEN_BOX)) {
            return;
        }
        this.toState().writeTo(carrier, this.owner.level().registryAccess());
    }

    private SpecimenBoxState toState() {
        List<SpecimenBoxState.Entry> entries = new ArrayList<>();
        for (int slot = 0; slot < this.getContainerSize(); slot++) {
            ItemStack stack = this.getItem(slot);
            SpecimenBoxState.Entry entry = this.slotEntries.get(slot);
            if (stack.isEmpty() || entry == null) {
                continue;
            }
            entries.add(new SpecimenBoxState.Entry(slot, entry.tableId(), entry.signature(), stack));
        }
        return SpecimenBoxState.of(entries);
    }

    private ItemStack resolveCarrierStack() {
        if (this.carrierHand == InteractionHand.OFF_HAND) {
            return this.owner.getOffhandItem();
        }
        if (this.carrierSlotIndex >= 0 && this.carrierSlotIndex < this.owner.getInventory().getContainerSize()) {
            return this.owner.getInventory().getItem(this.carrierSlotIndex);
        }
        return this.owner.getMainHandItem();
    }

    private boolean isValidSlot(int slot) {
        return slot >= 0 && slot < this.getContainerSize();
    }
}
