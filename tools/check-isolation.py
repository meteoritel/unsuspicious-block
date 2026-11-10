"""核对 common/Loader 隔离、跨 Loader 引用与静态客户端类型消费。"""
from pathlib import Path
import sys

from toolslib.boundaries import PAYLOADS, is_client_owner, is_client_target
from toolslib.checks import Finding, run
from toolslib.java_references import Source, TypeIndex
from toolslib.repo import java_files

LOADERS = {'fabric': ('net.fabricmc.',), 'neoforge': ('net.neoforged.', 'net.minecraftforge.', 'cpw.mods.')}


def check(root: Path) -> list[Finding]:
    sources = [Source.read(path, root) for path in java_files(root)]
    index = TypeIndex(sources)
    findings = set()
    for source in sources:
        for reference in index.references(source):
            declared = index.types.get(index.owner_name(reference.target), [])
            if declared and not any(index.visible(source, target) for target in declared):
                findings.add(Finding(source.relative, 'isolation.source-set', f'引用了当前源集不可见的平台类型：{reference.target}', reference.line))
            for module, prefixes in LOADERS.items():
                if reference.target.startswith(prefixes) and source.module != module:
                    findings.add(Finding(source.relative, 'isolation.loader', f'{source.module} 引用 {module} API：{reference.target}', reference.line))
            if is_client_target(reference.target, index) and not is_client_owner(source, reference):
                # 文件内 import 由实际消费类型归属判定，公共声明仍逐引用检查。
                if reference.is_import and source.package + '.' + source.path.stem == PAYLOADS:
                    continue
                findings.add(Finding(source.relative, 'isolation.client', f'公共执行面引用客户端类型：{reference.target}', reference.line))
    return sorted(findings, key=lambda item: (item.path, item.line or 0, item.rule, item.detail))


if __name__ == '__main__':
    sys.exit(run(check, __doc__))
