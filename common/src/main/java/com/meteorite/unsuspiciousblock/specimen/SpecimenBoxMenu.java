package com.meteorite.unsuspiciousblock.specimen;

import com.meteorite.unsuspiciousblock.item.ModItems;
import com.meteorite.unsuspiciousblock.journal.catalog.ArchaeologyJournalServerCatalog;
import com.meteorite.unsuspiciousblock.journal.state.ArchaeologyJournalState;
import com.meteorite.unsuspiciousblock.journal.state.ArchaeologyJournalStateHolder;
import com.meteorite.unsuspiciousblock.loottable.ArchaeologyLootTableCatalog.ItemDefinition;
import com.meteorite.unsuspiciousblock.loottable.ArchaeologyLootTableCatalog.TableDefinition;
import com.meteorite.unsuspiciousblock.loottable.LootResultMatcher;
import com.meteorite.unsuspiciousblock.loottable.LootResultSignature;
import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.network.payload.s2c.SyncSpecimenBoxViewPayload;
import com.meteorite.unsuspiciousblock.platform.Services;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 标本箱菜单（AbstractContainerMenu）——服务端侧菜单逻辑。
 * 管理隐藏后端容器（54 格 SpecimenBoxStorage）、战利品目录分页、
 * 3x3 逻辑槽位的存取操作，以及向客户端推送权威快照（SyncSpecimenBoxViewPayload）。
 * 所有物品操作在服务端校验后执行，客户端保持只读渲染。
 */
public class SpecimenBoxMenu extends AbstractContainerMenu {
    public static final int LOGICAL_SLOTS_PER_PAGE = 9;
    public static final int BUTTON_PREV_PAGE = 1;
    public static final int BUTTON_NEXT_PAGE = 2;
    public static final int BUTTON_PREV_TABLE = 3;
    public static final int BUTTON_NEXT_TABLE = 4;
    public static final int BUTTON_SELECT_TABLE_ABSOLUTE_BASE = 100;
    public static final int BUTTON_LOGICAL_SLOT_PRIMARY_BASE = 20;
    public static final int BUTTON_LOGICAL_SLOT_SECONDARY_BASE = 30;

