"""将 AutoJS 的传输信封收敛成显式 side 的短求值。"""
import json
import socket
import time
from urllib.error import URLError
from urllib.request import Request, urlopen


class ProbeError(RuntimeError):
    """配置、协议或运行环境未满足用例前置条件。"""


class AutoJS:
    def __init__(self, port: int, side: str):
        self.url = f'http://127.0.0.1:{port}/mcp'
        self.side = side
        self.sequence = 0
        self.diagnostics = []
        self.recoveries = []

    def rpc(self, method: str, params: dict):
        self.sequence += 1
        payload = dict(jsonrpc='2.0', id=self.sequence, method=method, params=params)
        request = Request(self.url, json.dumps(payload).encode(), {'Content-Type': 'application/json'})
        try:
            with urlopen(request, timeout=8) as response:
                envelope = json.load(response)
        except (URLError, TimeoutError, socket.timeout, ValueError) as error:
            raise ProbeError(f'{self.side} HTTP/JSON: {error}') from error
        if not isinstance(envelope, dict) or envelope.get('jsonrpc') != '2.0' or envelope.get('id') != self.sequence or 'error' in envelope or 'result' not in envelope:
            raise ProbeError(f'{self.side} JSON-RPC: {envelope}')
        return envelope['result']

    def tool(self, name: str, arguments: dict):
        result = self.rpc('tools/call', dict(name=name, arguments=arguments))
        try:
            content = result['content']
            if len(content) != 1 or content[0]['type'] != 'text':
                raise ValueError('需要单个 text 结果')
            value = json.loads(content[0]['text'])
        except (KeyError, IndexError, TypeError, ValueError) as error:
            raise ProbeError(f'{self.side} MCP 结果格式: {result}') from error
        if not isinstance(value, dict):
            raise ProbeError(f'{self.side} MCP 内层需要对象: {value}')
        self.diagnostics.extend(value.get('diagnostics', []))
        if result.get('isError') or value.get('ok') is False or name == 'eval_js' and value.get('ok') is not True:
            raise ProbeError(f'{self.side} {name}: {value}')
        return value

    def connect(self, process, timeout: float = 180):
        deadline = time.monotonic() + timeout
        last = '尚未连接'
        while time.monotonic() < deadline:
            if process.poll() is not None:
                raise ProbeError(f'{self.side} 启动退出 {process.returncode}，查看进程日志')
            try:
                self.rpc('initialize', dict(protocolVersion='2024-11-05', capabilities={},
                                            clientInfo=dict(name='usb-probes', version='1')))
                names = {tool['name'] for tool in self.rpc('tools/list', {})['tools']}
                if not {'status', 'eval_js'}.issubset(names):
                    raise ProbeError(f'缺少工具: {names}')
                status = self.tool('status', {})
                if any(s['side'] == self.side and s['available'] for s in status['sides']):
                    return status
                last = str(status)
            except ProbeError as error:
                last = str(error)
            time.sleep(.5)
        raise ProbeError(f'{self.side} 就绪超时: {last}')

    def evaluate(self, expression: str):
        result = self.tool('eval_js', dict(code=f'JSON.stringify({expression})', side=self.side, timeout_ms=3000))
        try:
            return json.loads(result['value'])
        except (KeyError, TypeError, ValueError) as error:
            raise ProbeError(f'{self.side} 非结构化或截断求值: {result}') from error

    def install(self, source: str):
        self.tool('eval_js', dict(code=source, side=self.side, timeout_ms=3000))

    def action(self, action_id: str, expression: str):
        key = json.dumps(action_id)
        try:
            result = self.evaluate(f'Probe.action({key}, function() {{ return {expression}; }})')
        except ProbeError as original:
            # AutoJS 超时不取消任务；查询排在同一 side 队列后，永不重发副作用。
            try:
                result = self.evaluate(f'Probe.actions[{key}] || null')
            except ProbeError:
                raise original
            if result is None or result.get('state') == 'running':
                raise ProbeError(f'{action_id} 结果不明，需终止本实例: {original}') from original
            self.recoveries.append(dict(actionId=action_id, state=result['state'], cause=str(original)))
        if result['state'] != 'done':
            raise ProbeError(f'{action_id}: {result}')
        return result.get('value')
