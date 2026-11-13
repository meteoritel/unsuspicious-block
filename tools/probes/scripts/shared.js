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
// SNBT long literals preserve all 64 bits across Rhino; remove only the type suffix.
Probe.longTag = function(tag) { return String(tag.getAsString()).replace(/[lL]$/, ''); };
Probe.progress = function(state, logs, table) {
    var progress = state.getTable(this.id(table));
    var result = {unlocked: progress !== null && progress.isUnlocked(), claimed: progress !== null && progress.isCompletionRewardClaimed(),
                  items: {}, logs: [], history: null};
    if (progress !== null) {
        var signatures = progress.getItemSignatures().iterator();
        while (signatures.hasNext()) {
            var signature = signatures.next();
            result.items[String(signature.toStoredKey())] = {itemId: String(signature.itemId()),
                type: String(signature.type()), data: signature.data() === null ? null : String(signature.data()),
                unlocked: progress.isItemUnlocked(signature), count: progress.getItemCount(signature)};
        }
    }
    var history = logs.getTable(this.id(table));
    if (history !== null) {
        var tag = history.toTag(), counts = tag.getCompound('lifetime_source_counts'), sources = {};
        var keys = counts.getAllKeys().iterator();
        while (keys.hasNext()) { var key = String(keys.next()); sources[key] = Probe.longTag(counts.get(key)); }
        result.history = {retentionLimit: history.getRetentionLimit(), effectiveRetentionLimit: history.getEffectiveRetentionLimit(),
            lifetimeEntryCount: Probe.longTag(tag.get('lifetime_entry_count')), lifetimeSourceCounts: sources,
            firstUnlock: {gameTime: tag.contains('first_unlocked_game_time') ? Probe.longTag(tag.get('first_unlocked_game_time')) : null,
                dayTime: tag.contains('first_unlocked_day_time') ? Probe.longTag(tag.get('first_unlocked_day_time')) : null,
                source: history.getFirstUnlockLootSource() === null ? null : String(history.getFirstUnlockLootSource().id())}};
        var iterator = history.getEntries().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next(), context = entry.context(), position = context.pos(), entryTag = entry.toTag();
            var stack = null;
            if (entry.tableStack() !== null) {
                stack = []; var tables = entry.tableStack().iterator();
                while (tables.hasNext()) stack.push(String(tables.next()));
            }
            result.logs.push({id: String(entry.entryId()), expected: this.map(entry.expectedLoot()),
                actual: this.map(entry.actualLoot()), source: entry.lootSource() === null ? null : String(entry.lootSource().id()),
                context: {dimension: String(context.dimensionId()), block: context.sourceBlockId() === null ? null : String(context.sourceBlockId()),
                    structure: context.structureId() === null ? null : String(context.structureId()), biome: String(context.biomeId()),
                    pos: [position.getX(), position.getY(), position.getZ()]},
                created: {gameTime: Probe.longTag(entryTag.get('created_game_time')), dayTime: Probe.longTag(entryTag.get('created_day_time'))},
                updated: {gameTime: Probe.longTag(entryTag.get('last_updated_game_time')), dayTime: Probe.longTag(entryTag.get('last_updated_day_time'))},
                note: String(entry.note()), tableStack: stack});
        }
    }
    return result;
};
