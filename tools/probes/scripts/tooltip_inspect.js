/* 纯检查物品 Tooltip 的可复用探针辅助脚本（服务端装配测试箱 + 客户端提取文本/色值/宽度与槽位悬停）。 */

Probe.buildTooltipSamples = function() {
    var items = this.type('com.meteorite.unsuspiciousblock.core.item.ModItems');
    var Stack = this.type('net.minecraft.world.item.ItemStack');
    var VanillaItems = this.type('net.minecraft.world.item.Items');
    var DataComponents = this.type('net.minecraft.core.component.DataComponents');
    var PotDecorations = this.type('net.minecraft.world.level.block.entity.PotDecorations');
    var SealedContents = this.type('com.meteorite.unsuspiciousblock.core.block.SealedContents');
    var SuspiciousReaderItem = this.type('com.meteorite.unsuspiciousblock.core.item.SuspiciousReaderItem');
    var HandOfCatItem = this.type('com.meteorite.unsuspiciousblock.core.item.HandOfCatItem');
    var CustomData = this.type('net.minecraft.world.item.component.CustomData');
    var ItemContainerContents = this.type('net.minecraft.world.item.component.ItemContainerContents');
    var ArrayList = this.type('java.util.ArrayList');
    var Optional = this.type('java.util.Optional');
    var UUID = this.type('java.util.UUID');
    var player = this.player();

    function sealedBlock(blockItem, innerStack) {
        var s = new Stack(blockItem);
        SealedContents.seal(s, innerStack);
        SealedContents.recordCrafter(s, player);
        return s;
    }

    function potWith(b, l, r, f) {
        var s = new Stack(items.UNFIRED_DECORATED_POT);
        s.set(DataComponents.POT_DECORATIONS, new PotDecorations(Optional.of(b), Optional.of(l), Optional.of(r), Optional.of(f)));
        return s;
    }

    function sherdWith(pattern) {
        var s = new Stack(items.UNFIRED_DECORATED_SHERD);
        s.set(DataComponents.POT_DECORATIONS, new PotDecorations(Optional.of(pattern), Optional.empty(), Optional.empty(), Optional.empty()));
        return s;
    }

    function filledBox() {
        var s = new Stack(items.SPECIMEN_BOX);
        var list = new ArrayList();
        list.add(new Stack(items.EYE_OF_CAT));
        list.add(new Stack(VanillaItems.TOTEM_OF_UNDYING));
        list.add(new Stack(items.ANCIENT_COIN, 4));
        s.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(list));
        return s;
    }

    function readerWith(level, energy) {
        var s = new Stack(items.SUSPICIOUS_READER);
        SuspiciousReaderItem.setScanLevel(s, level);
        SuspiciousReaderItem.setEnergy(s, energy);
        return s;
    }

    function boundHandSelf() {
        var s = new Stack(items.HAND_OF_CAT);
        HandOfCatItem.bindTo(s, player);
        return s;
    }

    function boundHandOther() {
        var s = new Stack(items.HAND_OF_CAT);
        CustomData.update(DataComponents.CUSTOM_DATA, s, function(tag) {
            tag.putString('owner_uuid', UUID.fromString('00000000-0000-0000-0000-000000000003').toString());
            tag.putString('owner_name', 'Alex');
        });
        return s;
    }

    return [
        { id: 'unsuspicious_sand_empty', stack: new Stack(items.UNSUSPICIOUS_SAND) },
        { id: 'unsuspicious_sand_sealed', stack: sealedBlock(items.UNSUSPICIOUS_SAND, new Stack(VanillaItems.DIAMOND, 3)) },
        { id: 'unsuspicious_gravel_empty', stack: new Stack(items.UNSUSPICIOUS_GRAVEL) },
        { id: 'unsuspicious_gravel_sealed', stack: sealedBlock(items.UNSUSPICIOUS_GRAVEL, new Stack(items.ANCIENT_COIN, 2)) },
        { id: 'pottery_wheel', stack: new Stack(items.POTTERY_WHEEL) },
        { id: 'unfired_decorated_pot_empty', stack: new Stack(items.UNFIRED_DECORATED_POT) },
        { id: 'unfired_decorated_pot_patterned', stack: potWith(VanillaItems.ANGLER_POTTERY_SHERD, VanillaItems.ARMS_UP_POTTERY_SHERD, VanillaItems.BLADE_POTTERY_SHERD, VanillaItems.BRICK) },
        { id: 'unfired_decorated_sherd_empty', stack: new Stack(items.UNFIRED_DECORATED_SHERD) },
        { id: 'unfired_decorated_sherd_patterned', stack: sherdWith(VanillaItems.HEART_POTTERY_SHERD) },
        { id: 'archaeology_journal', stack: new Stack(items.ARCHAEOLOGY_JOURNAL) },
        { id: 'suspicious_reader_full', stack: readerWith(0, 512) },
        { id: 'suspicious_reader_low', stack: readerWith(3, 100) },
        { id: 'archaeological_shovel', stack: new Stack(items.ARCHAEOLOGICAL_SHOVEL) },
        { id: 'copper_pan', stack: new Stack(items.COPPER_PAN) },
        { id: 'gold_pan', stack: new Stack(items.GOLD_PAN) },
        { id: 'obsidian_pan', stack: new Stack(items.OBSIDIAN_PAN) },
        { id: 'specimen_box_empty', stack: new Stack(items.SPECIMEN_BOX) },
        { id: 'specimen_box_filled', stack: filledBox() },
        { id: 'ancient_coin', stack: new Stack(items.ANCIENT_COIN) },
        { id: 'lost_page', stack: new Stack(items.LOST_PAGE) },
        { id: 'base_page', stack: new Stack(items.BASE_PAGE) },
        { id: 'spark_dust', stack: new Stack(items.SPARK_DUST) },
        { id: 'rewind_dust', stack: new Stack(items.REWIND_DUST) },
        { id: 'eye_of_cat', stack: new Stack(items.EYE_OF_CAT) },
        { id: 'hand_of_cat_unbound', stack: new Stack(items.HAND_OF_CAT) },
        { id: 'hand_of_cat_self', stack: boundHandSelf() },
        { id: 'hand_of_cat_other', stack: boundHandOther() }
    ];
};

