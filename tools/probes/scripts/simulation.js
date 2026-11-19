Probe.simulationState = function(table) {
    var state = this.type('com.meteorite.unsuspiciousblock.client.ui.support.JournalClientConnection').scenario();
    var id = this.id(table), dto = state.table(id), selection = state.selection(id);
    if (dto === null || dto.options() === null || selection === null) return null;
    var input = String(this.type('com.meteorite.unsuspiciousblock.client.state.ScenarioSession').inputKey(selection)), result = state.result(id, input);
    return {generation: Number(dto.options().generation()), hash: String(dto.hash()), input: input,
            status: String(state.status(id, input)), failure: String(state.failure(id, input)),
            measured: result === null ? 0 : result.simulationCount(), revision: Number(state.projectionRevision())};
};
Probe.requestSimulation = function(table) {
    var state = this.type('com.meteorite.unsuspiciousblock.client.ui.support.JournalClientConnection').scenario();
    var id = this.id(table), dto = state.table(id);
    var Params = this.type('com.meteorite.unsuspiciousblock.core.loottable.simulation.ScenarioParams');
    state.select(id, this.type('java.util.List').of(), new Params(0.37, dto.options().tools().getFirst().id(), this.type('java.util.Map').of(), 10000));
    state.request(id, true);
    return this.simulationState(table);
};
Probe.requestUnissuedTool = function(table) {
    var state = this.type('com.meteorite.unsuspiciousblock.client.ui.support.JournalClientConnection').scenario();
    var id = this.id(table), dto = state.table(id), options = dto.options();
    var tools = new (this.type('java.util.ArrayList'))(options.tools());
    var component = this.type('net.minecraft.network.chat.Component');
    tools.add(new (this.type('com.meteorite.unsuspiciousblock.core.loottable.simulation.ToolOption'))(
            this.id('minecraft:stick'), component.literal('Probe unissued tool'), component.empty()));
    var altered = new (this.type('com.meteorite.unsuspiciousblock.core.loottable.catalog.SimulationOptions'))(
            options.generation(), options.conditions(), tools, options.enchantments(), options.samples(),
            options.budgetExhausted(), options.toolSelectionAllowed());
    var Catalog = this.type('com.meteorite.unsuspiciousblock.core.loottable.catalog.CatalogTableDto');
    var input = new Catalog(dto.id(), dto.hash(), dto.displayName(), dto.type(), 0, dto.childTables(),
            dto.items(), dto.childProbabilities(), altered, dto.branches());
    // 在公开目录接收 Interface 构造不可信客户端输入；服务端目录保持真实签发集合。
    state.catalog(this.type('java.util.List').of(input));
    var Params = this.type('com.meteorite.unsuspiciousblock.core.loottable.simulation.ScenarioParams');
    state.select(id, this.type('java.util.List').of(), new Params(0.37, this.id('minecraft:stick'), this.type('java.util.Map').of(), 10000));
    state.request(id, true);
    return this.simulationState(table);
};
Probe.replaySimulation = function(table, generationOffset, samples) {
    var state = this.type('com.meteorite.unsuspiciousblock.client.ui.support.JournalClientConnection').scenario();
    var id = this.id(table), dto = state.table(id), key = this.type('com.meteorite.unsuspiciousblock.client.state.ScenarioSession').inputKey(state.selection(id));
    var result = state.result(id, key);
    var Catalog = this.type('com.meteorite.unsuspiciousblock.core.loottable.catalog.CatalogTableDto');
    var candidate = new Catalog(result.id(), result.hash(), result.displayName(), result.type(), samples,
            result.childTables(), result.items(), result.childProbabilities(), result.options(), result.branches());
    var Payload = this.type('com.meteorite.unsuspiciousblock.core.network.payload.s2c.SyncScenarioResultPayload');
    state.receive(new Payload(0, true, Number(dto.options().generation()) + generationOffset, dto.hash(), key, candidate));
    return true;
};
