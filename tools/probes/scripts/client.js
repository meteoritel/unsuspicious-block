Probe.client = function() { return this.type('net.minecraft.client.Minecraft').getInstance(); };
Probe.screenName = function() { return this.client().screen === null ? null : JSON.parse(String(AutoJS.ui())).screen['class']; };
Probe.ready = function() {
    var client = this.client();
    return {player: client.player !== null && client.level !== null,
            uuid: client.player === null ? null : String(client.player.getUUID()),
            screen: this.screenName(), tick: client.player === null ? 0 : client.player.tickCount};
};
'client installed';

Probe.id = function(value) { return this.type('net.minecraft.resources.ResourceLocation').parse(value); };
Probe.closeScreen = function() {
    this.client().setScreen(null);
    this.client().options.keyUse.setDown(false);
    this.client().player.getInventory().selected = 0;
    return true;
};
Probe.use = function() {
    var client = this.client();
    if (client.screen !== null) throw new Error('普通右键需要关闭 Screen');
    this.type('net.minecraft.client.KeyMapping').click(this.type('com.mojang.blaze3d.platform.InputConstants$Type').MOUSE.getOrCreate(1));
    client.options.keyUse.setDown(false);
    return {tick: client.player.tickCount};
};
Probe.scanInputReady = function() {
    var client = this.client(), hit = client.hitResult;
    return client.getOverlay() === null && hit instanceof this.type('net.minecraft.world.phys.BlockHitResult')
            && hit.getBlockPos().equals(new (this.type('net.minecraft.core.BlockPos'))(0, 100, 1))
            && client.level.getBlockState(hit.getBlockPos()).is(this.type('net.minecraft.world.level.block.Blocks').SUSPICIOUS_SAND);
};
Probe.holdUse = function() {
    var client = this.client();
    if (client.screen !== null) throw new Error('持续右键需要关闭 Screen');
    this.type('net.minecraft.client.KeyMapping').click(this.type('com.mojang.blaze3d.platform.InputConstants$Type').MOUSE.getOrCreate(1));
    client.options.keyUse.setDown(true);
    return {tick: client.player.tickCount};
};
Probe.openJournal = function() {
    var client = this.client();
    client.setScreen(null);
    client.player.getInventory().selected = 1;
    return this.use();
};
Probe.journal = function(table) {
    var state = this.type('com.meteorite.unsuspiciousblock.client.ui.support.ArchaeologyJournalClientState').getState();
    var logs = this.type('com.meteorite.unsuspiciousblock.client.ui.support.ArchaeologyJournalLogLocalStore').getState();
    return this.progress(state, logs, table);
};
Probe.capture = function(path) {
    this.captureFuture = this.type('com.meteorite.meteosuikit.api.render.UiFrameCapture').capture(
            this.type('java.nio.file.Path').of(path));
    return true;
};
Probe.toastsCleared = function() {
    var toasts = this.client().getToasts(), toast = this.type('net.minecraft.client.gui.components.toasts.Toast');
    return toasts.getToast(toast, toast.NO_TOKEN) === null
            && toasts.getToast(this.type('net.minecraft.client.gui.components.toasts.SystemToast'),
                    this.type('net.minecraft.client.gui.components.toasts.SystemToast$SystemToastId').UNSECURE_SERVER_WARNING) === null;
};
Probe.captureResult = function() {
    var future = this.captureFuture;
    if (!future.isDone()) return null;
    var result = future.join();
    return {path: String(result.path()), width: result.width(), height: result.height(), frame: Number(result.frame()),
            screen: String(result.screen()), dimension: String(result.dimension())};
};
Probe.resize = function(width, height) {
    var window = this.client().getWindow();
    this.type('org.lwjgl.glfw.GLFW').glfwSetWindowSize(window.getWindow(), width, height);
    return true;
};
Probe.guiScale = function(scale) {
    this.client().options.guiScale().set(new (this.type('java.lang.Integer'))(scale));
    this.client().resizeDisplay();
    return true;
};
Probe.releaseInput = function() {
    var client = this.client();
    client.options.keyUse.setDown(false);
    client.options.keyAttack.setDown(false);
    if (client.player !== null && client.player.isUsingItem()) client.gameMode.releaseUsingItem(client.player);
    if (this.captureFuture && !this.captureFuture.isDone()) this.captureFuture.cancel(false);
    return true;
};
Probe.environment = function() {
    var system = this.type('java.lang.System'), window = this.client().getWindow();
    return {java: String(system.getProperty('java.version')), os: String(system.getProperty('os.name')),
            renderer: String(this.type('com.mojang.blaze3d.platform.GlUtil').getRenderer()),
            framebuffer: [window.getWidth(), window.getHeight()], guiScale: window.getGuiScale()};
};
