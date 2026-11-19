Probe.catalogSnapshot = function(table) {
    var catalog = this.type('com.meteorite.unsuspiciousblock.core.journal.catalog.ArchaeologyJournalServerCatalog');
    var snapshot = catalog.readSnapshot();
    var payload = snapshot.payload();
    var ids = [], projected = null, rows = payload.catalog().iterator();
    var id = this.id(table);
    while (rows.hasNext()) {
        var dto = rows.next();
        ids.push(String(dto.id()));
        if (dto.id().equals(id)) projected = dto;
    }
    var staticIds = [], members = catalog.staticTables().keySet().iterator();
    while (members.hasNext()) staticIds.push(String(members.next()));
    var buffer = new (this.type('net.minecraft.network.RegistryFriendlyByteBuf'))(
            this.type('io.netty.buffer.Unpooled').buffer(), this.server().registryAccess());
    var codec = this.type('com.meteorite.unsuspiciousblock.core.network.payload.s2c.SyncArchaeologyCatalogPayload').STREAM_CODEC;
    var system = this.type('java.lang.System'), encodeMs = [], bytes;
    try {
        for (var attempt = 0; attempt < 5; attempt++) {
            buffer.clear();
            var start = system.nanoTime();
            codec.encode(buffer, payload);
            encodeMs.push((system.nanoTime() - start) / 1000000);
        }
        bytes = buffer.readableBytes();
    } finally {
        buffer.release();
    }
    return {generation: Number(snapshot.generation()), ids: ids, staticIds: staticIds.sort(),
            groups: payload.structure().categories().size(), roots: payload.structure().rootCategories().size(),
            measured: projected === null ? null : projected.simulationCount(), bytes: bytes,
            encodeMs: encodeMs, busy: this.catalogProgress(table).busy};
};

Probe.rebuildColdCatalog = function(table) {
    var catalog = this.type('com.meteorite.unsuspiciousblock.core.journal.catalog.ArchaeologyJournalServerCatalog');
    var system = this.type('java.lang.System'), start = system.nanoTime();
    if (!catalog.rebuildWithoutCache(this.server())) throw new Error('目录重建失败');
    var rebuildMs = (system.nanoTime() - start) / 1000000;
    var snapshot = this.catalogSnapshot(table);
    snapshot.rebuildMs = rebuildMs;
    return snapshot;
};

Probe.catalogProgress = function(table) {
    var worker = this.type('com.meteorite.unsuspiciousblock.core.loottable.simulation.LootProbabilitySimulationWorker').get();
    var catalog = this.type('com.meteorite.unsuspiciousblock.core.journal.catalog.ArchaeologyJournalServerCatalog');
    return {busy: worker.isBusy(), measured: catalog.simulatedTables().containsKey(this.id(table))};
};

Probe.catalogView = function(table) {
    var state = this.type('com.meteorite.unsuspiciousblock.client.ui.support.ArchaeologyJournalClientState');
    var session = this.type('com.meteorite.unsuspiciousblock.client.ui.support.JournalClientConnection').scenario();
    var id = this.id(table), tables = state.getCatalog(), dto = session.table(id), entry = tables.get(id);
    if (dto === null || dto.options() === null || entry === null) return null;
    var items = [], iterator = entry.items().iterator();
    var Unknown = this.type('com.meteorite.unsuspiciousblock.core.loottable.catalog.Probability$Unknown');
    var Measured = this.type('com.meteorite.unsuspiciousblock.core.loottable.catalog.Probability$Measured');
    while (iterator.hasNext()) {
        var item = iterator.next(), probability = item.probability();
        var kind = probability instanceof Unknown ? String(probability.reason())
                : probability instanceof Measured ? 'MEASURED' : String(probability);
        items.push({id: String(item.id()), state: kind});
    }
    var logs = this.type('com.meteorite.unsuspiciousblock.client.ui.support.ArchaeologyJournalLogLocalStore').getState();
    return {ready: state.isReadingReady(), generation: Number(dto.options().generation()),
            measured: entry.simulationCount(), sharedSamples: dto.simulationCount(), items: items, count: tables.size(),
            groups: state.getCatalogStructure().categories().size(),
            roots: state.getCatalogStructure().rootCategories().size(), logs: logs.getTotalEntryCount()};
};
