Probe.preparePanning = function(variant) {
    if (this.panTarget && !this.panTarget.isRemoved()) this.panTarget.discard();
    if (this.panSecond && !this.panSecond.isRemoved()) this.panSecond.discard();
    this.panSecond = null;
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
Probe.prepareSecondPanningTarget = function() {
    this.command('setblock 1 100 1 minecraft:water');
    var level = this.player().serverLevel();
    var target = this.type('com.meteorite.unsuspiciousblock.core.entity.ModEntities').SHIMMER.get().create(level);
    target.initializeAt(new (this.type('net.minecraft.core.BlockPos'))(1, 100, 1), false, 1000000);
    if (!level.addFreshEntity(target)) throw new Error('第二个淘洗点未加入世界');
    this.panSecond = target;
    return this.panningState();
};
Probe.tapPanning = function() {
    var client = this.client();
    this.type('net.minecraft.client.KeyMapping').click(this.type('com.mojang.blaze3d.platform.InputConstants$Type').MOUSE.getOrCreate(1));
    client.options.keyUse.setDown(false);
    return true;
};
Probe.aimPanningAtSecond = function() {
    // 只改变服务端视线，客户端持续按键并停在原目标上。
    this.player().setYRot(-18.435);
    return true;
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
    result.target = String(target.getUUID());
    result.remaining = target.getPanRemaining();
    result.damage = player.getMainHandItem().getDamageValue();
    result.using = player.isUsingItem();
    result.targeted = Pan.findTargetedShimmer(player) === target;
    result.targetingSecond = this.panSecond !== null && Pan.findTargetedShimmer(player) === this.panSecond;
    result.secondRemaining = this.panSecond === null ? null : this.panSecond.getPanRemaining();
    var session = this.type('com.meteorite.unsuspiciousblock.core.pan.PanningSession').of(player);
    result.session = session === null ? null : {
        target: String(session.target().getUUID()), duration: Number(session.duration()), startedAt: String(session.startedAt())
    };
    result.tick = Number(this.server().getTickCount());
    result.duration = player.getMainHandItem().getUseDuration(player);
    return result;
};
Probe.panningClientState = function() {
    var player = this.client().player;
    var state = this.type('com.meteorite.unsuspiciousblock.client.pan.ClientPanningState').of(player);
    return state === null ? null : {
        medium: String(state.medium().name()), hand: String(state.hand().name()),
        duration: Number(state.duration()), startedAt: String(state.startedAt())
    };
};
Probe.panningClientProgress = function() {
    var state = this.type('com.meteorite.unsuspiciousblock.client.ui.support.ArchaeologyJournalClientState').getState();
    var snapshot = this.progress(state, null, 'unsuspiciousblock:gameplay/panning/river');
    return {unlocked: snapshot.unlocked, claimed: snapshot.claimed, items: snapshot.items};
};
Probe.panningClientLogCount = function() {
    var mirror = this.type('com.meteorite.unsuspiciousblock.client.ui.support.JournalClientConnection').log();
    var summary = mirror.summary(this.id('unsuspiciousblock:gameplay/panning/river'));
    return summary === null ? 0 : Number(summary.count());
};
Probe.cleanPanning = function() {
    this.player().releaseUsingItem();
    if (this.panTarget && !this.panTarget.isRemoved()) this.panTarget.discard();
    if (this.panSecond && !this.panSecond.isRemoved()) this.panSecond.discard();
    this.command('fill -4 100 -4 4 104 4 minecraft:air');
    this.player().clearFire();
    return true;
};
Probe.panningGeneration = function() {
    var level = this.server().overworld();
    var Pos = this.type('net.minecraft.core.BlockPos'), ChunkPos = this.type('net.minecraft.world.level.ChunkPos');
    var Services = this.type('com.meteorite.unsuspiciousblock.platform.Services');
    var Ledger = this.type('com.meteorite.unsuspiciousblock.core.pan.ShimmerLedger');
    var Spawn = this.type('com.meteorite.unsuspiciousblock.core.pan.ShimmerSpawnService');
    var Trigger = this.type('com.meteorite.unsuspiciousblock.core.pan.ShimmerSpawnService$SpawnTrigger');
    var Variant = this.type('com.meteorite.unsuspiciousblock.core.pan.variant.ShimmerVariants').WATER;
    var Placement = this.type('com.meteorite.unsuspiciousblock.core.pan.ShimmerPlacement');
    var Random = this.type('net.minecraft.util.RandomSource');
    var AABB = this.type('net.minecraft.world.phys.AABB');
    var Shimmer = this.type('com.meteorite.unsuspiciousblock.core.entity.ShimmerEntity');
    var ledger = Ledger.of(level);
    var chunk = new ChunkPos(0, 0);
    var center = new Pos(3, 100, 8);
    var blocked = new Pos(4, 100, 8);
    var nearest = new Pos(5, 100, 8);
    var far = new Pos(8, 100, 8);
    this.command('fill 0 99 0 17 99 15 minecraft:stone');
    this.command('fillbiome 0 96 0 31 112 15 minecraft:river');
    this.command('fill 15 100 3 16 100 12 minecraft:water');
    var seam = null;
    var Predicate = Java.loadClass('java.util.function.Predicate');
    var accept = new JavaAdapter(Predicate, {test: function(pos) { return pos.getX() === 15; }});
    for (var seed = 0; seed < 32 && seam === null; seed++) {
        seam = Placement.wanderForSurface(level, Random.create(seed), chunk, Variant, 101, accept);
    }

    // 手工准备同高的水面与一个已占用的更近锚点，使用真实生成和账本入口。
    var water = this.type('net.minecraft.world.level.block.Blocks').WATER.defaultBlockState();
    [center, blocked, nearest, far].forEach(function(pos) { level.setBlock(pos, water, 2); });
    var natural = Spawn.spawnShimmer(level, center, Variant, Trigger.MANUAL);
    var occupied = Spawn.spawnShimmer(level, blocked, Variant, Trigger.WORLDGEN);
    if (natural === null || occupied === null) throw new Error('生成夹具未加入世界');
    var now = level.getGameTime();
    ledger.startHarvestCooldown(center, now, 100);
    var naturalBefore = ledger.countNatural(Variant.id());
    var specialBefore = ledger.countSpecial(Variant.id());
    Spawn.tryRegenerateAfterHarvest(level, natural, 0.0);
    var zeroChance = ledger.countSpecial(Variant.id()) !== specialBefore;
    Spawn.tryRegenerateAfterHarvest(level, natural, 1.0);
    var entities = level.getEntitiesOfClass(Shimmer, new AABB(0, 99, 0, 12, 102, 14)).iterator();
    var reward = null;
    while (entities.hasNext()) {
        var candidate = entities.next();
        if (String(candidate.getSpawnSource().name()) === 'SPECIAL') reward = candidate;
    }
    var specialAfter = ledger.countSpecial(Variant.id());
    if (reward !== null) Spawn.tryRegenerateAfterHarvest(level, reward, 1.0);
    var naturalUnchanged = naturalBefore === ledger.countNatural(Variant.id());
    var expiredId = this.type('java.util.UUID').randomUUID();
    var expiryPos = new Pos(11, 100, 8);
    ledger.register(expiredId, expiryPos, this.type('com.meteorite.unsuspiciousblock.core.pan.ShimmerLedger$Source').NATURAL,
            Variant.id(), now + 3);
    this.panningExpiry = {id: expiredId, pos: expiryPos};
    return {
        edge: seam === null ? null : Number(seam.getX()),
        defaultChance: Number(Services.PANNING_CONFIG.getGoldPanRegenerationChance()),
        regenerated: specialAfter === specialBefore + 1,
        nearest: reward !== null && reward.getAnchorPos().equals(nearest),
        specialRegistered: reward !== null && ledger.isOccupied(reward.getAnchorPos()),
        naturalUnchanged: naturalUnchanged,
        cooldownActive: ledger.isCoolingDown(chunk, now),
        chained: ledger.countSpecial(Variant.id()) !== specialAfter,
        zeroChance: zeroChance
    };
};
Probe.panningLedgerExpiry = function() {
    var ledger = this.type('com.meteorite.unsuspiciousblock.core.pan.ShimmerLedger').of(this.server().overworld());
    return {occupied: ledger.isOccupied(this.panningExpiry.pos), marked: ledger.isExpired(this.panningExpiry.id)};
};
