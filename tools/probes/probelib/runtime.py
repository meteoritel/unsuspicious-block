"""独立启动材料与隔离存档的所有权。"""
import hashlib
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import time
import tomllib
import zipfile

from .autojs import AutoJS, ProbeError


def write_json(path: Path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


def digest(path: Path) -> str:
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def read_config(path: Path) -> dict:
    config = json.loads(path.read_text(encoding='utf-8'))
    if not isinstance(config, dict):
        raise ProbeError('探针配置须为 JSON 对象')
    for key, mod_id in (('autojsJar', 'autojs'), ('rhinoJar', 'rhino')):
        jar = Path(config[key]).resolve(strict=True)
        with zipfile.ZipFile(jar) as archive:
            metadata = tomllib.loads(archive.read('META-INF/neoforge.mods.toml').decode())
            if mod_id not in {mod['modId'] for mod in metadata['mods']}:
                raise ProbeError(f'{key} 不是预期 NeoForge 模组: {jar}')
        config[key] = str(jar)
    if config.get('acceptEula') is not True:
        raise ProbeError('首次运行需接受 Minecraft EULA，并在本地配置显式设置 acceptEula=true')
    ports = [config.setdefault(key, default) for key, default in
             (('serverMcpPort', 25585), ('clientMcpPort', 25586), ('gamePort', 25575))]
    if len(set(ports)) != len(ports) or any(type(port) is not int or not 1024 <= port <= 65535 for port in ports):
        raise ProbeError('游戏与 MCP 端口需互异且位于 1024..65535')
    for port in ports:
        with socket.socket() as listener:
            listener.bind(('127.0.0.1', port))
    return config


def read_artifact(path: Path) -> dict:
    host = json.loads(path.read_text(encoding='utf-8'))
    if not isinstance(host, dict) or any(not isinstance(host.get(key), str) or not host[key]
                                        for key in ('jar', 'kit', 'minecraft', 'neoforge')):
        raise ProbeError('产物 manifest 需要 jar、kit、minecraft、neoforge 字符串')
    Path(host['jar']).resolve(strict=True)
    return host


def gradle(root: Path, arguments: list[str], log: Path):
    command = [str(root / ('gradlew.bat' if os.name == 'nt' else 'gradlew')), *arguments, '--console=plain']
    with log.open('w', encoding='utf-8') as output:
        result = subprocess.run(command, cwd=root, stdout=output, stderr=subprocess.STDOUT, check=False)
    if result.returncode:
        raise ProbeError(f'Gradle 退出 {result.returncode}: {log}')


class Runtime:
    def __init__(self, root: Path, directory: Path, config: dict):
        self.root, self.directory, self.config = root, directory, config
        self.processes = {}
        self.outputs = {}
        self.controls = {}
        self.launch = {}
        self.manifest = {}
        self.history = []

    def prepare(self, artifact: Path | None):
        if artifact is None:
            gradle(self.root, ['build', 'writeProbeArtifact'], self.directory / 'build.log')
            artifact = self.root / 'build/probes/artifact.json'
        host = read_artifact(artifact)
        host_jar = Path(host['jar']).resolve(strict=True)
        with zipfile.ZipFile(host_jar) as archive:
            nested = {name: hashlib.sha256(archive.read(name)).hexdigest()
                      for name in archive.namelist() if name.endswith('.jar')}
            if not any('meteos-ui-kit' in name for name in nested):
                raise ProbeError('宿主发布 JAR 未内嵌 kit')
        runtime = self.root / 'tools/probes/runtime'
        gradle(self.root, ['-p', str(runtime), 'prepareProbeRuntime'], self.directory / 'prepare.log')
        self.launch = json.loads((runtime / 'build/launch.json').read_text(encoding='utf-8'))
        if (host['minecraft'], host['neoforge']) != (self.launch['minecraft'], self.launch['neoforge']):
            raise ProbeError('发布物 manifest 与运行 Loader 不一致')
        # 独立工程无需宿主输出；意外接入源码时提前拒绝。
        for path in (runtime / 'build/moddev').glob('*Classpath.txt'):
            text = path.read_text(encoding='utf-8').replace('\\\\', '/').replace('\\', '/')
            if '/classes/java/main' in text or '/resources/main' in text or 'unsuspiciousBlock-1.21.1-multi/neoforge/build' in text:
                raise ProbeError(f'运行面混入宿主源码: {path}')
        jars = [host_jar, Path(self.config['autojsJar']), Path(self.config['rhinoJar'])]
        self.manifest = dict(artifactMode='jar', host=host, nested=nested,
                             dependencies=[dict(path=str(jar), sha256=digest(jar)) for jar in jars],
                             launch=self.launch, processes=self.history)
        for side in ('server', 'client'):
            game = self.directory / side
            (game / 'mods').mkdir(parents=True)
            for jar in jars:
                shutil.copy2(jar, game / 'mods' / jar.name)
                if digest(game / 'mods' / jar.name) != self.manifest['dependencies'][jars.index(jar)]['sha256']:
                    raise ProbeError(f'复制期间发布物变化: {jar}')
            write_json(game / 'config/autojs.json', dict(enabled=True, unrestricted=True,
                       bindAddress='127.0.0.1', port=self.config[f'{side}McpPort'],
                       enableServerSide=True, enableClientSide=True, evalTimeoutMs=3000,
                       maxResultChars=100000, logEval=True))
        server = self.directory / 'server'
        (server / 'eula.txt').write_text('eula=true\n', encoding='utf-8')
        (server / 'server.properties').write_text(
            f'server-ip=127.0.0.1\nserver-port={self.config["gamePort"]}\nonline-mode=false\n'
            'level-type=minecraft:flat\nspawn-protection=0\nview-distance=3\nsimulation-distance=3\n'
            'max-players=1\nallow-flight=true\ngamemode=survival\ndifficulty=peaceful\n', encoding='utf-8')
        resources = self.root / 'tools/probes/datapack'
        if resources.exists():
            shutil.copytree(resources, server / 'world/datapacks/usb_probe')
        (self.directory / 'client/options.txt').write_text(
            'pauseOnLostFocus:false\nguiScale:2\nrenderDistance:3\nmaxFps:30\nfullscreen:false\n'
            'tutorialStep:none\nlang:en_us\n', encoding='utf-8')
        write_json(self.directory / 'manifest.json', self.manifest)

    def start(self, side: str):
        if side in self.processes and self.processes[side].poll() is None:
            raise ProbeError(f'{side} 已在运行')
        output = (self.directory / f'{side}-process-{time.time_ns()}.log').open('w', encoding='utf-8')
        self.outputs[side] = output
        argv = [self.config.get('java', self.launch['java']), '-Xmx2G',
                f'-Dautojs.port={self.config[f"{side}McpPort"]}', *self.launch[side]]
        if side == 'client':
            argv += ['--username', 'UsbProbe', '--uuid', 'b9c5a31d4c87383899d08bb059420281',
                     '--accessToken', '0', '--quickPlayMultiplayer', f'127.0.0.1:{self.config["gamePort"]}',
                     '--width', '1100', '--height', '720']
        environment = {key: value for key, value in os.environ.items() if not key.startswith('AUTOJS_')}
        process = subprocess.Popen(argv, cwd=self.directory / side, stdin=subprocess.PIPE,
                                   stdout=output, stderr=subprocess.STDOUT, text=True, encoding='utf-8', env=environment)
        self.processes[side] = process
        self.history.append(dict(side=side, pid=process.pid, argv=argv, gameDir=str(self.directory / side),
                                 log=Path(output.name).name, exitCode=None))
        write_json(self.directory / 'manifest.json', self.manifest)
        control = AutoJS(self.config[f'{side}McpPort'], side)
        self.controls[side] = control
        control.connect(process)
        self.install(side)
        return control

    def install(self, side: str):
        source = (self.root / 'tools/probes/scripts/shared.js').read_text(encoding='utf-8')
        source += f'\nProbe.runId = {json.dumps(self.directory.name)};\n'
        source += (self.root / f'tools/probes/scripts/{side}.js').read_text(encoding='utf-8')
        self.controls[side].install(source)

    def stop(self, side: str):
        process = self.processes.get(side)
        if process is None:
            return
        forced = False
        if process.poll() is None:
            if side == 'server':
                process.stdin.write('stop\n')
                process.stdin.flush()
            else:
                try:
                    self.controls[side].evaluate('(Probe.type("net.minecraft.client.Minecraft").getInstance().stop(), true)')
                except ProbeError:
                    pass  # stop 可先关闭 MCP；退出状态由自有进程确认。
            try:
                process.wait(timeout=40)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=10)
                forced = True
        self.outputs[side].close()
        process.stdin.close()
        self.controls.pop(side, None)
        self.processes.pop(side, None)
        next(entry for entry in reversed(self.history) if entry['pid'] == process.pid)['exitCode'] = process.returncode
        write_json(self.directory / 'manifest.json', self.manifest)
        if forced:
            raise ProbeError(f'{side} 未正常退出，已终止自有 PID {process.pid}')
        if process.returncode:
            raise ProbeError(f'{side} 退出码 {process.returncode}')

    def fresh_client(self):
        if 'client' in self.processes:
            raise ProbeError('清空客户端缓存前须先正常停止客户端')
        client = self.directory / 'client'
        backup = self.directory / f'client-saved-{time.time_ns()}'
        client.rename(backup)
        client.mkdir()
        for name in ('mods', 'config'):
            shutil.copytree(backup / name, client / name)
        shutil.copy2(backup / 'options.txt', client / 'options.txt')
        self.manifest.setdefault('freshClientProfiles', []).append(str(backup))
        write_json(self.directory / 'manifest.json', self.manifest)

    def close(self):
        errors = []
        for side in ('client', 'server'):
            try:
                self.stop(side)
            except (ProbeError, OSError) as error:
                errors.append(str(error))
        return errors
