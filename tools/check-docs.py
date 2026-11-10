"""核对可分发 Markdown 的本地链接、图片目标和文档导航。"""
import argparse
from pathlib import Path
import re
import sys
from urllib.parse import unquote, urlsplit

from toolslib.checks import Finding, execute
from toolslib.markdown_links import links
from toolslib.repo import repo_root, repository_files

ENTRYPOINTS = ('docs/README.md', 'docs/dev/README.md', 'tools/README.md')
WIKI_ROOTS = ('docs/wiki/Home.md', 'docs/wiki/_Sidebar.md', 'docs/wiki/_Footer.md')


def local_target(root: Path, doc: Path, target: str, available: set[Path]) -> Path | None:
    clean = re.sub(r'\\([\\ ()\[\]#])', r'\1', target)
    parsed = urlsplit(clean)
    if parsed.scheme in ('http', 'https', 'mailto', 'tel', 'data') or parsed.netloc or not parsed.path:
        return None
    if parsed.scheme:
        raise ValueError(target)
    path = unquote(parsed.path)
    resolved = (root / path.lstrip('/') if path.startswith('/') else doc.parent / path).resolve()
    if not resolved.is_relative_to(root):
        raise ValueError(target)
    if resolved in available or any(item.is_relative_to(resolved) for item in available):
        return resolved
    if doc.is_relative_to(root / 'docs/wiki') and not resolved.suffix:
        if resolved.with_suffix('.md') in available:
            return resolved.with_suffix('.md')
    raise ValueError(target)


def check(root: Path, include_local_rules: bool = False) -> list[Finding]:
    files = repository_files(root)
    available = {path.resolve() for path in files}
    docs = [path for path in files if path.suffix.lower() == '.md']
    if not docs:
        raise ValueError('没有可检查的 Markdown 文档')
    for entry in ENTRYPOINTS:
        if root / entry not in available:
            raise ValueError(f'缺少文档入口：{entry}')
    if include_local_rules:
        docs.extend(root / name for name in ('AGENTS.md', 'CLAUDE.md') if (root / name).is_file())
    graph = {path: set() for path in docs}
    findings = []
    for doc in docs:
        relative = doc.relative_to(root).as_posix()
        for link in links(doc.read_text(encoding='utf-8-sig')):
            try:
                target = local_target(root, doc, link.target, available)
                if target in graph:
                    graph[doc].add(target)
            except ValueError:
                findings.append(Finding(relative, 'docs.target', f'本地目标未入库、被忽略或不存在：{link.target}', link.line))
    reached = set()
    pending = [root / name for name in ('docs/README.md', 'README.md', 'README_EN.md', *WIKI_ROOTS) if (root / name).is_file()]
    while pending:
        current = pending.pop()
        if current in reached:
            continue
        reached.add(current)
        pending.extend(graph.get(current, set()) - reached)
    for doc in docs:
        relative = doc.relative_to(root).as_posix()
        if relative.startswith('docs/') and doc not in reached:
            findings.append(Finding(relative, 'docs.navigation', '缺少从文档入口或 Wiki 导航进入的链接'))
        if relative.startswith(('docs/dev/foundation/', 'docs/dev/subsystems/')):
            if doc not in graph.get(root / 'docs/dev/README.md', set()):
                findings.append(Finding(relative, 'docs.dev-route', '请在开发导航中登记职责入口'))
    return findings


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', help='项目根目录，默认由脚本位置定位')
    parser.add_argument('--include-local-rules', action='store_true', help='同时检查本地根规则的出站链接')
    args = parser.parse_args()
    sys.exit(execute(lambda: check(repo_root(args.root), args.include_local_rules)))
