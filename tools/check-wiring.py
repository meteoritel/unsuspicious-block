"""核对资源 JSON、Mixin 类、SPI 声明与平台本地资源接线。"""
from pathlib import Path
import re
import sys

from toolslib.checks import Finding, run
from toolslib.boundaries import is_client_source
from toolslib.java_references import Source, TypeIndex
from toolslib.json_input import read_json
from toolslib.repo import java_files, properties, repository_files

PROJECT = 'com.meteorite.unsuspiciousblock.'


def check(root: Path) -> list[Finding]:
    files = repository_files(root)
    sources = [Source.read(path, root) for path in java_files(root)]
    index = TypeIndex(sources)
    props = properties(root)
    resources = {}
    for path in files:
        relative = path.relative_to(root).as_posix()
        match = re.match(r'(common|fabric|neoforge)/src/(?:main|generated)/resources/(.+)', relative)
        if match:
            resources.setdefault(match[1], {})[match[2]] = path
    for module in ('common', 'fabric', 'neoforge'):
        if not resources.get(module):
            raise ValueError(f'必需资源源集缺失或为空：{module}')
    findings = []

    def report(path: Path, rule: str, detail: str, line: int | None = None):
        findings.append(Finding(path.relative_to(root).as_posix(), rule, detail, line))

    def class_exists(module: str, name: str) -> bool:
        declared = index.types.get(name.replace('$', '.'), [])
        return any(source.module in ('common', module) for source in declared)

    def resource_exists(module: str, name: str) -> bool:
        return name in resources.get(module, {}) or name in resources['common']

    for module, entries in resources.items():
        for name, path in entries.items():
            if name.endswith('.json') or name == 'pack.mcmeta':
                try:
                    data = read_json(path)
                except ValueError as error:
                    report(path, 'resource.json', str(error), getattr(error, 'lineno', None))
                    continue
                if name.endswith('.mixins.json'):
                    if not isinstance(data, dict) or not isinstance(data.get('package'), str):
                        report(path, 'mixin.config', 'Mixin 配置须声明 package')
                        continue
                    declared = []
                    if not data['package'].startswith(PROJECT) or 'mixin' not in data['package'].split('.'):
                        report(path, 'mixin.package', 'Mixin package 须指向专属注入包')
                    for side in ('mixins', 'client', 'server'):
                        values = data.get(side, [])
                        if not isinstance(values, list) or any(not isinstance(value, str) for value in values):
                            report(path, 'mixin.config', f'{side} 须为类名数组')
                            continue
                        declared.extend(data['package'] + '.' + value for value in values)
                        for value in values:
                            class_name = data['package'] + '.' + value
                            if side != 'client' and any(is_client_source(source) for source in index.types.get(class_name, [])):
                                report(path, 'mixin.side', f'客户端 Mixin 须登记在 client 数组：{class_name}')
                    if data.get('plugin') and isinstance(data['plugin'], str):
                        declared.append(data['plugin'])
                    elif data.get('plugin'):
                        report(path, 'mixin.config', 'plugin 须为类名字符串')
                    for class_name in declared:
                        if not isinstance(class_name, str) or not class_exists(module, class_name):
                            report(path, 'mixin.class', f'找不到本地 Mixin/插件声明：{class_name}')
                    if len(set(declared)) != len(declared):
                        report(path, 'mixin.duplicate', 'Mixin 类重复登记')
                if name == 'fabric.mod.json':
                    if not isinstance(data, dict):
                        report(path, 'fabric.metadata', '顶层须为对象')
                        continue
                    targets = [data[key] for key in ('accessWidener', 'icon') if isinstance(data.get(key), str)]
                    if isinstance(data.get('icon'), dict):
                        targets.extend(data['icon'].values())
                    mixins = data.get('mixins', [])
                    if not isinstance(mixins, list):
                        report(path, 'fabric.metadata', 'mixins 须为数组')
                        mixins = []
                    for mixin in mixins:
                        targets.append(mixin.get('config', '') if isinstance(mixin, dict) else mixin)
                    for target in targets:
                        if not isinstance(target, str) or not resource_exists(module, target):
                            report(path, 'fabric.resource', f'本地资源不存在：{target}')
                    entrypoints = data.get('entrypoints', {})
                    if not isinstance(entrypoints, dict):
                        report(path, 'fabric.metadata', 'entrypoints 须为对象')
                        entrypoints = {}
                    for group in entrypoints.values():
                        if not isinstance(group, list):
                            report(path, 'fabric.metadata', 'entrypoints 的每个入口须为数组')
                            continue
                        for entry in group:
                            value = entry.get('value', '') if isinstance(entry, dict) else entry
                            if not isinstance(value, str) or not class_exists(module, value.split('::')[0]):
                                report(path, 'fabric.entrypoint', f'入口类不存在：{value}')
            if name.startswith('META-INF/services/'):
                interface = name.removeprefix('META-INF/services/')
                if interface.startswith(PROJECT) and not class_exists(module, interface):
                    report(path, 'spi.interface', f'接口不存在：{interface}')
                providers = []
                for line, raw in enumerate(path.read_text(encoding='utf-8-sig').splitlines(), 1):
                    provider = raw.split('#', 1)[0].strip()
                    if not provider:
                        continue
                    if provider in providers:
                        report(path, 'spi.duplicate', f'提供者重复登记：{provider}', line)
                    providers.append(provider)
                    if not class_exists(module, provider):
                        report(path, 'spi.provider', f'本地提供者不存在：{provider}', line)
                if not providers:
                    report(path, 'spi.empty', '服务声明缺少提供者')
            if name == 'META-INF/neoforge.mods.toml':
                text = path.read_text(encoding='utf-8-sig')
                for match in re.finditer(r'^\s*config\s*=\s*"([^"]+)"', text, re.M):
                    target = re.sub(r'\$\{([^}]+)\}', lambda m: props.get(m[1], m[0]), match[1])
                    if not resource_exists(module, target):
                        report(path, 'neoforge.resource', f'本地 Mixin 资源不存在：{target}', text.count('\n', 0, match.start()) + 1)
    return findings


if __name__ == '__main__':
    sys.exit(run(check, __doc__))
