Probe.archStack = function(id, count) {
    var ItemStack = this.type('net.minecraft.world.item.ItemStack');
    var items = this.type('net.minecraft.core.registries.BuiltInRegistries').ITEM;
    return new ItemStack(items.get(this.id(id)), count);
};

Probe.archItemId = function(stack) {
    return String(this.type('net.minecraft.core.registries.BuiltInRegistries').ITEM.getKey(stack.getItem()));
};

Probe.archOpenBox = function(offhand) {
    var player = this.player();
    var hand = this.type('net.minecraft.world.InteractionHand');
    var components = this.type('net.minecraft.core.component.DataComponents');
    var contents = this.type('net.minecraft.world.item.component.ItemContainerContents');
    var items = new (this.type('java.util.ArrayList'))();
    items.add(this.archStack('minecraft:totem_of_undying', 1));
    items.add(this.archStack('minecraft:stone', 1));
    var box = this.archStack('unsuspiciousblock:specimen_box', 1);
    box.set(components.CONTAINER, contents.fromItems(items));

    player.closeContainer();
    player.getInventory().clearContent();
    player.getInventory().selected = 0;
    player.setItemInHand(hand.MAIN_HAND, offhand ? this.archStack('unsuspiciousblock:specimen_box', 1) : box);
    if (offhand) player.setItemInHand(hand.OFF_HAND, box);
    box.getItem().use(player.serverLevel(), player, offhand ? hand.OFF_HAND : hand.MAIN_HAND);
    this.archBox = box;
    this.archBoxSlot = offhand ? 40 : 0;
    return player.containerMenu.getSlot(0).hasItem();
};

Probe.archConsumeBoxTotem = function() {
    var player = this.player();
    player.removeAllEffects();
    player.setAbsorptionAmount(0);
    player.setHealth(player.getMaxHealth());
    player.invulnerableTime = 0;
    player.hurt(player.serverLevel().damageSources().generic(), 1000);
    var menu = player.containerMenu;
    var click = this.type('net.minecraft.world.inventory.ClickType');
    menu.clicked(1, 0, click.QUICK_MOVE, player);
    var contents = this.type('com.meteorite.unsuspiciousblock.core.specimen.SpecimenBoxContents').read(this.archBox);
    var replacement = this.archStack('unsuspiciousblock:specimen_box', 1);
    player.getInventory().setItem(this.archBoxSlot, replacement);
    return {alive: player.isAlive(), consumed: contents.get(0).isEmpty(),
            moved: contents.get(1).isEmpty() && this.inventoryCount('minecraft:stone') === 1,
            invalid: !menu.stillValid(player)};
};

Probe.archOpenSmithing = function(full) {
    var player = this.player();
    var inventory = player.getInventory();
    player.closeContainer();
    inventory.clearContent();
    if (full) {
        for (var index = 0; index < 36; index++) inventory.setItem(index, this.archStack('minecraft:cobblestone', 64));
    }
    var pos = new (this.type('net.minecraft.core.BlockPos'))(0, 100, 1);
    this.command('setblock 0 100 1 minecraft:smithing_table');
    var level = player.serverLevel();
    player.openMenu(level.getBlockState(pos).getMenuProvider(level, pos));
    var menu = player.containerMenu;
    menu.getSlot(0).set(this.archStack('unsuspiciousblock:base_page', 1));
    menu.getSlot(1).set(this.archStack('minecraft:book', 1));
    menu.getSlot(2).set(this.archStack('unsuspiciousblock:lost_page', 1));
    return menu.getSlot(3).hasItem();
};

Probe.archTakeSmithing = function(mode) {
    var player = this.player();
    var menu = player.containerMenu;
    var click = this.type('net.minecraft.world.inventory.ClickType');
    menu.clicked(3, 0, click[mode], player);
    var book = menu.getCarried();
    var enchanted = this.type('net.minecraft.world.item.Items').ENCHANTED_BOOK;
    var inventory = player.getInventory();
    for (var index = 0; !book.is(enchanted) && index < inventory.getContainerSize(); index++) {
        var item = inventory.getItem(index);
        if (item.is(enchanted)) book = item;
    }
    var components = this.type('net.minecraft.core.component.DataComponents');
    var stored = this.type('net.minecraft.world.item.enchantment.ItemEnchantments');
    var roller = this.type('com.meteorite.unsuspiciousblock.core.enchantment.EnchantedBookRoller');
    return {enchanted: book.is(enchanted) && !book.getOrDefault(components.STORED_ENCHANTMENTS, stored.EMPTY).isEmpty(),
            emptyInputs: !menu.getSlot(0).hasItem() && !menu.getSlot(1).hasItem() && !menu.getSlot(2).hasItem(),
            pending: roller.isPending(menu.getSlot(3).getItem())};
};

