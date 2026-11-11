var Probe = {
    actions: {},
    action: function(id, execute) {
        if (Object.prototype.hasOwnProperty.call(this.actions, id)) return this.actions[id];
        this.actions[id] = {state: 'running'};
        try {
            var value = execute();
            this.actions[id] = {state: 'done', value: value === undefined ? null : value};
        } catch (error) {
            this.actions[id] = {state: 'error', message: String(error)};
        }
        return this.actions[id];
    },
    type: function(name) { return Java.loadClass(name); }
};
'installed';

Probe.map = function(map) {
    var result = {};
    var iterator = map.entrySet().iterator();
    while (iterator.hasNext()) { var entry = iterator.next(); result[String(entry.getKey())] = Number(entry.getValue()); }
    return result;
};
Probe.progress = function(state, logs, table) {
    var progress = state.getTable(this.id(table));
    var result = {unlocked: progress !== null && progress.isUnlocked(), claimed: progress !== null && progress.isCompletionRewardClaimed(),
                  items: {}, logs: []};
    if (progress !== null) {
        var signatures = progress.getItemSignatures().iterator();
        while (signatures.hasNext()) {
            var signature = signatures.next();
            result.items[String(signature.itemId())] = {unlocked: progress.isItemUnlocked(signature), count: progress.getItemCount(signature)};
        }
    }
    var history = logs.getTable(this.id(table));
    if (history !== null) {
        var iterator = history.getEntries().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            result.logs.push({id: String(entry.entryId()), expected: this.map(entry.expectedLoot()),
                              actual: this.map(entry.actualLoot()), source: String(entry.lootSource())});
        }
    }
    return result;
};
