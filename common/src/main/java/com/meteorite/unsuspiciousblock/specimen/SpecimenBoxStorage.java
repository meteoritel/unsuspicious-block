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

/**
 * 标本箱隐藏后端容器——继承 SimpleContainer 管理 54 格隐藏物品存储。
 * 维护 slotEntries 列表精确跟踪每个槽位的归属表与 LootResultSignature，
 * 在数据变更时自动回写至载体物品（SpecimenBoxItem）的 CUSTOM_DATA。
 */
public class SpecimenBoxStorage extends SimpleContainer {
    private final Player owner;
    private final InteractionHand carrierHand;
    private final int carrierSlotIndex;
    // 精确槽位归属追踪（slot → Entry），与服务端原始槽位保持一一对应
    private final List<SpecimenBoxState.Entry> slotEntries;
    // 抑制回写标记，loadFromCarrier 时防止循环写入
    private boolean suppressWriteBack;

    // 构造时从载体物品 NBT 加载后端槽位内容
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

    // 设置指定槽位：写入 tableId、signature 与物品栈；空栈触发清除
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

    // 清除指定槽位的 Entry 与虚拟物品
    public void clearEntry(int slot) {
        if (!this.isValidSlot(slot)) {
            return;
        }
        this.slotEntries.set(slot, null);
        super.setItem(slot, ItemStack.EMPTY);
    }

    // 检查指定表是否还可以存入新物品
    public boolean canStoreInTable(ResourceLocation tableId) {
        return this.toState().canStoreInTable(tableId);
    }

    // 获取当前所有 Entry 的不可变快照
    public List<SpecimenBoxState.Entry> snapshotEntries() {
        return this.toState().entries();
    }

    // 立即回写数据至载体物品
    public void flush() {
        this.writeBack();
    }

    @Override
    // 覆盖 setItem：当外部直接调用时，失配的堆叠也会使 slotEntries 失效
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
    // 清除所有槽位内容
    public void clearContent() {
        Collections.fill(this.slotEntries, null);
        super.clearContent();
    }

    @Override
    // 标记脏数据并触发回写
    public void setChanged() {
        super.setChanged();
        if (!this.suppressWriteBack) {
            this.writeBack();
        }
    }

    // 从载体物品解析 SpecimenBoxState 并填充容器
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

    // 将当前状态回写到载体物品的 NBT
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

    // 将容器数据组装为 SpecimenBoxState
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

    // 获取当前载体物品栈
    private ItemStack resolveCarrierStack() {
        if (this.carrierHand == InteractionHand.OFF_HAND) {
            return this.owner.getOffhandItem();
        }
        if (this.carrierSlotIndex >= 0 && this.carrierSlotIndex < this.owner.getInventory().getContainerSize()) {
            return this.owner.getInventory().getItem(this.carrierSlotIndex);
        }
        return this.owner.getMainHandItem();
    }

    // 检查槽位索引是否合法
    private boolean isValidSlot(int slot) {
        return slot >= 0 && slot < this.getContainerSize();
    }
}