Probe.archBreakBone = function(enchanted) {
    var player = this.player();
    var level = player.serverLevel();
    var tracker = this.type('com.meteorite.unsuspiciousblock.core.world.NaturalBoneBlockTracker');
    var pos = new (this.type('net.minecraft.core.BlockPos'))(0, 100, 1);
    this.command('setblock 0 100 1 minecraft:bone_block');
    tracker.markNatural(level, pos);
    var tool = this.archStack('minecraft:diamond_pickaxe', 1);
    if (enchanted) {
        var keys = this.type('net.minecraft.core.registries.Registries');
        var enchant = this.type('net.minecraft.resources.ResourceKey').create(keys.ENCHANTMENT,
            this.id('unsuspiciousblock:fossil_hunter'));
        tool.enchant(level.registryAccess().registryOrThrow(keys.ENCHANTMENT).getHolderOrThrow(enchant), 1);
        tool.setDamageValue(tool.getMaxDamage() - 1);
        player.getRandom().setSeed(4096);
    }
    player.getInventory().setItem(0, tool);
    player.getInventory().selected = 0;
    var broken = player.gameMode.destroyBlock(pos);
    var bone = this.type('net.minecraft.world.item.Items').BONE;
    var entityClass = this.type('net.minecraft.world.entity.item.ItemEntity');
    var bounds = new (this.type('net.minecraft.world.phys.AABB'))(-1, 99, 0, 2, 103, 3);
    var drops = level.getEntitiesOfClass(entityClass, bounds).iterator();
    var bonus = false;
    while (drops.hasNext()) {
        if (drops.next().getItem().is(bone)) bonus = true;
    }
    return {broken: broken && level.getBlockState(pos).isAir(), cleared: !tracker.isNatural(level, pos),
            emptyTool: player.getMainHandItem().isEmpty(), bonus: bonus};
};

Probe.archPottery = function() {
    this.command('setblock 0 100 1 unsuspiciousblock:pottery_wheel');
    var Pos = this.type('net.minecraft.core.BlockPos');
    var wheel = this.player().serverLevel().getBlockEntity(new Pos(0, 100, 1));
    var Wheel = this.type('com.meteorite.unsuspiciousblock.core.blockentity.PotteryWheelBlockEntity');
    for (var slot = Wheel.TOP; slot <= Wheel.BOTTOM; slot++) {
        wheel.setItem(slot, this.archStack('minecraft:angler_pottery_sherd', 1));
    }
    wheel.setItem(Wheel.CLAY, this.archStack('minecraft:clay_ball', 1));
    var water = this.archStack('minecraft:potion', 1);
    water.set(this.type('net.minecraft.core.component.DataComponents').POTION_CONTENTS,
        new (this.type('net.minecraft.world.item.alchemy.PotionContents'))(
            this.type('net.minecraft.world.item.alchemy.Potions').WATER));
    wheel.setItem(Wheel.WATER, water);
    var preview = Wheel.getPreviewResult(wheel);
    this.archWheel = wheel;
    return this.archItemId(preview);
};

Probe.archPotteryResult = function() {
    var Wheel = this.type('com.meteorite.unsuspiciousblock.core.blockentity.PotteryWheelBlockEntity');
    return {output: this.archItemId(this.archWheel.getItem(Wheel.OUTPUT)),
            water: this.archItemId(this.archWheel.getItem(Wheel.WATER)),
            clayEmpty: this.archWheel.getItem(Wheel.CLAY).isEmpty()};
};

Probe.archPrepareMerchant = function() {
    var level = this.player().serverLevel();
    var merchant = this.type('com.meteorite.unsuspiciousblock.core.entity.ModEntities').MERCHANT_CAT.get().create(level);
    merchant.moveTo(0.5, 101, 0.5, 0, 0);
    merchant.activateDuty(12);
    var saved = this.type('com.meteorite.unsuspiciousblock.core.cat.merchant.MerchantCatSpawnData').get(level);
    var spawned = level.addFreshEntity(merchant);
    if (spawned) saved.recordSuccess(merchant);
    this.archMerchant = merchant;
    merchant.setRemoved(this.type('net.minecraft.world.entity.Entity$RemovalReason').UNLOADED_TO_CHUNK);
    return {spawned: spawned, unloaded: level.getEntity(merchant.getUUID()) === null,
            occupied: saved.hasActiveMerchant(level)};
};