Probe.prepareTooltipChest = function() {
    var player = this.player();
    this.command('gamemode survival UsbProbe');
    this.command('gamerule doDaylightCycle false');
    this.command('time set day');
    this.command('fill -3 99 -3 3 99 3 minecraft:stone');
    this.command('fill -3 100 -3 3 103 3 minecraft:air');
    this.command('tp UsbProbe 0.5 100 -0.5 0 35');
    this.command('setblock 0 100 1 minecraft:chest');
    var pos = new (this.type('net.minecraft.core.BlockPos'))(0, 100, 1);
    var chest = player.serverLevel().getBlockEntity(pos);
    var samples = this.buildTooltipSamples();
    var ids = [];
    for (var i = 0; i < samples.length; i++) {
        chest.setItem(i, samples[i].stack);
        ids.push(samples[i].id);
    }
    chest.setChanged();
    player.openMenu(chest);
    return { slots: ids };
};

/* ===== 客户端能力 ===== */

Probe.tooltipShiftState = false;
Probe.originalTooltipHooksInstalled = false;

Probe.ensureTooltipBridgeProxy = function() {
    if (this.originalTooltipHooksInstalled) return true;
    var ClientTooltipBridge = this.type('com.meteorite.unsuspiciousblock.core.text.ClientTooltipBridge');
    var ClientTooltipHooks = this.type('com.meteorite.unsuspiciousblock.client.tooltip.ClientTooltipHooks');
    var HooksInterface = this.type('com.meteorite.unsuspiciousblock.core.text.ClientTooltipBridge$Hooks');
    var delegate = new ClientTooltipHooks();
    var self = this;
    var proxy = new HooksInterface({
        isExpandKeyDown: function() { return Boolean(self.tooltipShiftState); },
        splitLines: function(text, maxWidth) { return delegate.splitLines(text, maxWidth); },
        openJournal: function() { delegate.openJournal(); },
        handOfCatFavor: function() { return delegate.handOfCatFavor(); },
        handOfCatLives: function() { return delegate.handOfCatLives(); },
        isLocalPlayer: function(uuid) { return delegate.isLocalPlayer(uuid); }
    });
    ClientTooltipBridge.install(proxy);
    this.originalTooltipHooksInstalled = true;
    return true;
};

