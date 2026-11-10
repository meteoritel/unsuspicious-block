"""核对 common/Loader 隔离、跨 Loader 引用与静态客户端类型消费。"""
from pathlib import Path
import sys

from toolslib.boundaries import PROJECT, is_client_owner, is_client_target
from toolslib.checks import Finding, run
from toolslib.java_references import Source, TypeIndex
from toolslib.repo import java_files

LOADERS = {'fabric': ('net.fabricmc.',), 'neoforge': ('net.neoforged.', 'net.minecraftforge.', 'cpw.mods.')}
ROOT_TYPES = {
    'common': frozenset({'Constants'}),
    'fabric': frozenset({'UnsuspiciousBlockFabric', 'UnsuspiciousBlockFabricPreLaunch'}),
    'neoforge': frozenset({'UnsuspiciousBlockNeoForge'}),
}


def check(root: Path) -> list[Finding]:
    sources = [Source.read(path, root) for path in java_files(root)]
    index = TypeIndex(sources)
    findings = set()
    for source in sources:
        root_type = source.package == PROJECT[:-1] and source.path.stem in ROOT_TYPES.get(source.module, ())
        layers = ('core', 'client', 'platform') if source.module == 'common' else ('client', 'platform')
        layer = source.package.removeprefix(PROJECT).split('.', 1)[0]
        if not root_type and (not source.package.startswith(PROJECT) or layer not in layers):
            findings.add(Finding(source.relative, 'isolation.package', '源码须按 core/client/platform 职责归包，根包仅保留环境常量与公共 Loader 入口'))
        expected = source.package.replace('.', '/') + '/' + source.path.name
        if not source.relative.endswith('/src/main/java/' + expected):
            findings.add(Finding(source.relative, 'isolation.package-path', '源码路径与 package 声明不一致'))
        for reference in index.references(source):
            declared = index.types.get(index.owner_name(reference.target), [])
            if declared and not any(index.visible(source, target) for target in declared):
                findings.add(Finding(source.relative, 'isolation.source-set', f'引用了当前源集不可见的平台类型：{reference.target}', reference.line))
            for module, prefixes in LOADERS.items():
                if reference.target.startswith(prefixes) and source.module != module:
                    findings.add(Finding(source.relative, 'isolation.loader', f'{source.module} 引用 {module} API：{reference.target}', reference.line))
            if is_client_target(reference.target, index) and not is_client_owner(source, reference):
                findings.add(Finding(source.relative, 'isolation.client', f'公共执行面引用客户端类型：{reference.target}', reference.line))
    return sorted(findings, key=lambda item: (item.path, item.line or 0, item.rule, item.detail))


if __name__ == '__main__':
    sys.exit(run(check, __doc__))