Probe.archMerchantOccupied = function() {
    var level = this.player().serverLevel();
    return this.type('com.meteorite.unsuspiciousblock.core.cat.merchant.MerchantCatSpawnData').get(level).hasActiveMerchant(level);
};

Probe.archCatalogTable = function(path) {
    var id = this.id(path);
    var catalog = this.type('com.meteorite.unsuspiciousblock.core.journal.catalog.ArchaeologyJournalServerCatalog');
    var definition = catalog.staticTable(id);
    var staticItems = {};
    var items = definition.items().iterator();
    while (items.hasNext()) {
        var item = items.next();
        var route = item.acquisitionPaths().getFirst();
        var gating = route.allConditions().stream().map(function(condition) {
            return String(condition.kind().name());
        }).toList();
        staticItems[String(item.id())] = {blockedByWeight: route.luckImpossible(),
                                        gating: String(gating)};
    }
    var server = this.server();
    var keys = this.type('net.minecraft.core.registries.Registries');
    var key = this.type('net.minecraft.resources.ResourceKey').create(keys.LOOT_TABLE, id);
    var loot = server.reloadableRegistries().getLootTable(key);
    var builder = new (this.type('net.minecraft.world.level.storage.loot.LootParams$Builder'))(server.overworld());
    builder.withParameter(this.type('net.minecraft.world.level.storage.loot.parameters.LootContextParams').ORIGIN,
        new (this.type('net.minecraft.world.phys.Vec3'))(0.5, 100, 0.5));
    var params = builder.create(this.type('net.minecraft.world.level.storage.loot.parameters.LootContextParamSets').ARCHAEOLOGY);
    var generated = {};
    for (var index = 0; index < 24; index++) {
        var drops = loot.getRandomItems(params).iterator();
        while (drops.hasNext()) generated[this.archItemId(drops.next())] = true;
    }
    return {staticItems: staticItems, drops: Object.keys(generated).sort(),
            child: definition.childTables().contains(this.id('usb_probe:archaeology/architecture_leaf'))};
};

Probe.graphProjection = function(path) {
    var server = this.server();
    var registries = server.registryAccess();
    var Snapshot = this.type('com.meteorite.unsuspiciousblock.core.loottable.source.LootTableSourceSnapshot');
    var Graph = this.type('com.meteorite.unsuspiciousblock.core.loottable.graph.LootTableReferenceGraph');
    var Compiler = this.type('com.meteorite.unsuspiciousblock.core.loottable.analysis.LootTableCompiler');
    var Projector = this.type('com.meteorite.unsuspiciousblock.core.loottable.catalog.LootTableProjector');
    var Map = this.type('java.util.Map');
    var Set = this.type('java.util.Set');
    var id = this.id(path);
    var snapshot = Snapshot.capture(server.getResourceManager());
    var graph = Graph.build(snapshot, Map.of());
    var compiled = new Compiler(registries).compile(snapshot, graph.descendantsInclusive(id));
    return function() { return new Projector(graph, compiled, Set.of(), registries).project(id); };
};

Probe.graphFacts = function(result) {
    var items = [], iterator = result.items().iterator();
    while (iterator.hasNext()) items.push(String(iterator.next().id()));
    return {items: items.sort(), paths: items.length === 0 ? 0 : Number(result.items().getFirst().acquisitionPaths().size())};
};

Probe.archProjectGraph = function(path) {
    return this.graphFacts(this.graphProjection(path)());
};

Probe.archTradeOffers = function() {
    var offers = this.type('com.meteorite.unsuspiciousblock.core.cat.merchant.MerchantCatTradeManager')
        .createOffers(this.server().overworld(), this.type('net.minecraft.util.RandomSource').create(1234), 100);
    var candidates = [];
    var iterator = offers.iterator();
    while (iterator.hasNext()) candidates.push(this.archItemId(iterator.next().getResult()));
    return candidates.sort();
};