Probe.setTooltipShift = function(down) {
    this.ensureTooltipBridgeProxy();
    this.tooltipShiftState = Boolean(down);
    return this.tooltipShiftState;
};

Probe.setCatClientState = function(favor, lives, established) {
    var Payload = this.type('com.meteorite.unsuspiciousblock.core.network.payload.s2c.SyncCatFavorPayload');
    var State = this.type('com.meteorite.unsuspiciousblock.client.state.HandOfCatClientState');
    State.receive(new Payload(Number(favor), Number(lives), Boolean(established)));
    this.client().getToasts().clear();
    return true;
};

Probe.containerReady = function(expectedSlots) {
    var screen = this.client().screen;
    var AbstractContainerScreen = this.type('net.minecraft.client.gui.screens.inventory.AbstractContainerScreen');
    if (screen === null || !(screen instanceof AbstractContainerScreen)) return false;
    var menu = screen.getMenu();
    for (var i = 0; i < expectedSlots; i++) {
        if (menu.getSlot(i).getItem().isEmpty()) return false;
    }
    this.client().getToasts().clear();
    return true;
};

Probe.inspectSlotTooltip = function(slotIndex, advanced) {
    var client = this.client();
    var screen = client.screen;
    var stack = screen.getMenu().getSlot(Number(slotIndex)).getItem();
    var TooltipContext = this.type('net.minecraft.world.item.Item$TooltipContext');
    var TooltipFlag = this.type('net.minecraft.world.item.TooltipFlag');
    var StyledConsumer = this.type('net.minecraft.network.chat.FormattedText$StyledContentConsumer');
    var Style = this.type('net.minecraft.network.chat.Style');
    var Optional = this.type('java.util.Optional');
    var flag = advanced ? TooltipFlag.ADVANCED : TooltipFlag.NORMAL;
    var lines = stack.getTooltipLines(TooltipContext.of(client.level), client.player, flag);
    var font = client.font;
    var result = [];
    for (var i = 0; i < lines.size(); i++) {
        var line = lines.get(i);
        var spans = [];
        line.visit(new StyledConsumer({
            accept: function(style, part) {
                var color = style.getColor();
                var hex = null;
                var colorName = null;
                if (color !== null) {
                    colorName = String(color.serialize());
                    hex = '#' + ('000000' + (Number(color.getValue()) & 0xFFFFFF).toString(16)).slice(-6);
                }
                spans.push({
                    text: String(part),
                    color: colorName,
                    hex: hex,
                    italic: Boolean(style.isItalic()),
                    bold: Boolean(style.isBold())
                });
                return Optional.empty();
            }
        }), Style.EMPTY);
        result.push({
            index: i,
            text: String(line.getString()),
            widthPx: Number(font.width(line)),
            spans: spans
        });
    }
    return {
        item: String(stack.getItem()),
        hasTooltipImage: Boolean(stack.getTooltipImage().isPresent()),
        lines: result
    };
};

Probe.hoverContainerSlot = function(slotIndex) {
    var client = this.client();
    var screen = client.screen;
    var slot = screen.getMenu().getSlot(Number(slotIndex));
    var x = Number(screen.getGuiLeft()) + Number(slot.x) + 8;
    var y = Number(screen.getGuiTop()) + Number(slot.y) + 8;
    var window = client.getWindow();
    var px = x * window.getScreenWidth() / window.getGuiScaledWidth();
    var py = y * window.getScreenHeight() / window.getGuiScaledHeight();
    this.movePointer(x, y);
    client.getToasts().clear();
    return { slot: Number(slotIndex), x: x, y: y, px: px, py: py };
};

Probe.isContainerSlotHovered = function(slotIndex) {
    var client = this.client();
    var screen = client.screen;
    if (screen === null) return false;
    var target = screen.getMenu().getSlot(Number(slotIndex));
    var hovered = screen.getSlotUnderMouse();
    return hovered !== null && hovered === target;
};
