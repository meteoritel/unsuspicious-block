// UI module 只读取当前活动布局；几何与输入资格由真实 kit target 提供。
(function() {
    function entries() {
        var screen = Probe.client().screen;
        if (screen === null || !(screen instanceof Probe.type('com.meteorite.meteosuikit.api.control.UiScreen')))
            throw new Error('Screen 不支持 kit 布局');
        var result = [], focusType = Probe.type('com.meteorite.meteosuikit.api.input.UiFocusTarget');
        function visit(values) {
            var iterator = values.iterator();
            while (iterator.hasNext()) {
                var node = iterator.next(), rect = node.rect(), clip = node.visibleRect(), target = node.target();
                var focus = target !== null && target instanceof focusType;
                var name = focus ? target.accessibleName() : null;
                var editor = target instanceof Probe.type('com.meteorite.meteosuikit.api.control.UiTextInput')
                        || target instanceof Probe.type('com.meteorite.meteosuikit.api.control.UiTextArea');
                result.push({node: node, view: {
                    key: String(node.key()), kind: String(node.kind()), text: String(node.text().getString()),
                    name: name === null ? '' : String(name.getString()), focused: focus && target.isFocused(),
                    value: editor ? String(target.value()) : null,
                    enabled: node.enabled(), visible: node.visible(),
                    x: rect.x(), y: rect.y(), width: rect.width(), height: rect.height(),
                    clip: {x: clip.x(), y: clip.y(), width: clip.width(), height: clip.height()}}});
                visit(node.children());
            }
        }
        visit(screen.layoutNodes());
        return result;
    }
    function selected(selector) {
        if (typeof selector === 'string') selector = {key: selector};
        var keys = Object.keys(selector);
        if (keys.length === 0 || keys.some(function(key) { return ['key','kind','text','name'].indexOf(key) < 0; }))
            throw new Error('选择器只接受 key/kind/text/name');
        var matches = entries().filter(function(entry) {
            return keys.every(function(key) { return entry.view[key] === selector[key]; });
        });
        if (matches.length !== 1) throw new Error('目标数量 ' + matches.length + ': ' + JSON.stringify(selector));
        return matches[0];
    }
    function point(selector) {
        var entry = selected(selector), view = entry.view, clip = view.clip;
        if (!view.enabled || !view.visible || clip.width <= 0 || clip.height <= 0)
            throw new Error('目标不可交互: ' + JSON.stringify(view));
        var screen = Probe.client().screen, viewport = screen.viewport();
        var x = clip.x + clip.width / 2, y = clip.y + clip.height / 2, target = entry.node.target();
        if (view.key !== '') {
            if (!screen.isLayoutTarget(view.key, x, y)) throw new Error('真实命中不属于目标: ' + view.key);
        } else if (target === null || target.hitTest(viewport.toLogical(x), viewport.toLogical(y)) === null) {
            throw new Error('目标没有实际输入命中: ' + JSON.stringify(view));
        }
        return {x: x, y: y};
    }
    Probe.layout = function() {
        var screen = this.client().screen, window = this.client().getWindow();
        var nodes = entries().map(function(entry) { return entry.view; }), viewport = screen.viewport();
        return {screen: this.screenName(), width: screen.width, height: screen.height,
                framebuffer: [window.getWidth(), window.getHeight()], guiScale: window.getGuiScale(),
                window: [window.getScreenWidth(), window.getScreenHeight()],
                logical: [viewport.logicalWidth(), viewport.logicalHeight()], scale: viewport.scale(), nodes: nodes};
    };
    Probe.movePointer = function(x, y) {
        var window = this.client().getWindow(), handle = window.getWindow();
        var glfw = this.type('org.lwjgl.glfw.GLFW');
        var callback = glfw.glfwSetCursorPosCallback(handle, null);
        glfw.glfwSetCursorPosCallback(handle, callback);
        if (callback === null) throw new Error('窗口未安装指针回调');
        // 直接通知 MouseHandler；不调用操作系统的指针移动，后台运行也保留真实悬停状态。
        callback.invoke(handle, x * window.getScreenWidth() / window.getGuiScaledWidth(),
                        y * window.getScreenHeight() / window.getGuiScaledHeight());
        callback.invoke(handle, x * window.getScreenWidth() / window.getGuiScaledWidth(),
                        y * window.getScreenHeight() / window.getGuiScaledHeight());
        return {x:x, y:y};
    };
    Probe.hover = function(selector) {
        var position = point(selector);
        this.movePointer(position.x, position.y);
        return position;
    };
    Probe.hovered = function(selector) {
        var position = point(selector), window = this.client().getWindow(), mouse = this.client().mouseHandler;
        var x = mouse.xpos() * window.getGuiScaledWidth() / window.getScreenWidth();
        var y = mouse.ypos() * window.getGuiScaledHeight() / window.getScreenHeight();
        return Math.abs(x-position.x) < 1 && Math.abs(y-position.y) < 1;
    };
    Probe.pointer = function(phase, position) {
        var screen = this.client().screen, hooks = this.type('net.neoforged.neoforge.client.ClientHooks');
        var handled = false;
        if (phase === 'press') {
            if (!hooks.onScreenMouseClickedPre(screen, position.x, position.y, 0)) {
                handled = screen.mouseClicked(position.x, position.y, 0);
                hooks.onScreenMouseClickedPost(screen, position.x, position.y, 0, handled);
            }
        } else if (phase === 'release') {
            if (!hooks.onScreenMouseReleasedPre(screen, position.x, position.y, 0)) {
                handled = screen.mouseReleased(position.x, position.y, 0);
                hooks.onScreenMouseReleasedPost(screen, position.x, position.y, 0, handled);
            }
        } else throw new Error('未知指针动作: ' + phase);
        return handled;
    };
    Probe.press = function(selector) {
        var position = point(selector);
        this.pointer('press', position);
        return position;
    };
    Probe.click = function(selector) {
        var position = point(selector);
        try { this.pointer('press', position); }
        finally { this.pointer('release', position); }
        return position;
    };
    Probe.scroll = function(selector, delta) {
        var position = point(selector);
        return this.client().screen.mouseScrolled(position.x, position.y, 0, delta);
    };
    Probe.text = function(value) {
        var screen = this.client().screen, hooks = this.type('net.neoforged.neoforge.client.ClientHooks');
        for (var i = 0; i < value.length; i++) {
            var code = value.charCodeAt(i);
            if (!hooks.onScreenCharTypedPre(screen, code, 0) && !screen.charTyped(code, 0)) hooks.onScreenCharTypedPost(screen, code, 0);
        }
        return true;
    };
    Probe.key = function(code, modifiers) {
        modifiers = modifiers || 0;
        var screen = this.client().screen, hooks = this.type('net.neoforged.neoforge.client.ClientHooks');
        try {
            if (!hooks.onScreenKeyPressedPre(screen, code, 0, modifiers) && !screen.keyPressed(code, 0, modifiers))
                hooks.onScreenKeyPressedPost(screen, code, 0, modifiers);
        } finally {
            if (!hooks.onScreenKeyReleasedPre(screen, code, 0, modifiers) && !screen.keyReleased(code, 0, modifiers))
                hooks.onScreenKeyReleasedPost(screen, code, 0, modifiers);
        }
        return true;
    };
    Probe.label = function(key) { return String(this.type('net.minecraft.network.chat.Component').translatable(key).getString()); };
    Probe.replaceText = function(value) {
        var inputs = entries().filter(function(entry) { return entry.view.kind === 'TEXT_INPUT' && entry.view.focused; });
        if (inputs.length !== 1) throw new Error('替换文本需要唯一聚焦的单行字段');
        var length = inputs[0].view.value.length;
        this.key(269);
        for (var i = 0; i < length; i++) this.key(259);
        return this.text(value);
    };
})();
'ui installed';
