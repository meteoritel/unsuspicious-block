"""按 Git 可分发文件集合与项目标记定位仓库并读取输入，避免依赖固定目录深度。"""
from pathlib import Path
import subprocess


def repo_root(explicit: str | None = None) -> Path:
    """定位并校验仓库根目录；默认从工具位置向上查找工程根标记文件。"""
    if explicit:
        root = Path(explicit).resolve()
        if not (root / 'settings.gradle').is_file() or not (root / 'gradle.properties').is_file():
            raise ValueError(f'不是项目根目录：{root}')
        return root

    anchor = Path(__file__).resolve()
    for candidate in anchor.parents:
        if (candidate / 'settings.gradle').is_file() and (candidate / 'gradle.properties').is_file():
            return candidate
    raise ValueError(f'无法从工具路径向上定位项目根目录：{anchor}')


def mod_assets_dir(root: Path) -> Path:
    """返回通用模块下的模组资源根目录 (`assets/unsuspiciousblock`)。"""
    return root / 'common/src/main/resources/assets/unsuspiciousblock'


def repository_files(root: Path) -> list[Path]:
    """返回仓库中已跟踪及未忽略的文件清单（排除构建缓存与本地材料）。"""
    result = subprocess.run(
        ['git', '-C', str(root), 'ls-files', '--cached', '--others', '--exclude-standard', '-z'],
        capture_output=True, check=False,
    )
    if result.returncode:
        raise ValueError('Git 文件清单读取失败：' + result.stderr.decode('utf-8', errors='replace').strip())
    names = set(result.stdout.decode('utf-8').split('\0')) - {''}
    return sorted(root / name for name in names if (root / name).is_file())


def java_files(root: Path) -> list[Path]:
    """收集 common / fabric / neoforge 主源码集下的全部 Java 文件。"""
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
    """读取根目录 gradle.properties 键值对。"""
    result = {}
    for line in (root / 'gradle.properties').read_text(encoding='utf-8-sig').splitlines():
        if '=' in line and not line.lstrip().startswith(('#', '!')):
            name, value = line.split('=', 1)
            result[name.strip()] = value.strip()
    return result
