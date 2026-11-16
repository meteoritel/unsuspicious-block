"""JEI 配方 PNG 的安装、清单与串行导出入口；仅连接已启动的客户端。"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import math
import os
from pathlib import Path
import re
import shutil
import struct
import subprocess
import sys
import tempfile
import time
import tomllib
from uuid import uuid4
import zipfile

TOOLS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TOOLS))
sys.path.insert(0, str(TOOLS / 'probes'))
from toolslib.cli import configure_console
from toolslib.repo import repo_root, properties
from probelib.autojs import AutoJS, ProbeError

SCRIPT = Path(__file__).resolve().parent / 'scripts/export.js'
BRIDGE = 'com.meteorite.tools.recipecards.client.RecipeCardExports'
MOD_ID = 'usb_recipe_cards'
DEFAULT_PORT = 25586
MANIFEST = 'recipe-cards.json'


def integer(minimum, maximum):
    def parse(value):
        number = int(value)
        if not minimum <= number <= maximum:
            raise argparse.ArgumentTypeError(f'需要 {minimum}..{maximum} 的整数')
        return number
    return parse


def seconds(value):
    number = float(value)
    if not math.isfinite(number) or number <= 0:
        raise argparse.ArgumentTypeError('超时需要有限正数（秒）')
    return number


def resource_id(value):
    if not re.fullmatch(r'[a-z0-9_.-]+:[a-z0-9_./-]+', value):
        raise argparse.ArgumentTypeError('需要 namespace:path 形式的完整 ID')
    return value


def namespace(value):
    if value != '*' and not re.fullmatch(r'[a-z0-9_.-]+', value):
        raise argparse.ArgumentTypeError('需要命名空间，或 * 表示全部')
    return value


def read_json(path):
    return json.loads(path.read_text(encoding='utf-8'))


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def write_manifest(path, record):
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(mode='w', encoding='utf-8', newline='\n',
                                         dir=path.parent, delete=False, suffix='.json') as output:
            temporary = Path(output.name)
            json.dump(record, output, ensure_ascii=False, indent=2)
            output.write('\n')
        temporary.replace(path)
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def mod_ids(path):
    with zipfile.ZipFile(path) as jar:
        if 'META-INF/neoforge.mods.toml' not in jar.namelist():
            return set()
        metadata = tomllib.loads(jar.read('META-INF/neoforge.mods.toml').decode('utf-8'))
        return {entry['modId'] for entry in metadata.get('mods', [])}


def install(args, root):
    runtime = Path(__file__).resolve().parent / 'runtime'
    if args.artifact is None:
        wrapper = root / ('gradlew.bat' if os.name == 'nt' else 'gradlew')
        subprocess.run([str(wrapper), '-p', str(runtime), 'writeToolArtifact', '--console=plain'],
                       cwd=root, check=True)
    artifact = read_json(args.artifact or runtime / 'build/artifact.json')
    versions = properties(root)
    for key in ('minecraft', 'neoforge', 'jei'):
        if artifact[key] != versions[f'{key}_version']:
            raise ValueError(f'工具产物与根版本不符：{key}，请重新构建')
    jar = Path(artifact['jar']).resolve(strict=True)
    if MOD_ID not in mod_ids(jar):
        raise ValueError(f'产物不是配方卡片工具：{jar}')
    game = (args.game_dir or root / 'neoforge/run').resolve()
    mods = game / 'mods'
    mods.mkdir(parents=True, exist_ok=True)
    config_path = args.config or root / '.local/probes.json'
    config = read_json(config_path) if config_path.exists() else {}
    if args.config is not None and not config_path.exists():
        raise ValueError(f'配置不存在：{config_path}')
    if not isinstance(config, dict):
        raise ValueError('运行依赖配置需要 JSON 对象')
    port = args.port or config.get('clientMcpPort', DEFAULT_PORT)
    if type(port) is not int or not 1024 <= port <= 65535:
        raise ValueError('clientMcpPort 需要 1024..65535 的整数')
    sources = {MOD_ID: jar}
    for mod_id, explicit, key in (('autojs', args.autojs, 'autojsJar'), ('rhino', args.rhino, 'rhinoJar')):
        value = explicit or config.get(key)
        if value:
            source = Path(value).resolve(strict=True)
            if mod_id not in mod_ids(source):
                raise ValueError(f'{source} 不是 NeoForge {mod_id} 模组')
            sources[mod_id] = source
    autojs_config = game / 'config/autojs.json'
    current = read_json(autojs_config) if autojs_config.exists() else {}
    if not isinstance(current, dict):
        raise ValueError(f'AutoJS 配置需要 JSON 对象：{autojs_config}')
    desired = dict(current, enabled=True, unrestricted=True, bindAddress='127.0.0.1', port=port,
                   enableClientSide=True, enableServerSide=False, evalTimeoutMs=3000,
                   maxResultChars=100000, logEval=True)

    # 所有备份均进入主仓本地目录；按已识别的模组 ID 逐文件移动。
    backup = (root / '.local/recipe-cards/retired' / datetime.now().strftime('%Y%m%d-%H%M%S-%f')).resolve()
    if not backup.is_relative_to((root / '.local/recipe-cards/retired').resolve()):
        raise ValueError('备份路径超出工具本地目录')

    def retire(path):
        if not path.resolve().is_relative_to(game):
            raise ValueError(f'备份来源超出游戏目录：{path}')
        backup.mkdir(parents=True, exist_ok=True)
        target = backup / path.name
        if target.exists():
            target = backup / f'{uuid4().hex}-{path.name}'
        shutil.move(str(path), str(target))
        print(f'已备份：{target}')

    installed = {}
    for path in mods.glob('*.jar'):
        for mod_id in mod_ids(path):
            installed.setdefault(mod_id, []).append(path)
    for mod_id, source in sources.items():
        previous_jars = installed.get(mod_id, [])
        destination = source if source in [path.resolve() for path in previous_jars] else mods / f'{mod_id}-recipe-cards-local.jar'
        for previous in previous_jars:
            if previous != destination or digest(previous) != digest(source):
                retire(previous)
        if source != destination.resolve() and (not destination.exists() or digest(source) != digest(destination)):
            shutil.copy2(source, destination)
        if digest(source) != digest(destination):
            raise ValueError(f'安装副本摘要不符：{destination}')
        print(f'已安装：{destination}')
    for previous in installed.get('recipe_card_exporter', []):
        retire(previous)

    if current != desired:
        if autojs_config.exists():
            retire(autojs_config)
        autojs_config.parent.mkdir(parents=True, exist_ok=True)
        write_manifest(autojs_config, desired)
    missing = [mod_id for mod_id in ('autojs', 'rhino') if mod_id not in sources and mod_id not in installed]
    print(f'客户端配置：{autojs_config}（仅 loopback，端口 {port}）')
    if missing:
        print('尚缺运行依赖：' + '、'.join(missing) + '；用 --autojs / --rhino 指定 JAR 后再次安装。')
    print('安装结束。请重启 NeoForge 客户端并进入世界；本命令只安装文件。')
    return 0


def connect(port):
    client = AutoJS(port, 'client')
    client.rpc('initialize', dict(protocolVersion='2024-11-05', capabilities={},
                                 clientInfo=dict(name='usb-recipe-cards', version='1')))
    names = {tool['name'] for tool in client.rpc('tools/list', {})['tools']}
    if not {'status', 'eval_js'}.issubset(names):
        raise ProbeError('AutoJS 缺少 status / eval_js')
    state = client.tool('status', {})
    if not any(side['side'] == 'client' and side['available'] for side in state['sides']):
        raise ProbeError('客户端 JS 尚未就绪，请进入世界并等待加载完成')
    return client


def call(client, expression):
    return client.evaluate(expression)


def selection(client, args):
    filters = dict(namespaces=args.namespace or ['unsuspiciousblock'],
                   categories=args.category or [], recipes=args.recipe or [])
    token = uuid4().hex
    client.install(SCRIPT.read_text(encoding='utf-8'))
    key = json.dumps(token)
    try:
        state = call(client, f'RecipeCards.begin({key}, {json.dumps(filters)})')
    except ProbeError as original:
        # 求值超时仍可能在主线程完成；查询同一 token，不重复初始化。
        try:
            state = call(client, f'RecipeCards.summary({key})')
        except ProbeError:
            raise original
    deadline = time.monotonic() + args.timeout
    while state['state'] == 'scanning':
        if time.monotonic() >= deadline:
            raise ProbeError('配方清单读取超时，请缩小筛选范围')
        try:
            state = call(client, f'RecipeCards.step({key})')
        except ProbeError:
            state = call(client, f'RecipeCards.summary({key})')
    if state['state'] != 'ready':
        raise ProbeError(f'JEI 配方清单失败：{state}')
    entries = []
    for offset in range(0, state['total'], 64):
        entries.extend(call(client, f'RecipeCards.page({key}, {offset})'))
    return token, filters, entries


def filename(entry, variant):
    position = entry['sourceIndex'] if entry['recipe'] is None else entry.get('occurrence', 0)
    identity = json.dumps([entry['category'], entry['recipe'], position, variant], separators=(',', ':'))
    suffix = hashlib.sha256(identity.encode()).hexdigest()[:12]
    name = entry['recipe'] or f"{entry['category']}-anonymous-{entry['sourceIndex']}"
    stem = re.sub(r'[^a-zA-Z0-9._-]', '_', name)[:90].rstrip('. ')
    return f'card-{stem}-v{variant}-{suffix}.png'


def png_dimensions(path):
    with path.open('rb') as stream:
        header = stream.read(24)
    if len(header) != 24 or header[:8] != b'\x89PNG\r\n\x1a\n' or header[12:16] != b'IHDR':
        raise ValueError(f'输出不是有效 PNG：{path}')
    return struct.unpack('>II', header[16:24])


def wait_for_card(client, request_id, timeout):
    deadline = time.monotonic() + timeout
    while True:
        result = call(client, f'JSON.parse(String(Java.loadClass({json.dumps(BRIDGE)}).status({json.dumps(request_id)})))')
        if result['state'] in ('done', 'failed'):
            return result
        if result['state'] == 'unknown':
            raise ProbeError(f'请求状态未知：{request_id}；本次停止，不重发请求')
        if time.monotonic() >= deadline:
            raise ProbeError(f'导出超时：{request_id}；请求可能仍在执行，用 status 查询后处理')
        time.sleep(.15)


def export_cards(client, args, token, filters, entries):
    if not entries:
        raise ValueError('筛选结果为空；可先用 list 核对 category / recipe，--namespace "*" 查询全部')
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    manifest = output / MANIFEST
    cards = [dict(entry, file=filename(entry, args.variant), requestId=uuid4().hex, state='pending') for entry in entries]
    if not args.overwrite:
        for path in [manifest, *(output / card['file'] for card in cards)]:
            if path.exists():
                raise FileExistsError(f'输出已存在：{path}；换目录或显式使用 --overwrite')
    record = dict(schemaVersion=1, createdAt=datetime.now(timezone.utc).isoformat(),
                  filters=filters, scale=args.scale, variant=args.variant, cards=cards)
    # 先预留清单位置，再提交任何写 PNG 的请求。
    with manifest.open('w' if args.overwrite else 'x', encoding='utf-8', newline='\n') as stream:
        json.dump(record, stream, ensure_ascii=False, indent=2)
        stream.write('\n')
    try:
        for card in cards:
            path = output / card['file']
            options = dict(id=card['requestId'], path=str(path), scale=args.scale,
                           variant=args.variant, overwrite=args.overwrite)
            card['state'] = 'submitted'
            write_manifest(manifest, record)
            try:
                call(client, f'RecipeCards.submit({json.dumps(token)}, {card["index"]}, {json.dumps(options)})')
            except ProbeError as error:
                card['submitDiagnostic'] = str(error)
                # submit 只尝试一次；即使传输失败，也只查询原 id。
            try:
                result = wait_for_card(client, card['requestId'], args.timeout)
            except ProbeError as error:
                if 'submitDiagnostic' in card:
                    raise ProbeError(f'{error}；提交诊断：{card["submitDiagnostic"]}') from error
                raise
            card.update(state=result['state'], result=result)
            if result['state'] == 'done':
                if png_dimensions(path) != (result['width'], result['height']):
                    raise ValueError(f'PNG 尺寸与渲染回执不符：{path}')
                card['sha256'] = digest(path)
            print(f'{card["state"]}: {card["file"]}')
            write_manifest(manifest, record)
    except (ProbeError, OSError, ValueError, KeyboardInterrupt) as error:
        if card['state'] in ('submitted', 'done'):
            card['state'] = 'unknown' if card['state'] == 'submitted' else 'invalid'
            card['error'] = str(error) or type(error).__name__
        record['interruption'] = str(error) or type(error).__name__
        write_manifest(manifest, record)
        raise
    print(f'清单：{manifest}')
    return 1 if any(card['state'] == 'failed' for card in cards) else 0


def parser():
    cli = argparse.ArgumentParser(description=__doc__)
    commands = cli.add_subparsers(dest='command', required=True)
    setup = commands.add_parser('install', help='构建、安装独立工具并备份旧工具；不启动游戏')
    setup.add_argument('--game-dir', type=Path, help='游戏目录，默认 neoforge/run')
    setup.add_argument('--artifact', type=Path, help='复用 writeToolArtifact 生成的产物清单')
    setup.add_argument('--config', type=Path, help='读取 autojsJar/rhinoJar/clientMcpPort，默认 .local/probes.json（可缺省）')
    setup.add_argument('--autojs', type=Path, help='AutoJS NeoForge JAR')
    setup.add_argument('--rhino', type=Path, help='Rhino NeoForge JAR')
    setup.add_argument('--port', type=integer(1024, 65535), help='客户端 AutoJS loopback 端口')
    for command in ('list', 'export', 'status'):
        sub = commands.add_parser(command, help={'list': '读取已启动客户端的 JEI 配方',
                                               'export': '串行导出 PNG 与 JSON 清单',
                                               'status': '查询当前客户端保留的最后一个请求'}[command])
        sub.add_argument('--port', type=integer(1024, 65535), default=DEFAULT_PORT)
        if command == 'status':
            sub.add_argument('request_id')
            continue
        sub.add_argument('--namespace', action='append', type=namespace, help='可重复，默认 unsuspiciousblock；* 表示全部')
        sub.add_argument('--category', action='append', type=resource_id, help='可重复，按 JEI category UID 精确筛选')
        sub.add_argument('--recipe', action='append', type=resource_id, help='可重复，按配方 ID 精确筛选')
        sub.add_argument('--timeout', type=seconds, default=60, help='清单读取/单张导出超时秒数，默认 %(default)s')
        if command == 'export':
            sub.add_argument('--output', type=Path, required=True, help='PNG 与 recipe-cards.json 目录')
            sub.add_argument('--scale', type=integer(1, 6), default=3, help='整数像素倍率，默认 %(default)s')
            sub.add_argument('--variant', type=integer(0, 2_147_483_647), default=0, help='槽位候选下标，默认 %(default)s，各槽位按候选数取模')
            sub.add_argument('--overwrite', action='store_true', help='允许覆盖同名 PNG 与导出清单')
    return cli


def main():
    configure_console()
    args = parser().parse_args()
    try:
        if args.command == 'install':
            return install(args, repo_root())
        client = connect(args.port)
        if args.command == 'status':
            result = call(client, f'JSON.parse(String(Java.loadClass({json.dumps(BRIDGE)}).status({json.dumps(args.request_id)})))')
            print(json.dumps(result, ensure_ascii=False, indent=2))
            return 1 if result['state'] in ('failed', 'unknown') else 0
        token, filters, entries = selection(client, args)
        if args.command == 'list':
            print(json.dumps(dict(filters=filters, recipes=entries), ensure_ascii=False, indent=2))
            return 0
        return export_cards(client, args, token, filters, entries)
    except (ProbeError, OSError, ValueError, KeyError, TypeError, subprocess.CalledProcessError,
            zipfile.BadZipFile) as error:
        print(f'配方卡片工具：{error}', file=sys.stderr)
        return 2
    except KeyboardInterrupt:
        print('已中断；检查清单中的 requestId，可用 status 查询最后请求。', file=sys.stderr)
        return 2


if __name__ == '__main__':
    raise SystemExit(main())
