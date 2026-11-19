"""执行已登记的必验项，并保留独立预期和原始观察。"""
from dataclasses import dataclass
import time

from .autojs import ProbeError


@dataclass(frozen=True)
class Check:
    id: str
    basis: str
    source: str


class Verdict:
    def __init__(self, required):
        self.required = {check.id: check for check in required}
        self.results = {}

    def check(self, check_id, actual, expected):
        check = self.required.get(check_id)
        if check is None or check_id in self.results:
            raise ProbeError(f'必验项未登记或重复执行: {check_id}')
        passed = actual == expected
        self.results[check_id] = dict(id=check_id, basis=check.basis, source=check.source, phase='assert',
                                      actual=actual, expected=expected, passed=passed)
        if not passed:
            raise AssertionError(f'{check_id}: expected={expected!r}, actual={actual!r}')

    def wait(self, check_id, observe, expected, timeout):
        deadline = time.monotonic() + timeout
        while True:
            actual = observe()
            if actual == expected or time.monotonic() >= deadline:
                self.check(check_id, actual, expected)
                return
            time.sleep(.15)

    def complete(self):
        missing = self.required.keys() - self.results.keys()
        if not self.required or missing:
            raise ProbeError('缺少必验项: ' + (', '.join(sorted(missing)) or '用例未声明'))
        failed = [name for name, result in self.results.items() if not result['passed']]
        if failed:
            raise AssertionError('必验项失败: ' + ', '.join(failed))
