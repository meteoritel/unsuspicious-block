Probe.preparePanning = function(variant) {
    if (this.panTarget && !this.panTarget.isRemoved()) this.panTarget.discard();
    var player = this.player(), level = player.serverLevel();
    this.command('fill -4 99 -4 4 99 4 minecraft:stone');
    this.command('fill -4 100 -4 4 104 4 minecraft:air');
    this.command('tp UsbProbe 0.5 100 -1.5 0 22');
    this.command('setblock 0 100 1 minecraft:' + (variant === 'water' ? 'water' : 'lava'));
    var entities = this.type('com.meteorite.unsuspiciousblock.core.entity.ModEntities');
    this.panTarget = (variant === 'water' ? entities.SHIMMER : entities.GLIMMER).get().create(level);
    this.panTarget.initializeAt(new (this.type('net.minecraft.core.BlockPos'))(0, 100, 1), false, 1000000);
    level.addFreshEntity(this.panTarget);
    player.releaseUsingItem();
    player.getInventory().clearContent();
    player.getInventory().selected = 0;
    player.setItemInHand(this.type('net.minecraft.world.InteractionHand').MAIN_HAND,
            new (this.type('net.minecraft.world.item.ItemStack'))(this.type('com.meteorite.unsuspiciousblock.core.item.ModItems').COPPER_PAN));
    player.inventoryMenu.broadcastChanges();
    return this.panningState();
};
Probe.panningState = function() {
    var player = this.player(), target = this.panTarget;
    var Pan = this.type('com.meteorite.unsuspiciousblock.core.item.PanItem');
    var result = this.journal('unsuspiciousblock:gameplay/panning/river');
    var gold = this.type('net.minecraft.core.registries.BuiltInRegistries').ITEM.get(this.id('minecraft:gold_nugget'));
    var total = this.inventoryCount('minecraft:gold_nugget');
    var drops = player.serverLevel().getEntitiesOfClass(this.type('net.minecraft.world.entity.item.ItemEntity'),
            new (this.type('net.minecraft.world.phys.AABB'))(-6, 98, -6, 6, 107, 6)).iterator();
    while (drops.hasNext()) { var stack = drops.next().getItem(); if (stack.is(gold)) total += Number(stack.getCount()); }
    result.produced = total;
    result.remaining = target.getPanRemaining();
    result.damage = player.getMainHandItem().getDamageValue();
    result.using = player.isUsingItem();
    result.targeted = Pan.findTargetedShimmer(player) === target;
    result.tick = Number(this.server().getTickCount());
    result.duration = player.getMainHandItem().getUseDuration(player);
    return result;
};
Probe.beginPanning = function() {
    var result = this.panTarget.interact(this.player(), this.type('net.minecraft.world.InteractionHand').MAIN_HAND);
    return {interaction: String(result), state: this.panningState()};
};
Probe.cleanPanning = function() {
    this.player().releaseUsingItem();
    if (this.panTarget && !this.panTarget.isRemoved()) this.panTarget.discard();
    this.command('fill -4 100 -4 4 104 4 minecraft:air');
    this.player().clearFire();
    return true;
};
