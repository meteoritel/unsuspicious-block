"""读取 Java 静态引用及其所属类型，隔离注释与字面量。"""
from bisect import bisect_right
from dataclasses import dataclass
from pathlib import Path
import re

NON_CODE = re.compile(r'"""(?:\\.|(?!""")[\s\S])*?"""|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|//[^\n]*|/\*[\s\S]*?\*/')
PACKAGE = re.compile(r'\bpackage\s+([\w.]+)\s*;')
IMPORT = re.compile(r'\bimport\s+(?:static\s+)?([\w.]+(?:\*)?)\s*;')
TYPE = re.compile(r'\b(?:class|interface|enum|record)\s+([A-Za-z_$][\w$]*)')
NAME = re.compile(r'\b[A-Za-z_$][\w$]*(?:\s*\.\s*[A-Za-z_$][\w$]*)*')
PREFIXES = ('com.meteorite.', 'com.mojang.blaze3d.', 'org.lwjgl.', 'net.fabricmc.', 'net.neoforged.', 'net.minecraftforge.', 'net.minecraft.', 'cpw.mods.')


def code_only(text: str) -> str:
    return NON_CODE.sub(lambda match: re.sub(r'[^\n]', ' ', match[0]), text)


@dataclass(frozen=True)
class Reference:
    line: int
    target: str
    owner: str
    is_import: bool = False


@dataclass
class Source:
    path: Path
    relative: str
    code: str
    package: str
    module: str
    imports: list[tuple[int, str]]
    types: list[tuple[int, int, str]]

    @classmethod
    def read(cls, path: Path, root: Path):
        code = code_only(path.read_text(encoding='utf-8-sig'))
        package_match = PACKAGE.search(code)
        package = package_match[1] if package_match else ''
        line_starts = [0] + [match.end() for match in re.finditer('\n', code)]
        imports = [(bisect_right(line_starts, match.start()), match[1]) for match in IMPORT.finditer(code)]
        stack = []
        braces = {}
        for match in re.finditer(r'[{}]', code):
            if match[0] == '{':
                stack.append(match.start())
            elif stack:
                braces[stack.pop()] = match.start()
        types = []
        for match in TYPE.finditer(code):
            opening = code.find('{', match.end())
            if opening not in braces:
                continue
            parent = next((name for begin, end, name in reversed(types) if begin < match.start() < end), package)
            types.append((opening, braces[opening], parent + '.' + match[1]))
        relative = path.relative_to(root).as_posix()
        return cls(path, relative, code, package, relative.split('/', 1)[0], imports, types)

    def owner_at(self, position: int) -> str:
        return next((name for begin, end, name in reversed(self.types) if begin < position < end), '')


class TypeIndex:
    def __init__(self, sources: list[Source]):
        self.types = {}
        for source in sources:
            for _, _, name in source.types:
                self.types.setdefault(name, []).append(source)

    @staticmethod
    def visible(source: Source, target: Source) -> bool:
        return target.module == 'common' or source.module == target.module

    def owner_name(self, name: str) -> str:
        candidate = name.removesuffix('.*')
        while '.' in candidate:
            if candidate in self.types:
                return candidate
            candidate = candidate.rsplit('.', 1)[0]
        return name

    def references(self, source: Source) -> list[Reference]:
        result = {Reference(line, name, '', True) for line, name in source.imports}
        imports = {name.rsplit('.', 1)[-1]: name for _, name in source.imports if not name.endswith('.*')}
        wildcards = [name[:-2] for _, name in source.imports if name.endswith('.*')]
        excluded = [match.span() for pattern in (IMPORT, PACKAGE) for match in pattern.finditer(source.code)]
        line_starts = [0] + [match.end() for match in re.finditer('\n', source.code)]
        for match in NAME.finditer(source.code):
            if any(begin <= match.start() < end for begin, end in excluded):
                continue
            name = re.sub(r'\s+', '', match[0])
            candidates = []
            if name.startswith(PREFIXES):
                candidates.append(name)
            else:
                first, _, rest = name.partition('.')
                if first in imports:
                    candidates.append(imports[first] + ('.' + rest if rest else ''))
                else:
                    for prefix in (source.package, *wildcards):
                        candidate = prefix + '.' + name
                        declared = self.types.get(self.owner_name(candidate), [])
                        if declared:
                            candidates.append(candidate)
            for target in candidates:
                result.add(Reference(bisect_right(line_starts, match.start()), target, source.owner_at(match.start())))
        return sorted(result, key=lambda item: (item.line, item.target, item.owner, item.is_import))
