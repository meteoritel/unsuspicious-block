"""读取仓库使用的 Markdown 链接，隔离代码示例并保留源位置。"""
from dataclasses import dataclass
import re


@dataclass(frozen=True)
class Link:
    target: str
    line: int


def prose_view(text: str) -> str:
    result = []
    fence = ''
    for line in text.splitlines(keepends=True):
        match = re.match(r'^\s{0,3}(`{3,}|~{3,})', line)
        if match and not fence:
            fence = match[1]
        elif fence and re.match(r'^\s{0,3}' + re.escape(fence[0]) + '{' + str(len(fence)) + r',}\s*$', line.rstrip()):
            fence = ''
            result.append(re.sub(r'[^\n]', ' ', line))
            continue
        result.append(re.sub(r'[^\n]', ' ', line) if fence else line)
    view = ''.join(result)
    view = re.sub(r'<!--.*?-->', lambda m: re.sub(r'[^\n]', ' ', m[0]), view, flags=re.S)
    return re.sub(r'(`+)(?!`)(.*?)(?<!`)\1(?!`)', lambda m: re.sub(r'[^\n]', ' ', m[0]), view, flags=re.S)


def bracket_end(text: str, start: int) -> int:
    depth = 1
    index = start + 1
    while index < len(text):
        if text[index] == '\\':
            index += 2
            continue
        if text[index] == '[':
            depth += 1
        elif text[index] == ']':
            depth -= 1
            if not depth:
                return index
        index += 1
    return -1


def destination(text: str, start: int) -> str:
    index = start
    while index < len(text) and text[index].isspace():
        index += 1
    if index < len(text) and text[index] == '<':
        end = text.find('>', index + 1)
        return text[index + 1:end] if end >= 0 else ''
    begin = index
    depth = 0
    while index < len(text):
        char = text[index]
        if char == '\\':
            index += 2
            continue
        if char == '(':
            depth += 1
        elif char == ')':
            if not depth:
                break
            depth -= 1
        if char.isspace() and not depth:
            break
        index += 1
    return text[begin:index]


def links(text: str) -> list[Link]:
    view = prose_view(text)
    definitions = {}
    definition_spans = []
    for match in re.finditer(r'^ {0,3}\[([^\]\n]+)\]:\s*(.*)$', view, re.M):
        definitions[' '.join(match[1].lower().split())] = destination(match[2], 0)
        definition_spans.append(match.span())
    result = []
    for match in re.finditer(r'(?<!\\)\[', view):
        start = match.start()
        if any(begin <= start < end for begin, end in definition_spans):
            continue
        end = bracket_end(view, start)
        if end < 0:
            continue
        after = end + 1
        target = ''
        if view[after:after + 1] == '(':
            target = destination(view, after + 1)
        else:
            label = view[start + 1:end]
            if view[after:after + 1] == '[':
                ref_end = bracket_end(view, after)
                if ref_end >= 0:
                    label = view[after + 1:ref_end] or label
            target = definitions.get(' '.join(label.lower().split()), '')
        if target:
            result.append(Link(target, view.count('\n', 0, start) + 1))
    for match in re.finditer(r'<(?:img|a)\b[^>]*?\b(?:src|href)\s*=\s*["\']([^"\']+)["\'][^>]*>', view, re.I):
        result.append(Link(match[1], view.count('\n', 0, match.start()) + 1))
    return result
