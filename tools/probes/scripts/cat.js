Probe.catLedger = function() {
    var State = this.type('com.meteorite.unsuspiciousblock.core.cat.state.CatFavorState');
    var Ledger = this.type('com.meteorite.unsuspiciousblock.core.cat.state.CatBondLedger');
    var Event = this.type('com.meteorite.unsuspiciousblock.core.cat.state.CatBondLedger$Event');
    var Behavior = this.type('com.meteorite.unsuspiciousblock.core.cat.state.CatBondLedger$Behavior');
    var SetBond = this.type('com.meteorite.unsuspiciousblock.core.cat.state.CatBondLedger$SetBond');
    var Action = this.type('com.meteorite.unsuspiciousblock.core.cat.CatFavorAction');
    var UUID = this.type('java.util.UUID');
    var cat = UUID.fromString('00000000-0000-0000-0000-000000000001');
    var other = UUID.fromString('00000000-0000-0000-0000-000000000002');
    var state = new State(), result = {};
    function step(name, mutation, tick) {
        var change = Ledger.apply(state, mutation, tick);
        result[name] = [change.bond(), change.lives(), change.relationship(), String(change.stage()), change.applied()];
    }
    step('unrelated', new Behavior(Action.FEED_CAT, null), 0);
    step('establish', Event.ESTABLISH_RELATIONSHIP, 0);
    step('feed', new Behavior(Action.FEED_CAT, null), 0);
    step('cooldown', new Behavior(Action.FEED_CAT, null), 11999);
    step('boundary', new Behavior(Action.FEED_CAT, null), 12000);
    Ledger.apply(state, new SetBond(19), 12001);
    step('stage', new Behavior(Action.SIT_ON_BLOCK, null), 12001);
    Ledger.apply(state, new SetBond(100), 12002);
    step('initial-life', Event.GRANT_INITIAL_LIFE, 12002);
    for (var i = 0; i < 8; i++) Ledger.apply(state, Event.MESSENGER_GIFT, 12003 + i);
    step('life-cap', Event.MESSENGER_GIFT, 12012);
    step('hit', new Behavior(Action.HIT_CAT, cat), 20000);
    step('fatal-hit', new Behavior(Action.KILL_CAT, cat), 20000);
    Ledger.apply(state, new SetBond(100), 20001);
    step('no-second-initial-life', Event.GRANT_INITIAL_LIFE, 20001);
    Ledger.apply(state, new Behavior(Action.HIT_CAT, cat), 30000);
    step('other-cat', new Behavior(Action.KILL_CAT, other), 30000);
    Ledger.apply(state, new SetBond(100), 40000);
    Ledger.apply(state, new Behavior(Action.HIT_CAT, cat), 40000);
    step('other-tick', new Behavior(Action.KILL_CAT, cat), 40001);
    var restored = new State();
    restored.readFrom(state.toTag());
    result.restored = [restored.getCatBond(), restored.getNineLivesCount(), restored.isRelationshipEstablished(),
                       restored.isInitialBestFriendLifeGranted(), restored.canTrigger(Action.FEED_CAT, 23999),
                       restored.canTrigger(Action.FEED_CAT, 24000)];
    return result;
};

