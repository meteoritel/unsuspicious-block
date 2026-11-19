Probe.fixtureCatalog = function(tables, structure) {
    var members = {}, roots = {};
    var rows = tables.iterator();
    while (rows.hasNext()) {
        var table = rows.next(), id = table.id();
        if (String(id.getNamespace()) !== 'usb_probe') continue;
        var children = [], iterator = table.childTables().iterator();
        while (iterator.hasNext()) children.push(String(iterator.next()));
        members[String(id)] = children.sort();
    }
    var entries = structure.rootCategories().entrySet().iterator();
    while (entries.hasNext()) {
        var root = entries.next();
        if (String(root.getKey().getNamespace()) === 'usb_probe')
            roots[String(root.getKey())] = String(root.getValue());
    }
    return {members: members, roots: roots};
};

Probe.catalogSnapshot = function(table) {
    var catalog = this.type('com.meteorite.unsuspiciousblock.core.journal.catalog.ArchaeologyJournalServerCatalog');
    var snapshot = catalog.readSnapshot(), payload = snapshot.payload(), rows = payload.catalog().iterator();
    var id = this.id(table), measured = null;
    while (rows.hasNext()) {
        var row = rows.next();
        if (row.id().equals(id)) measured = row.simulationCount();
    }
    return {generation: Number(snapshot.generation()), measured: measured,
            fixtures: this.fixtureCatalog(payload.catalog(), payload.structure()), busy: this.catalogProgress(table).busy};
};

Probe.rebuildColdCatalog = function() {
    var catalog = this.type('com.meteorite.unsuspiciousblock.core.journal.catalog.ArchaeologyJournalServerCatalog');
    if (!catalog.rebuildWithoutCache(this.server())) throw new Error('目录重建失败');
    return Number(catalog.currentGenerationId());
};

Probe.catalogProgress = function(table) {
    var worker = this.type('com.meteorite.unsuspiciousblock.core.loottable.simulation.LootProbabilitySimulationWorker').get();
    var catalog = this.type('com.meteorite.unsuspiciousblock.core.journal.catalog.ArchaeologyJournalServerCatalog');
    return {generation: Number(catalog.currentGenerationId()), busy: worker.isBusy(),
            measured: catalog.simulatedTables().containsKey(this.id(table))};
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
            measured: entry.simulationCount(), sharedSamples: dto.simulationCount(), items: items,
            fixtures: this.fixtureCatalog(tables.values(), state.getCatalogStructure()), logs: logs.getTotalEntryCount()};
};
