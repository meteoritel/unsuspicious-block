Probe.clockSample = function(work) {
    var system = this.type('java.lang.System'), started = system.nanoTime();
    var result = work();
    return {ns: Number(system.nanoTime() - started), result: result};
};

Probe.benchmarkEnvironment = function() {
    var system = this.type('java.lang.System');
    var mx = this.type('java.lang.management.ManagementFactory');
    var args = mx.getRuntimeMXBean().getInputArguments().iterator(), flags = [];
    while (args.hasNext()) {
        var flag = String(args.next());
        if (!/^-D(autojs\.port|legacyClassPath\.file|log4j2\.configurationFile)=/.test(flag)) flags.push(flag);
    }
    return {java: String(system.getProperty('java.runtime.version')), vm: String(system.getProperty('java.vm.name')),
            args: flags, processors: this.type('java.lang.Runtime').getRuntime().availableProcessors(),
            tickRate: this.server().tickRateManager().tickrate()};
};

Probe.codecSample = function() {
    var catalog = this.type('com.meteorite.unsuspiciousblock.core.journal.catalog.ArchaeologyJournalServerCatalog');
    var snapshot = catalog.readSnapshot(), payload = snapshot.payload();
    var buffer = new (this.type('net.minecraft.network.RegistryFriendlyByteBuf'))(
            this.type('io.netty.buffer.Unpooled').buffer(), this.server().registryAccess());
    var codec = this.type('com.meteorite.unsuspiciousblock.core.network.payload.s2c.SyncArchaeologyCatalogPayload').STREAM_CODEC;
    var rows = payload.catalog().iterator(), members = [];
    while (rows.hasNext()) members.push(String(rows.next().id()));
    try {
        var sample = this.clockSample(function() { codec.encode(buffer, payload); });
        return {ns: sample.ns, bytes: buffer.readableBytes(), members: members.sort(), hash: String(snapshot.hash())};
    } finally {
        buffer.release();
    }
};

Probe.simulationWorkload = function() {
    var catalog = this.type('com.meteorite.unsuspiciousblock.core.journal.catalog.ArchaeologyJournalServerCatalog');
    var rows = catalog.readSnapshot().payload().catalog().iterator(), workload = {};
    while (rows.hasNext()) {
        var row = rows.next();
        workload[String(row.id())] = [String(row.hash()), row.simulationCount()];
    }
    return workload;
};

Probe.ledgerSample = function(entries) {
    var server = this.server(), level = server.overworld(), data = server.getWorldData().overworldData();
    var Ledger = this.type('com.meteorite.unsuspiciousblock.core.pan.ShimmerLedger');
    var source = this.type('com.meteorite.unsuspiciousblock.core.pan.ShimmerLedger$Source');
    var variant = this.type('com.meteorite.unsuspiciousblock.core.pan.variant.ShimmerVariants').WATER_ID;
    var blocks = this.type('net.minecraft.core.BlockPos'), uuid = this.type('java.util.UUID');
    var ledger = Ledger.of(level), now = Number(data.getGameTime()), deadline = now + 10000, ids = [];
    try {
        for (var index = 0; index < entries; index++) {
            var id = new uuid(531, index);
            ids.push(id);
            ledger.register(id, new blocks(1000 + index * 16, 65, 1000), source.NATURAL, variant, deadline);
        }
        data.setGameTime(now + 1);
        var measured = this.clockSample(function() { return Ledger.of(level); });
        var retained = 0;
        for (var item = 0; item < ids.length; item++)
            if (Number(measured.result.expirationOf(ids[item], -1)) === deadline) retained++;
        return {ns: measured.ns, retained: retained};
    } finally {
        for (var entry = 0; entry < ids.length; entry++) ledger.unregister(ids[entry]);
        data.setGameTime(now);
    }
};
