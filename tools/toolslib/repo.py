"""按 Git 可分发文件集合读取仓库，避免依赖本地缓存和忽略材料。"""
from pathlib import Path
import subprocess


def repo_root(explicit: str | None = None) -> Path:
    root = Path(explicit).resolve() if explicit else Path(__file__).resolve().parents[2]
    if not (root / 'settings.gradle').is_file() or not (root / 'gradle.properties').is_file():
        raise ValueError(f'不是项目根目录：{root}')
    return root


def repository_files(root: Path) -> list[Path]:
    result = subprocess.run(
        ['git', '-C', str(root), 'ls-files', '--cached', '--others', '--exclude-standard', '-z'],
        capture_output=True, check=False,
    )
    if result.returncode:
        raise ValueError('Git 文件清单读取失败：' + result.stderr.decode('utf-8', errors='replace').strip())
    names = set(result.stdout.decode('utf-8').split('\0')) - {''}
    return sorted(root / name for name in names if (root / name).is_file())


def java_files(root: Path) -> list[Path]:
    files = repository_files(root)
    sources = []
    for module in ('common', 'fabric', 'neoforge'):
        directory = root / module / 'src/main/java'
        selected = [path for path in files if path.is_relative_to(directory) and path.suffix == '.java']
        if not selected:
            raise ValueError(f'必需 Java 源集缺失或为空：{directory}')
        sources.extend(selected)
    return sources


def properties(root: Path) -> dict[str, str]:
    result = {}
    for line in (root / 'gradle.properties').read_text(encoding='utf-8-sig').splitlines():
        if '=' in line and not line.lstrip().startswith(('#', '!')):
            name, value = line.split('=', 1)
            result[name.strip()] = value.strip()
    return result
