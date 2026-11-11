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
Probe.layout = function() {
    var client = this.client();
    var screen = client.screen;
    if (screen === null || !(screen instanceof this.type('com.meteorite.meteosuikit.api.control.UiScreen'))) throw new Error('Screen 不支持 kit 布局');
    var nodes = [];
    function visit(values) {
        var iterator = values.iterator();
        while (iterator.hasNext()) {
            var node = iterator.next(), rect = node.rect(), clip = node.visibleRect();
            nodes.push({key: String(node.key()), kind: String(node.kind()), text: String(node.text().getString()),
                        enabled: node.enabled(), visible: node.visible(),
                        x: rect.x(), y: rect.y(), width: rect.width(), height: rect.height(),
                        clip: {x: clip.x(), y: clip.y(), width: clip.width(), height: clip.height()}});
            visit(node.children());
        }
    }
    visit(screen.layoutNodes());
    return {screen: this.screenName(), width: screen.width, height: screen.height,
            scale: screen.viewport().scale(), nodes: nodes};
};
Probe.click = function(key) {
    var nodes = this.layout().nodes.filter(function(node) { return node.key === key; });
    if (nodes.length !== 1) throw new Error('目标数量 ' + nodes.length + ': ' + key);
    var clip = nodes[0].clip, x = clip.x + clip.width / 2, y = clip.y + clip.height / 2;
    var screen = this.client().screen;
    if (!screen.isLayoutTarget(key, x, y)) throw new Error('目标不可点击: ' + key);
    var hooks = this.type('net.neoforged.neoforge.client.ClientHooks');
    var handled = false;
    try {
        if (!hooks.onScreenMouseClickedPre(screen, x, y, 0)) {
            handled = screen.mouseClicked(x, y, 0);
            hooks.onScreenMouseClickedPost(screen, x, y, 0, handled);
        }
    } finally {
        if (!hooks.onScreenMouseReleasedPre(screen, x, y, 0)) {
            handled = screen.mouseReleased(x, y, 0);
            hooks.onScreenMouseReleasedPost(screen, x, y, 0, handled);
        }
    }
    return {key: key, x: x, y: y};
};
Probe.text = function(value) {
    var screen = this.client().screen, hooks = this.type('net.neoforged.neoforge.client.ClientHooks');
    for (var i = 0; i < value.length; i++) {
        var code = value.charCodeAt(i);
        if (!hooks.onScreenCharTypedPre(screen, code, 0) && !screen.charTyped(code, 0)) hooks.onScreenCharTypedPost(screen, code, 0);
    }
    return true;
};
Probe.key = function(code) {
    var screen = this.client().screen, hooks = this.type('net.neoforged.neoforge.client.ClientHooks');
    try {
        if (!hooks.onScreenKeyPressedPre(screen, code, 0, 0) && !screen.keyPressed(code, 0, 0)) hooks.onScreenKeyPressedPost(screen, code, 0, 0);
    } finally {
        if (!hooks.onScreenKeyReleasedPre(screen, code, 0, 0) && !screen.keyReleased(code, 0, 0)) hooks.onScreenKeyReleasedPost(screen, code, 0, 0);
    }
    return true;
};
Probe.replaceText = function(value) {
    this.key(269); // End 后逐个 Backspace，使用真实编辑入口且不依赖物理 Ctrl 状态。
    for (var i = 0; i < 128; i++) this.key(259);
    return this.text(value);
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
