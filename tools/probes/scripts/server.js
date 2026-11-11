Probe.server = function() { return this.type('net.neoforged.neoforge.server.ServerLifecycleHooks').getCurrentServer(); };
Probe.player = function() { return this.server().getPlayerList().getPlayerByName('UsbProbe'); };
Probe.artifact = function() {
    var mod = this.type('net.neoforged.fml.ModList').get().getModFileById('unsuspiciousblock');
    return {path: String(mod.getFile().getFilePath().toAbsolutePath()),
            minecraft: String(this.server().getServerVersion()),
            players: this.server().getPlayerList().getPlayerCount()};
};
Probe.ready = function() {
    var player = this.player();
    return {player: player !== null, uuid: player === null ? null : String(player.getUUID()),
            tick: Number(this.server().getTickCount())};
};
'server installed';

Probe.id = function(value) { return this.type('net.minecraft.resources.ResourceLocation').parse(value); };
Probe.command = function(command) {
    var server = this.server();
    server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), command);
    return true;
};
Probe.catalogReady = function(table) {
    var catalog = this.type('com.meteorite.unsuspiciousblock.core.journal.catalog.ArchaeologyJournalServerCatalog');
    var id = this.id(table);
    var definition = catalog.staticTable(id);
    return {tracked: catalog.tracked(id), exists: definition !== null,
            generation: Number(catalog.currentGenerationId())};
};
Probe.prepareScan = function(table) {
    var player = this.player();
    this.command('gamemode survival UsbProbe');
    this.command('gamerule doDaylightCycle false');
    this.command('gamerule doWeatherCycle false');
    this.command('time set day');
    this.command('weather clear');
    this.command('fill -4 99 -4 4 99 4 minecraft:stone');
    this.command('fill -4 100 -4 4 104 4 minecraft:air');
    this.command('tp UsbProbe 0.5 100 -1.5 0 22');
    player.getInventory().clearContent();
    player.getInventory().selected = 0;
    var items = this.type('com.meteorite.unsuspiciousblock.core.item.ModItems');
    var stack = this.type('net.minecraft.world.item.ItemStack');
    var hand = this.type('net.minecraft.world.InteractionHand');
    player.setItemInHand(hand.MAIN_HAND, new stack(items.SUSPICIOUS_READER));
    player.setItemInHand(hand.OFF_HAND, new stack(items.ARCHAEOLOGICAL_SHOVEL));
    player.getInventory().setItem(1, new stack(items.ARCHAEOLOGY_JOURNAL));
    this.command('setblock 0 100 1 minecraft:suspicious_sand{LootTable:"' + table + '",LootTableSeed:1L}');
    player.inventoryMenu.broadcastChanges();
    return this.scanState(table);
};
Probe.journal = function(table) {
    var player = this.player();
    var state = this.type('com.meteorite.unsuspiciousblock.core.journal.state.ArchaeologyJournalStateHolder').getState(player);
    var logs = this.type('com.meteorite.unsuspiciousblock.core.world.JournalLogStorage').getPlayerState(this.server(), player.getUUID());
    return this.progress(state, logs, table);
};
Probe.scanState = function(table) {
    var player = this.player();
    var pos = new (this.type('net.minecraft.core.BlockPos'))(0, 100, 1);
    var block = player.serverLevel().getBlockEntity(pos);
    var result = this.journal(table);
    result.scanned = block !== null && block.unsuspiciousblock$isScanner(player.getUUID());
    result.pending = block !== null && block.unsuspiciousblock$pendingJournal().current() !== null;
    result.loot = block === null ? 0 : Number(block.unsuspiciousblock$getItem().getCount());
    result.iron = this.inventoryCount('minecraft:iron_ingot');
    result.emerald = this.inventoryCount('minecraft:emerald');
    result.coins = this.inventoryCount('unsuspiciousblock:ancient_coin');
    return result;
};
Probe.inventoryCount = function(itemId) {
    var item = this.type('net.minecraft.core.registries.BuiltInRegistries').ITEM.get(this.id(itemId));
    var inventory = this.player().getInventory();
    var total = 0;
    for (var i = 0; i < inventory.getContainerSize(); i++) { var stack = inventory.getItem(i); if (stack.is(item)) total += Number(stack.getCount()); }
    return total;
};
