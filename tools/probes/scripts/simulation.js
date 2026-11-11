Probe.simulationState = function(table) {
    var state = this.type('com.meteorite.unsuspiciousblock.client.state.ScenarioSimulationClientState');
    var id = this.id(table), dto = state.table(id), selection = state.selection(id);
    if (dto === null || dto.options() === null || selection === null) return null;
    var input = String(state.inputKey(selection)), result = state.result(id, input);
    return {generation: Number(dto.options().generation()), hash: String(dto.hash()), input: input,
            status: String(state.status(id, input)), failure: String(state.failure(id, input)),
            measured: result === null ? 0 : result.simulationCount(), revision: Number(state.revision())};
};
Probe.requestSimulation = function(table) {
    var state = this.type('com.meteorite.unsuspiciousblock.client.state.ScenarioSimulationClientState');
    var id = this.id(table), dto = state.table(id);
    var Params = this.type('com.meteorite.unsuspiciousblock.core.loottable.simulation.ScenarioParams');
    state.select(id, 'baseline', new Params(0.37, dto.options().tools().getFirst().id(), this.type('java.util.Map').of(), 10000));
    state.request(id, true);
    return this.simulationState(table);
};
Probe.requestUnissuedTool = function(table) {
    var state = this.type('com.meteorite.unsuspiciousblock.client.state.ScenarioSimulationClientState');
    var id = this.id(table), dto = state.table(id), options = dto.options();
    var tools = new (this.type('java.util.ArrayList'))(options.tools());
    var component = this.type('net.minecraft.network.chat.Component');
    tools.add(new (this.type('com.meteorite.unsuspiciousblock.core.loottable.simulation.ToolOption'))(
            this.id('minecraft:stick'), component.literal('Probe unissued tool'), component.empty()));
    var altered = new (this.type('com.meteorite.unsuspiciousblock.core.loottable.catalog.SimulationOptions'))(
            options.generation(), options.scenes(), tools, options.enchantments(), options.samples(),
            options.truncated(), options.budgetExhausted(), options.toolSelectionAllowed());
    var Catalog = this.type('com.meteorite.unsuspiciousblock.core.loottable.catalog.CatalogTableDto');
    var input = new Catalog(dto.id(), dto.hash(), dto.displayName(), dto.type(), 0, dto.childTables(), dto.scenarios(),
            dto.items(), dto.childProbabilities(), altered, dto.branches());
    // 在公开目录接收 Interface 构造不可信客户端输入；服务端目录保持真实签发集合。
    state.catalog(this.type('java.util.List').of(input));
    var Params = this.type('com.meteorite.unsuspiciousblock.core.loottable.simulation.ScenarioParams');
    state.select(id, 'baseline', new Params(0.37, this.id('minecraft:stick'), this.type('java.util.Map').of(), 10000));
    state.request(id, true);
    return this.simulationState(table);
};
Probe.saveSimulation = function(table, path) {
    var state = this.type('com.meteorite.unsuspiciousblock.client.state.ScenarioSimulationClientState');
    var id = this.id(table), dto = state.table(id), key = state.inputKey(state.selection(id));
    var Payload = this.type('com.meteorite.unsuspiciousblock.core.network.payload.s2c.SyncScenarioResultPayload');
    var payload = new Payload(dto.options().generation(), dto.hash(), key, state.result(id, key));
    var buffer = new (this.type('net.minecraft.network.RegistryFriendlyByteBuf'))(
            this.type('io.netty.buffer.Unpooled').buffer(), this.client().level.registryAccess());
    try {
        Payload.STREAM_CODEC.encode(buffer, payload);
        var output = new (this.type('java.io.FileOutputStream'))(path);
        try { buffer.readBytes(output, buffer.readableBytes()); } finally { output.close(); }
    } finally { buffer.release(); }
    return this.simulationState(table);
};
Probe.receiveOldSimulation = function(path) {
    var buffer = new (this.type('net.minecraft.network.RegistryFriendlyByteBuf'))(
            this.type('io.netty.buffer.Unpooled').buffer(), this.client().level.registryAccess());
    try {
        var input = new (this.type('java.io.FileInputStream'))(path);
        try { buffer.writeBytes(input, input.available()); } finally { input.close(); }
        var payload = this.type('com.meteorite.unsuspiciousblock.core.network.payload.s2c.SyncScenarioResultPayload').STREAM_CODEC.decode(buffer);
        var state = this.type('com.meteorite.unsuspiciousblock.client.state.ScenarioSimulationClientState');
        var before = Number(state.revision());
        state.receive(payload);
        return {before: before, after: Number(state.revision()), oldGeneration: Number(payload.generation()),
                currentGeneration: Number(state.table(payload.table().id()).options().generation())};
    } finally { buffer.release(); }
};