    /** 菜单类型 ID，各平台注册时使用 */
    public static final ResourceLocation ID =
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "specimen_box");
    /** 菜单类型，由平台模块注册后回写 */
    public static MenuType<SpecimenBoxMenu> TYPE;

    private static final int MAX_LOGICAL_SLOT_STACK_MULTIPLIER = 4;
    private static final int BACKEND_SLOT_X = -2000;
    private static final int BACKEND_SLOT_Y = -2000;
    private static final int PLAYER_INVENTORY_X = 37;
    private static final int PLAYER_INVENTORY_Y = 146;
    private static final int HOTBAR_Y = 204;

    private final Player owner;
    private final SpecimenBoxStorage storage;
    private final DataSlot carrierHandData = DataSlot.standalone();
    private final DataSlot carrierSlotData = DataSlot.standalone();
    private final DataSlot selectedTableData = DataSlot.standalone();
    private final DataSlot pageData = DataSlot.standalone();
    @Nullable
    private SyncSpecimenBoxViewPayload lastSyncedView;

    // 无手参数构造：自动检测载体手持
    public SpecimenBoxMenu(int containerId, Inventory playerInventory) {
        this(containerId, playerInventory, detectCarrierHand(playerInventory));
    }

    // 带手持参数构造：自动解析载体槽位索引
    public SpecimenBoxMenu(int containerId, Inventory playerInventory, InteractionHand hand) {
        this(containerId, playerInventory, hand, resolveCarrierSlot(playerInventory, hand));
    }

    // 完整构造：初始化 DataSlot、后端槽位、玩家物品栏；服务端侧立即规范化选择并推送快照
    private SpecimenBoxMenu(int containerId, Inventory playerInventory, InteractionHand hand, int carrierSlotIndex) {
        super(TYPE, containerId);
        this.owner = playerInventory.player;
        this.storage = new SpecimenBoxStorage(this.owner, hand, carrierSlotIndex);
        this.carrierHandData.set(hand == InteractionHand.OFF_HAND ? 1 : 0);
        this.carrierSlotData.set(carrierSlotIndex);
        this.addDataSlot(this.carrierHandData);
        this.addDataSlot(this.carrierSlotData);
        this.addDataSlot(this.selectedTableData);
        this.addDataSlot(this.pageData);
        this.addBackendSlots();
        this.addPlayerInventorySlots(playerInventory);
        if (!this.owner.level().isClientSide()) {
            this.normalizeSelection();
            this.syncClientView();
        }
    }

    // 检测载体标本箱所在的手（主手优先）
    private static InteractionHand detectCarrierHand(Inventory playerInventory) {
        Player player = playerInventory.player;
        if (player.getMainHandItem().is(ModItems.SPECIMEN_BOX)) {
            return InteractionHand.MAIN_HAND;
        }
        if (player.getOffhandItem().is(ModItems.SPECIMEN_BOX)) {
            return InteractionHand.OFF_HAND;
        }
        return InteractionHand.MAIN_HAND;
    }

    // 根据手持解析载体物品在玩家物品栏中的槽位索引
    private static int resolveCarrierSlot(Inventory playerInventory, InteractionHand hand) {
        return hand == InteractionHand.OFF_HAND ? Inventory.SLOT_OFFHAND : playerInventory.selected;
    }

    // 添加 54 格隐藏后端槽位（屏幕外，不可直接交互）
    private void addBackendSlots() {
        for (int slot = 0; slot < SpecimenBoxState.BACKEND_SLOT_COUNT; slot++) {
            this.addSlot(new Slot(this.storage, slot, BACKEND_SLOT_X, BACKEND_SLOT_Y) {
                @Override
                public boolean mayPickup(@NotNull Player player) {
                    return false;
                }

                @Override
                public boolean mayPlace(@NotNull ItemStack stack) {
                    return false;
                }
            });
        }
    }

    // 添加玩家物品栏槽位（3×9 背包 + 9 快捷栏）
    private void addPlayerInventorySlots(Inventory playerInventory) {
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                int slotIndex = column + row * 9 + 9;
                int x = PLAYER_INVENTORY_X + column * 18;
                int y = PLAYER_INVENTORY_Y + row * 18;
                this.addSlot(this.createPlayerSlot(playerInventory, slotIndex, x, y));
            }
        }

        for (int hotbarIndex = 0; hotbarIndex < 9; hotbarIndex++) {
            int x = PLAYER_INVENTORY_X + hotbarIndex * 18;
            this.addSlot(this.createPlayerSlot(playerInventory, hotbarIndex, x, HOTBAR_Y));
        }
    }

    // 创建单一玩家槽位
    private Slot createPlayerSlot(Inventory playerInventory, int slotIndex, int x, int y) {
        return new Slot(playerInventory, slotIndex, x, y);
    }

    // 获取载体所在的手
    public InteractionHand getCarrierHand() {
        return this.carrierHandData.get() == 1 ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
    }

    // 获取载体物品的槽位索引
    public int getCarrierSlotIndex() {
        return this.carrierSlotData.get();
    }

    // 根据绝对索引生成选择表的按键 ID
    public static int absoluteTableButtonId(int absoluteTableIndex) {
        return BUTTON_SELECT_TABLE_ABSOLUTE_BASE + absoluteTableIndex;
    }

    // 根据逻辑槽位索引生成存取操作的按键 ID（区分左键/右键）
    public static int logicalSlotButtonId(int slotIndex, boolean secondaryClick) {
        return (secondaryClick ? BUTTON_LOGICAL_SLOT_SECONDARY_BASE : BUTTON_LOGICAL_SLOT_PRIMARY_BASE) + slotIndex;
    }

    @Override
    // 阻止对载体物品槽位的点击操作（防止抽出标本箱）
    public void clicked(int slotId, int button, @NotNull ClickType clickType, @NotNull Player player) {
        if (this.isCarrierMenuSlot(slotId)) {
            return;
        }
        if (clickType == ClickType.SWAP) {
            if (this.getCarrierHand() == InteractionHand.MAIN_HAND && button == this.getCarrierSlotIndex()) {
                return;
            }
            if (this.getCarrierHand() == InteractionHand.OFF_HAND && button == Inventory.SLOT_OFFHAND) {
                return;
            }
        }
        super.clicked(slotId, button, clickType, player);
    }

    @Override
    // 定期广播变更：规范化选择后检查是否需要推送新快照
    public void broadcastChanges() {
        if (!this.owner.level().isClientSide()) {
            this.normalizeSelection();
        }
        super.broadcastChanges();
        this.syncClientViewIfChanged();
    }

    @Override
    // 处理从客户端发来的菜单按钮点击：逻辑槽位存取、翻页、切换表
    public boolean clickMenuButton(@NotNull Player player, int id) {
        LogicalSlotClick logicalSlotClick = decodeLogicalSlotClick(id);
        if (logicalSlotClick != null) {
            boolean changed = this.handleLogicalSlotClick(logicalSlotClick.slotIndex(), logicalSlotClick.secondaryClick());
            if (changed) {
                this.broadcastChanges();
            }
            return changed;
        }

        List<ResourceLocation> unlockedTables = this.collectUnlockedTables();
        if (unlockedTables.isEmpty()) {
            this.selectedTableData.set(0);
            this.pageData.set(0);
            this.syncClientView();
            return false;
        }

        int selected = Mth.clamp(this.selectedTableData.get(), 0, unlockedTables.size() - 1);
        int absoluteTableIndex = id - BUTTON_SELECT_TABLE_ABSOLUTE_BASE;
        if (absoluteTableIndex >= 0 && absoluteTableIndex < unlockedTables.size()) {
            boolean changed = absoluteTableIndex != selected || this.pageData.get() != 0;
            this.selectedTableData.set(absoluteTableIndex);
            this.pageData.set(0);
            this.normalizeSelection();
            this.syncClientView();
            return changed;
        }

        boolean changed = false;
        switch (id) {
            case BUTTON_PREV_PAGE -> {
                int nextPage = Math.max(0, this.pageData.get() - 1);
                changed = nextPage != this.pageData.get();
                this.pageData.set(nextPage);
            }
            case BUTTON_NEXT_PAGE -> {
                ResourceLocation tableId = unlockedTables.get(selected);
                int nextPage = Math.min(this.pageData.get() + 1, this.maxPageFor(tableId));
                changed = nextPage != this.pageData.get();
                this.pageData.set(nextPage);
            }
            case BUTTON_PREV_TABLE -> {
                int nextSelected = Math.max(0, selected - 1);
                changed = nextSelected != selected || this.pageData.get() != 0;
                this.selectedTableData.set(nextSelected);
                this.pageData.set(0);
            }
            case BUTTON_NEXT_TABLE -> {
                int nextSelected = Math.min(unlockedTables.size() - 1, selected + 1);
                changed = nextSelected != selected || this.pageData.get() != 0;
                this.selectedTableData.set(nextSelected);
                this.pageData.set(0);
            }
        }

        if (changed) {
            this.normalizeSelection();
            this.syncClientView();
        }
        return changed;
    }

    @Nullable
    // 将 button ID 解码为逻辑槽位点击事件
    private static LogicalSlotClick decodeLogicalSlotClick(int id) {
        if (id >= BUTTON_LOGICAL_SLOT_PRIMARY_BASE && id < BUTTON_LOGICAL_SLOT_PRIMARY_BASE + LOGICAL_SLOTS_PER_PAGE) {
            return new LogicalSlotClick(id - BUTTON_LOGICAL_SLOT_PRIMARY_BASE, false);
        }
        if (id >= BUTTON_LOGICAL_SLOT_SECONDARY_BASE && id < BUTTON_LOGICAL_SLOT_SECONDARY_BASE + LOGICAL_SLOTS_PER_PAGE) {
            return new LogicalSlotClick(id - BUTTON_LOGICAL_SLOT_SECONDARY_BASE, true);
        }
        return null;
    }

    // 处理逻辑槽位点击：手中无物品 → 取出，手中有物品 → 存入
    private boolean handleLogicalSlotClick(int slotIndex, boolean secondaryClick) {
        ResolvedLogicalSlot logicalSlot = this.resolveLogicalSlot(slotIndex);
        if (logicalSlot == null) {
            return false;
        }
        return this.getCarried().isEmpty()
                ? this.extractFromLogicalSlot(logicalSlot, secondaryClick)
                : this.insertIntoLogicalSlot(logicalSlot, secondaryClick);
    }

    // 向逻辑槽位插入物品：优先合并至已有精确匹配的槽位，余量插入空槽
    private boolean insertIntoLogicalSlot(ResolvedLogicalSlot logicalSlot, boolean secondaryClick) {
        ItemStack carried = this.getCarried();
        if (carried.isEmpty() || !this.matchesLogicalSlot(carried, logicalSlot.signature())) {
            return false;
        }
        if (!this.storage.canStoreInTable(logicalSlot.tableId())) {
            return false;
        }

        int storedCount = this.countStoredItems(logicalSlot.tableId(), logicalSlot.signature());
        int remainingCapacity = carried.getMaxStackSize() * MAX_LOGICAL_SLOT_STACK_MULTIPLIER - storedCount;
        if (remainingCapacity <= 0) {
            return false;
        }

        int requested = Math.min(secondaryClick ? 1 : carried.getCount(), remainingCapacity);
        int moved = this.mergeIntoExactEntries(logicalSlot.tableId(), logicalSlot.signature(), carried, requested);
        moved += this.insertIntoEmptyEntries(logicalSlot.tableId(), logicalSlot.signature(), carried, requested - moved);
        if (moved <= 0) {
            return false;
        }

        this.setCarried(carried.isEmpty() ? ItemStack.EMPTY : carried);
        return true;
    }

    // 从逻辑槽位取出物品：左键全部取出，右键取一半（向上取整）
    private boolean extractFromLogicalSlot(ResolvedLogicalSlot logicalSlot, boolean secondaryClick) {
        for (SpecimenBoxState.Entry entry : this.storage.snapshotEntries()) {
            if (!logicalSlot.tableId().equals(entry.tableId()) || !logicalSlot.signature().equals(entry.signature())) {
                continue;
            }

            ItemStack stored = entry.stack();
            int extractedCount = secondaryClick ? Math.max(1, (stored.getCount() + 1) / 2) : stored.getCount();
            ItemStack extracted = stored.copy();
            extracted.setCount(extractedCount);

            ItemStack remaining = stored.copy();
            remaining.shrink(extractedCount);
            if (remaining.isEmpty()) {
                this.storage.clearEntry(entry.slot());
            } else {
                this.storage.setEntry(entry.slot(), entry.tableId(), entry.signature(), remaining);
            }

            this.setCarried(extracted);
            return true;
        }
        return false;
    }

    // 合并到已有精确匹配的槽位（按最大堆叠量填充）
    private int mergeIntoExactEntries(ResourceLocation tableId, LootResultSignature signature, ItemStack carried, int requested) {
        int moved = 0;
        for (SpecimenBoxState.Entry entry : this.storage.snapshotEntries()) {
            if (moved >= requested) {
                break;
            }
            if (!tableId.equals(entry.tableId()) || !signature.equals(entry.signature())) {
                continue;
            }
            if (!ItemStack.isSameItemSameComponents(entry.stack(), carried)) {
                continue;
            }

            int freeSpace = entry.stack().getMaxStackSize() - entry.stack().getCount();
            if (freeSpace <= 0) {
                continue;
            }

            int insertCount = Math.min(requested - moved, freeSpace);
            ItemStack updated = entry.stack().copy();
            updated.grow(insertCount);
            this.storage.setEntry(entry.slot(), tableId, signature, updated);
            carried.shrink(insertCount);
            moved += insertCount;
        }
        return moved;
    }

    // 插入到空后端槽位
    private int insertIntoEmptyEntries(ResourceLocation tableId, LootResultSignature signature, ItemStack carried, int requested) {
        int moved = 0;
        while (moved < requested && !carried.isEmpty()) {
            int emptySlot = this.findEmptyBackendSlot();
            if (emptySlot < 0) {
                break;
            }

            int insertCount = Math.min(requested - moved, carried.getMaxStackSize());
            ItemStack inserted = carried.copy();
            inserted.setCount(insertCount);
            this.storage.setEntry(emptySlot, tableId, signature, inserted);
            carried.shrink(insertCount);
            moved += insertCount;
        }
        return moved;
    }

    // 查找第一个空的后端槽位
    private int findEmptyBackendSlot() {
        for (int slot = 0; slot < this.storage.getContainerSize(); slot++) {
            if (this.storage.getItem(slot).isEmpty()) {
                return slot;
            }
        }
        return -1;
    }

    // 统计指定表与签名在存储中已占用的总数量
    private int countStoredItems(ResourceLocation tableId, LootResultSignature signature) {
        int storedCount = 0;
        for (SpecimenBoxState.Entry entry : this.storage.snapshotEntries()) {
            if (tableId.equals(entry.tableId()) && signature.equals(entry.signature())) {
                storedCount += entry.stack().getCount();
            }
        }
        return storedCount;
    }

    // 检查物品栈是否匹配逻辑槽位的签名
    private boolean matchesLogicalSlot(ItemStack stack, LootResultSignature signature) {
        return LootResultMatcher.matches(stack, signature);
    }

    // 判断指定 slotId 是否为载体物品所在格（主手）
    private boolean isCarrierMenuSlot(int slotId) {
        if (this.getCarrierHand() != InteractionHand.MAIN_HAND) {
            return false;
        }
        return slotId == this.menuSlotIdForPlayerSlot(this.getCarrierSlotIndex());
    }

    // 将玩家槽位索引转换为菜单 slotId（考虑后端槽位偏移）
    private int menuSlotIdForPlayerSlot(int playerSlotIndex) {
        if (playerSlotIndex >= 9 && playerSlotIndex < 36) {
            return SpecimenBoxState.BACKEND_SLOT_COUNT + (playerSlotIndex - 9);
        }
        if (playerSlotIndex >= 0 && playerSlotIndex < 9) {
            return SpecimenBoxState.BACKEND_SLOT_COUNT + 27 + playerSlotIndex;
        }
        return -1;
    }

    @Nullable
    // 根据槽位索引、选中表与当前页解析对应的逻辑槽位
    private ResolvedLogicalSlot resolveLogicalSlot(int slotIndex) {
        if (slotIndex < 0 || slotIndex >= LOGICAL_SLOTS_PER_PAGE) {
            return null;
        }

        List<ResourceLocation> unlockedTables = this.collectUnlockedTables();
        if (unlockedTables.isEmpty()) {
            return null;
        }

        int selectedIndex = Mth.clamp(this.selectedTableData.get(), 0, unlockedTables.size() - 1);
        ResourceLocation tableId = unlockedTables.get(selectedIndex);
        TableDefinition table = ArchaeologyJournalServerCatalog.getCatalog().get(tableId);
        if (table == null || table.items().isEmpty()) {
            return null;
        }

        int pageIndex = Mth.clamp(this.pageData.get(), 0, this.maxPageFor(tableId));
        int itemIndex = pageIndex * LOGICAL_SLOTS_PER_PAGE + slotIndex;
        if (itemIndex < 0 || itemIndex >= table.items().size()) {
            return null;
        }
        return new ResolvedLogicalSlot(tableId, table.items().get(itemIndex).signature());
    }

    // TODO：补充shift快速移动物品
    // 目前还不支持快速整理
    // 需要注意测试对于背包整理类模组的兼容性。快速移动逻辑应在服务端执行，客户端保持只读
    @Override
    // TODO 补充 shift 快速移动物品（目前返回空，阻塞整理操作）
    public @NotNull ItemStack quickMoveStack(@NotNull Player player, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    // 菜单关闭时将数据回写至载体物品
    public void removed(@NotNull Player player) {
        super.removed(player);
        this.storage.flush();
    }

    // TODO 补充GUI打开后物品栏状态的锁定，避免刷物品
    @Override
    // 检查菜单是否仍有效：载体物品必须仍在原位
    public boolean stillValid(@NotNull Player player) {
        if (this.getCarrierHand() == InteractionHand.OFF_HAND) {
            return player.getOffhandItem().is(ModItems.SPECIMEN_BOX);
        }
        int slotIndex = this.getCarrierSlotIndex();
        if (slotIndex >= 0 && slotIndex < player.getInventory().getContainerSize()) {
            return player.getInventory().getItem(slotIndex).is(ModItems.SPECIMEN_BOX);
        }
        return player.getMainHandItem().is(ModItems.SPECIMEN_BOX) || player.getOffhandItem().is(ModItems.SPECIMEN_BOX);
    }

    // 立即推送客户端快照（无条件）
    public void syncClientView() {
        this.syncClientView(false);
    }

    // 仅在快照有变更时推送（避免重复发包）
    private void syncClientViewIfChanged() {
        this.syncClientView(true);
    }

    // 构建并推送快照；onlyWhenChanged 时与上次快照比较避免重复
    private void syncClientView(boolean onlyWhenChanged) {
        if (!(this.owner instanceof ServerPlayer serverPlayer)) {
            return;
        }
        SyncSpecimenBoxViewPayload snapshot = this.buildSnapshot();
        if (onlyWhenChanged && snapshot.equals(this.lastSyncedView)) {
            return;
        }
        this.lastSyncedView = snapshot;
        Services.NETWORK.sendToPlayer(serverPlayer, snapshot);
    }

    // 构建包含目录、选中表、页码与逻辑槽位的完整快照
    private SyncSpecimenBoxViewPayload buildSnapshot() {
        List<ResourceLocation> unlockedTables = this.collectUnlockedTables();
        Map<ResourceLocation, TableDefinition> catalog = ArchaeologyJournalServerCatalog.getCatalog();
        List<SyncSpecimenBoxViewPayload.TableEntry> tables = new ArrayList<>(unlockedTables.size());
        for (ResourceLocation tableId : unlockedTables) {
            TableDefinition definition = catalog.get(tableId);
            if (definition != null) {
                tables.add(new SyncSpecimenBoxViewPayload.TableEntry(tableId, definition.displayName()));
            }
        }

        if (tables.isEmpty()) {
            return new SyncSpecimenBoxViewPayload(this.containerId, tables, 0, 0, 1, List.of());
        }

        int selectedIndex = Mth.clamp(this.selectedTableData.get(), 0, tables.size() - 1);
        ResourceLocation selectedTableId = tables.get(selectedIndex).tableId();
        TableDefinition selectedTable = catalog.get(selectedTableId);
        int pageCount = selectedTable == null || selectedTable.items().isEmpty()
                ? 1
                : Math.max(1, (selectedTable.items().size() + LOGICAL_SLOTS_PER_PAGE - 1) / LOGICAL_SLOTS_PER_PAGE);
        int pageIndex = Mth.clamp(this.pageData.get(), 0, pageCount - 1);

        List<SyncSpecimenBoxViewPayload.LogicalSlotEntry> logicalSlots = List.of();
        if (selectedTable != null) {
            logicalSlots = this.buildLogicalSlotEntries(selectedTableId, selectedTable, pageIndex);
        }

        return new SyncSpecimenBoxViewPayload(this.containerId, tables, selectedIndex, pageIndex, pageCount, logicalSlots);
    }

    // 构建当前页所有逻辑槽位的条目（含解锁状态、日记计数、存储计数）
    private List<SyncSpecimenBoxViewPayload.LogicalSlotEntry> buildLogicalSlotEntries(ResourceLocation tableId,
                                                                                       TableDefinition table,
                                                                                       int pageIndex) {
        Map<String, Integer> storedCounts = new LinkedHashMap<>();
        for (SpecimenBoxState.Entry entry : this.storage.snapshotEntries()) {
            if (!tableId.equals(entry.tableId())) {
                continue;
            }
            storedCounts.merge(entry.signature().toStoredKey(), entry.stack().getCount(), Integer::sum);
        }

        ArchaeologyJournalState state = this.getJournalState();
        ArchaeologyJournalState.TableProgress progress = state != null ? state.getTable(tableId) : null;
        int from = pageIndex * LOGICAL_SLOTS_PER_PAGE;
        int to = Math.min(table.items().size(), from + LOGICAL_SLOTS_PER_PAGE);
        List<SyncSpecimenBoxViewPayload.LogicalSlotEntry> logicalSlots = new ArrayList<>(to - from);
        for (int index = from; index < to; index++) {
            ItemDefinition item = table.items().get(index);
            boolean unlocked = progress != null && progress.isItemUnlocked(item.signature());
            int journalCount = progress != null ? progress.getItemCount(item.signature()) : 0;
            int storedCount = storedCounts.getOrDefault(item.signature().toStoredKey(), 0);
            logicalSlots.add(new SyncSpecimenBoxViewPayload.LogicalSlotEntry(
                    item.displayName(),
                    item.signature().toStoredKey(),
                    unlocked,
                    journalCount,
                    storedCount
            ));
        }
        return List.copyOf(logicalSlots);
    }

    // 规范化选中表与页码至合法范围
    private void normalizeSelection() {
        List<ResourceLocation> unlockedTables = this.collectUnlockedTables();
        if (unlockedTables.isEmpty()) {
            this.selectedTableData.set(0);
            this.pageData.set(0);
            return;
        }

        int selected = Mth.clamp(this.selectedTableData.get(), 0, unlockedTables.size() - 1);
        ResourceLocation tableId = unlockedTables.get(selected);
        this.selectedTableData.set(selected);
        this.pageData.set(Mth.clamp(this.pageData.get(), 0, this.maxPageFor(tableId)));
    }

    // 计算指定表的最大页码（基于逻辑槽位分页）
    private int maxPageFor(ResourceLocation tableId) {
        TableDefinition definition = ArchaeologyJournalServerCatalog.getCatalog().get(tableId);
        if (definition == null || definition.items().isEmpty()) {
            return 0;
        }
        return Math.max(0, (definition.items().size() - 1) / LOGICAL_SLOTS_PER_PAGE);
    }

    // 收集所有已解锁的战利品表（排序：minecraft 优先，按命名空间+名称排序）
    private List<ResourceLocation> collectUnlockedTables() {
        if (this.owner.level().getServer() != null) {
            ArchaeologyJournalServerCatalog.ensureLoaded(this.owner.level().getServer());
        }
        ArchaeologyJournalState state = this.getJournalState();
        if (state == null) {
            return List.of();
        }

        Map<ResourceLocation, TableDefinition> catalog = ArchaeologyJournalServerCatalog.getCatalog();
        List<ResourceLocation> unlockedTables = new ArrayList<>();
        for (Map.Entry<ResourceLocation, TableDefinition> entry : catalog.entrySet()) {
            ArchaeologyJournalState.TableProgress progress = state.getTable(entry.getKey());
            if (progress != null && progress.isUnlocked()) {
                unlockedTables.add(entry.getKey());
            }
        }
        unlockedTables.sort(Comparator
                .comparing((ResourceLocation id) -> !"minecraft".equals(id.getNamespace()))
                .thenComparing(ResourceLocation::getNamespace)
                .thenComparing(id -> catalog.get(id).displayName().getString())
                .thenComparing(ResourceLocation::getPath));
        return unlockedTables;
    }

    // 获取玩家的考古日记状态（通过 mixin 接口）
    private ArchaeologyJournalState getJournalState() {
        if (this.owner instanceof ArchaeologyJournalStateHolder holder) {
            return holder.unsuspiciousblock$getArchaeologyJournalState();
        }
        return null;
    }

    private record LogicalSlotClick(int slotIndex, boolean secondaryClick) {
    }

    private record ResolvedLogicalSlot(ResourceLocation tableId, LootResultSignature signature) {
    }
}
