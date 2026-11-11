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
    var contents = this.type('com.meteorite.unsuspiciousblock.core.specimen.SpecimenBoxContents');
    var values = contents.read(box), previous = contents.copy(values);
    values.set(0, stack);
    contents.writeIfChanged(box, previous, values);
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