Probe.archLedgerDeadline = function() {
    var level = this.player().serverLevel();
    var Ledger = this.type('com.meteorite.unsuspiciousblock.core.pan.ShimmerLedger');
    var source = this.type('com.meteorite.unsuspiciousblock.core.pan.ShimmerLedger$Source');
    var uuid = this.type('java.util.UUID').randomUUID();
    var pos = new (this.type('net.minecraft.core.BlockPos'))(100, 100, 100);
    var ledger = Ledger.of(level);
    var variant = this.type('com.meteorite.unsuspiciousblock.core.pan.variant.ShimmerVariants').WATER_ID;
    this.archLedgerUuid = uuid;
    this.archLedgerBaseline = Number(ledger.countNatural(variant));
    ledger.register(uuid, pos, source.NATURAL, variant, level.getGameTime() + 4);
    return Number(ledger.countNatural(variant)) === this.archLedgerBaseline + 1;
};

Probe.archLedgerExpired = function() {
    var ledger = this.type('com.meteorite.unsuspiciousblock.core.pan.ShimmerLedger').of(this.player().serverLevel());
    var variant = this.type('com.meteorite.unsuspiciousblock.core.pan.variant.ShimmerVariants').WATER_ID;
    return ledger.isExpired(this.archLedgerUuid) && Number(ledger.countNatural(variant)) === this.archLedgerBaseline;
};

Probe.archCatalogState = function() {
    var server = this.server();
    var catalog = this.type('com.meteorite.unsuspiciousblock.core.journal.catalog.ArchaeologyJournalServerCatalog');
    var resource = this.id('usb_probe:archaeology/architecture_ordered');
    var cache = this.type('com.meteorite.unsuspiciousblock.core.world.LootProbabilityData').get(server.overworld());
    var snapshot = catalog.readSnapshot();
    var rows = snapshot.payload().catalog().iterator();
    var cached = false;
    while (rows.hasNext()) {
        var row = rows.next();
        if (row.id().equals(resource)) {
            cached = !cache.needsResimulation(resource, row.hash());
            break;
        }
    }
    return {generation: Number(snapshot.generation()), hash: String(snapshot.hash()),
            canExecute: catalog.canExecute(server), failed: catalog.hasRebuildFailed(), cached: cached};
};

Probe.archWriteLegacyState = function(folder) {
    var UUID = this.type('java.util.UUID');
    var Compound = this.type('net.minecraft.nbt.CompoundTag');
    var NbtIo = this.type('net.minecraft.nbt.NbtIo');
    var Paths = this.type('java.nio.file.Paths');
    var version = this.type('net.minecraft.SharedConstants').getCurrentVersion().getDataVersion().getVersion();
    var merchantId = UUID.fromString('8211ac00-6b06-4d93-9ea0-8201b3f6c581');
    var shimmerId = UUID.fromString('bd148693-e0b8-4c3f-a1c7-b97cb7ad67aa');

    function write(name, data) {
        var outer = new Compound();
        outer.put('data', data);
        outer.putInt('DataVersion', version);
        NbtIo.writeCompressed(outer, Paths.get(folder, name));
    }

    var merchant = new Compound();
    merchant.putUUID('active_merchant', merchantId);
    merchant.putLong('next_attempt', this.server().overworld().getGameTime() + 200);
    write('unsuspiciousblock_merchant_cat.dat', merchant);
    var shimmer = new Compound();
    var entry = new Compound();
    var entries = new (this.type('net.minecraft.nbt.ListTag'))();
    entry.putUUID('uuid', shimmerId);
    entry.putLong('pos', new (this.type('net.minecraft.core.BlockPos'))(500, 100, 500).asLong());
    entries.add(entry);
    shimmer.put('entries', entries);
    write('unsuspiciousblock_shimmer.dat', shimmer);
    return true;
};

Probe.archReadLegacyState = function() {
    var level = this.server().overworld();
    var UUID = this.type('java.util.UUID');
    var Compound = this.type('net.minecraft.nbt.CompoundTag');
    var merchant = this.type('com.meteorite.unsuspiciousblock.core.cat.merchant.MerchantCatSpawnData').get(level);
    var shimmer = this.type('com.meteorite.unsuspiciousblock.core.pan.ShimmerLedger').of(level);
    var occupied = merchant.hasActiveMerchant(level);
    var data = merchant.save(new Compound(), level.registryAccess());
    return {occupied: occupied, merchantDeadline: Number(data.getLong('active_until')),
            shimmerDeadline: Number(shimmer.expirationOf(UUID.fromString('bd148693-e0b8-4c3f-a1c7-b97cb7ad67aa'), -1)),
            time: Number(level.getGameTime())};
};

'core architecture installed';
