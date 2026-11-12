// 场景装配只使用已注册的配置和真实 Screen，不反射控件或修改输入树。
Probe.openConfiguration = function() {
    var configs = this.type('net.neoforged.fml.config.ModConfigs');
    var kind = this.type('net.neoforged.fml.config.ModConfig$Type').COMMON;
    var iterator = configs.getConfigSet(kind).iterator(), config = null;
    while (iterator.hasNext()) {
        var next = iterator.next();
        if (String(next.getModId()) === 'unsuspiciousblock') config = next;
    }
    if (config === null) throw new Error('缺少本模组 COMMON 配置');
    this.configuration = config;
    var editor = this.type('com.meteorite.unsuspiciousblock.client.config.NeoForgeConfigEditor');
    this.client().setScreen(new editor(this.client().screen, config));
    return this.configurationValues();
};
Probe.configurationValues = function() {
    var result = {}, valueType = this.type('net.neoforged.neoforge.common.ModConfigSpec$ConfigValue');
    function visit(config) {
        var iterator = config.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next(), value = entry.getRawValue();
            if (value instanceof valueType) result[String(value.getPath())] = String(value.get());
            else visit(value);
        }
    }
    visit(this.configuration.getSpec().getValues());
    return result;
};
'ui scenarios installed';
