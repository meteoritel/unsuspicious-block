"""读取资源 JSON 时保留重复键和非法数值错误。"""
import json
from pathlib import Path


def unique_object(pairs: list[tuple]) -> dict:
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f'重复 key：{key}')
        result[key] = value
    return result


def reject_constant(value: str) -> None:
    raise ValueError(f'非法 JSON 数值：{value}')


def loads(text: str):
    return json.loads(text, object_pairs_hook=unique_object, parse_constant=reject_constant)


def read_json(path: Path):
    return loads(path.read_text(encoding='utf-8-sig'))
