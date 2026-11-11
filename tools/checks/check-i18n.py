"""核对源码语言资源的成对文件、合法对象、字符串值与中英文 key。"""
from pathlib import Path
import sys

from checkslib.checks import Finding, run
from checkslib.json_input import read_json
from toolslib.repo import repository_files

LOCALES = ('en_us', 'zh_cn')


def check(root: Path) -> list[Finding]:
    groups = {}
    for path in repository_files(root):
        relative = path.relative_to(root).as_posix()
        if '/src/' in relative and '/resources/assets/' in relative and path.parent.name == 'lang':
            if path.stem in LOCALES and path.suffix == '.json':
                groups.setdefault(path.parent, {})[path.stem] = path
    if not groups:
        raise ValueError('没有找到 en_us.json / zh_cn.json 源码语言资源')
    findings = []
    for directory, files in sorted(groups.items()):
        values = {}
        for locale in LOCALES:
            path = directory / f'{locale}.json'
            relative = path.relative_to(root).as_posix()
            if locale not in files:
                findings.append(Finding(relative, 'i18n.pair', '缺少对端语言文件'))
                continue
            try:
                data = read_json(path)
                if not isinstance(data, dict):
                    raise ValueError('顶层须为对象')
                values[locale] = data
                for key, value in data.items():
                    if not isinstance(value, str):
                        findings.append(Finding(relative, 'i18n.value', f'{key} 的翻译须为字符串'))
            except ValueError as error:
                findings.append(Finding(relative, 'i18n.json', str(error), getattr(error, 'lineno', None)))
        if len(values) == len(LOCALES):
            for locale, other in (LOCALES, LOCALES[::-1]):
                for key in sorted(values[locale].keys() - values[other].keys()):
                    findings.append(Finding(files[other].relative_to(root).as_posix(), 'i18n.key', f'缺少 key：{key}'))
    return findings


if __name__ == '__main__':
    sys.exit(run(check, __doc__))