Probe.specimenWith = function(stack) {
    var items = this.type('com.meteorite.unsuspiciousblock.core.item.ModItems');
    var box = new (this.type('net.minecraft.world.item.ItemStack'))(items.SPECIMEN_BOX);
    var components = this.type('net.minecraft.core.component.DataComponents');
    var contents = this.type('net.minecraft.world.item.component.ItemContainerContents');
    box.set(components.CONTAINER, contents.fromItems(this.type('java.util.List').of(stack)));
    return box;
};
Probe.prepareForeignToken = function() {
    var items = this.type('com.meteorite.unsuspiciousblock.core.item.ModItems');
    var stack = new (this.type('net.minecraft.world.item.ItemStack'))(items.HAND_OF_CAT);
    var tag = new (this.type('net.minecraft.nbt.CompoundTag'))();
    tag.putString('owner_uuid', '00000000-0000-0000-0000-000000000003');
    tag.putString('owner_name', 'ProbeOther');
    stack.set(this.type('net.minecraft.core.component.DataComponents').CUSTOM_DATA,
              this.type('net.minecraft.world.item.component.CustomData').of(tag));
    this.player().getInventory().clearContent();
    this.player().getInventory().setItem(0, this.specimenWith(stack));
    return this.bindingState();
};
Probe.prepareBlankTokens = function() {
    var items = this.type('com.meteorite.unsuspiciousblock.core.item.ModItems');
    var Stack = this.type('net.minecraft.world.item.ItemStack');
    this.player().getInventory().clearContent();
    this.player().getInventory().setItem(0, this.specimenWith(new Stack(items.HAND_OF_CAT)));
    this.player().getInventory().setItem(1, new Stack(items.HAND_OF_CAT));
    return this.bindingState();
};
Probe.bindingState = function() {
    var player = this.player(), inventory = player.getInventory();
    var contents = this.type('com.meteorite.unsuspiciousblock.core.specimen.SpecimenBoxContents');
    var token = contents.read(inventory.getItem(0)).get(0);
    var item = this.type('com.meteorite.unsuspiciousblock.core.item.HandOfCatItem');
    return {owned: this.type('com.meteorite.unsuspiciousblock.core.cat.HandOfCatBinding').hasOwned(player),
            relationship: this.type('com.meteorite.unsuspiciousblock.core.cat.CatFavorManager').view(player).isRelationshipEstablished(),
            boxedOwner: String(item.getOwnerUuid(token).map(function(uuid) { return String(uuid); }).orElse('')),
            extraBound: item.isBound(inventory.getItem(1)), tick: Number(this.server().getTickCount())};
};
Probe.prepareCatJourney = function() {
    this.resetCat();
    var player = this.player(), level = player.serverLevel();
    this.command('gamemode survival UsbProbe');
    this.command('fill -4 99 -4 4 99 4 minecraft:stone');
    this.command('fill -4 100 -4 4 104 4 minecraft:air');
    this.command('tp UsbProbe 0.5 100 -1.5 0 22');
    this.feedCat = this.type('net.minecraft.world.entity.EntityType').CAT.create(level);
    this.feedCat.setTame(true, false);
    this.feedCat.setOwnerUUID(player.getUUID());
    this.feedCat.setNoAi(true);
    this.feedCat.setNoGravity(true);
    this.feedCat.setPos(0.5, 100, 1.5);
    this.feedCat.setHealth(5);
    level.addFreshEntity(this.feedCat);
    player.getInventory().selected = 0;
    player.getInventory().setItem(0, new (this.type('net.minecraft.world.item.ItemStack'))(this.type('net.minecraft.world.item.Items').COD, 3));
    player.inventoryMenu.broadcastChanges();
    return this.catJourneyState();
};
Probe.carryCatToken = function() {
    this.player().getInventory().setItem(2, new (this.type('net.minecraft.world.item.ItemStack'))(
        this.type('com.meteorite.unsuspiciousblock.core.item.ModItems').HAND_OF_CAT));
    this.player().inventoryMenu.broadcastChanges();
    return true;
};
Probe.catJourneyState = function() {
    var player = this.player(), view = this.type('com.meteorite.unsuspiciousblock.core.cat.CatFavorManager').view(player);
    var token = player.getInventory().getItem(2);
    return {bond: view.getCatBond(), lives: view.getNineLivesCount(), relationship: view.isRelationshipEstablished(),
        owned: this.type('com.meteorite.unsuspiciousblock.core.cat.HandOfCatBinding').hasOwned(player),
        tokenOwner: String(this.type('com.meteorite.unsuspiciousblock.core.item.HandOfCatItem').getOwnerUuid(token).orElse(null)),
        target: String(this.feedCat.getUUID()), owner: String(this.feedCat.getOwnerUUID()), health: this.feedCat.getHealth(),
        food: this.inventoryCount('minecraft:cod'), tick: Number(this.server().getTickCount())};
};
Probe.injureFeedCat = function() {
    this.feedCat.setHealth(5);
    return this.catJourneyState();
};
