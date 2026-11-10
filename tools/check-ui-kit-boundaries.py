"""核对独立 UI Kit 公开 API、客户端归属、旧实现和自有控件边界。"""
from pathlib import Path
import sys

from toolslib.boundaries import KIT, NATIVE_WIDGETS, PROJECT, is_client_owner
from toolslib.checks import Finding, run
from toolslib.java_references import NAME, Reference, Source, TypeIndex
from toolslib.repo import java_files

LEGACY = PROJECT + 'client.ui.kit'


def check(root: Path) -> list[Finding]:
    sources = [Source.read(path, root) for path in java_files(root)]
    index = TypeIndex(sources)
    findings = set()
    legacy_dir = root / 'common/src/main/java/com/meteorite/unsuspiciousblock/client/ui/kit'
    if legacy_dir.exists():
        findings.add(Finding(legacy_dir.relative_to(root).as_posix(), 'ui-kit.legacy', '宿主保留旧 kit 目录'))
    for source in sources:
        references = index.references(source)
        wildcard_prefixes = [name[:-1] for _, name in source.imports if name.endswith('.*')]
        for match in NAME.finditer(source.code):
            first = match[0].split('.', 1)[0].strip()
            for prefix in wildcard_prefixes:
                if prefix + first in NATIVE_WIDGETS:
                    references.append(Reference(source.code.count('\n', 0, match.start()) + 1, prefix + first, source.owner_at(match.start())))
        for reference in references:
            target = reference.target
            rule = ''
            if target == LEGACY or target.startswith(LEGACY + '.'):
                rule = 'ui-kit.legacy'
            elif target.startswith(KIT) and not target.startswith(KIT + 'api.'):
                rule = 'ui-kit.public-api'
            elif target.startswith(KIT) and not is_client_owner(source, reference):
                rule = 'ui-kit.client'
            elif any(target == widget or target.startswith(widget + '.') for widget in NATIVE_WIDGETS):
                rule = 'ui-kit.native-widget'
            if rule:
                findings.add(Finding(source.relative, rule, f'越过宿主 UI 边界：{target}', reference.line))
    return sorted(findings, key=lambda item: (item.path, item.line or 0, item.rule, item.detail))


if __name__ == '__main__':
    sys.exit(run(check, __doc__))
