"""Run a real modded client against an isolated loopback dedicated server."""

import argparse
import json
import os
from pathlib import Path
import re
import socket
import subprocess
import sys
import time
from datetime import datetime


ROOT = Path(__file__).resolve().parent.parent
PLAYER = 'ConnectionTest'
UUID = 'a89b1ff42dd941f38e411e86c426df95'
FATAL = re.compile(
    r'Failed to (?:decode|encode) packet|[Dd]ecoderException|[Ee]ncoderException|'
    r'This channel is missing|mismatched mod channel|Incompatible client|'
    r'Failed to handle packet|Encountered an unexpected exception|'
    r'Mixin apply .*failed|Failed to start the minecraft server'
)


def message(value):
    print(f'[{time.strftime("%H:%M:%S")}] {value}', flush=True)


def read_text(path):
    return path.read_text(encoding='utf-8', errors='replace') if path.exists() else ''


def wait_for(check, timeout, processes, description):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        value = check()
        if value:
            return value
        for name, process in processes:
            if process.poll() is not None:
                raise RuntimeError(f'{name} exited with code {process.returncode}: {description}')
        time.sleep(1)
    raise TimeoutError(description)


def launch(config, directory, extra_arguments, output):
    config = json.loads(config.read_text(encoding='utf-8'))
    arguments = list(config['arguments'])
    # Put memory limits after inherited JVM options and before the main class.
    classpath = arguments.index('-classpath')
    arguments[classpath:classpath] = ['-Xms256m', '-Xmx2G']
    arguments.extend(extra_arguments)
    argument_file = directory / (output.stem + '.args')
    argument_file.write_text(
        '\n'.join(json.dumps(str(arg), ensure_ascii=False) for arg in arguments),
        encoding='utf-8',
    )
    environment = os.environ.copy()
    environment.update({key: str(value) for key, value in config['environment'].items()})
    with output.open('w', encoding='utf-8') as log:
        return subprocess.Popen(
            [config['java'], *['@' + path for path in config['argumentFiles']],
             '@' + str(argument_file)], cwd=directory, env=environment,
            stdin=subprocess.PIPE, stdout=log, stderr=subprocess.STDOUT,
            creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0,
        )


def stop(process, command=None):
    if process is None or process.poll() is not None:
        return
    if command:
        try:
            process.stdin.write((command + '\n').encode())
            process.stdin.flush()
            process.wait(timeout=40)
            return
        except (BrokenPipeError, OSError, subprocess.TimeoutExpired):
            pass
    process.terminate()
    try:
        process.wait(timeout=15)
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait(timeout=10)


def run_loader(loader, args):
    stamp = datetime.now().strftime('%Y%m%d-%H%M%S-%f')
    directory = ROOT / 'build' / 'connection-smoke' / loader / stamp
    server_dir = directory / 'server'
    client_dir = directory / 'client'
    server_dir.mkdir(parents=True)
    client_dir.mkdir()
    report = {'loader': loader, 'status': 'FAIL', 'directory': str(directory),
              'online_seconds': args.seconds, 'attempts': [], 'started_at': datetime.now().isoformat()}
    properties = dict(line.split('=', 1) for line in
                      (ROOT / 'gradle.properties').read_text(encoding='utf-8').splitlines()
                      if '=' in line and not line.lstrip().startswith('#'))
    loader_key = 'fabric_loader_version' if loader == 'fabric' else 'neoforge_version'
    report.update(minecraft_version=properties['minecraft_version'],
                  mod_version=properties['version'], loader_version=properties[loader_key])
    server = client = None
    server_log = directory / 'server-console.log'
    try:
        with socket.socket() as probe:
            probe.bind(('127.0.0.1', args.port))
        if not args.accept_eula:
            raise RuntimeError('Read the Minecraft EULA and pass --accept-eula to start the test server.')
        (server_dir / 'eula.txt').write_text('eula=true\n', encoding='ascii')
        (server_dir / 'server.properties').write_text(
            f'server-ip=127.0.0.1\nserver-port={args.port}\nonline-mode=false\n'
            'enforce-secure-profile=false\nview-distance=3\nsimulation-distance=3\n'
            'max-players=2\ngamemode=creative\nforce-gamemode=true\n'
            'level-type=minecraft:flat\nspawn-protection=0\n'
            'generator-settings={"biome":"minecraft:plains","layers":['
            '{"block":"minecraft:bedrock","height":1},'
            '{"block":"minecraft:dirt","height":2},'
            '{"block":"minecraft:grass_block","height":1}]}\n'
            'motd=UnsuspiciousBlock connection smoke test\n', encoding='ascii')
        (client_dir / 'options.txt').write_text(
            'renderDistance:3\nsimulationDistance:5\nmaxFps:30\n'
            'pauseOnLostFocus:false\nonboardAccessibility:false\n'
            'skipMultiplayerWarning:true\n', encoding='ascii')
        message(f'{loader}: compiling and preparing isolated launches')
        command = [str(ROOT / 'gradlew.bat'), '--console=plain', '--no-daemon',
                   '-I', str(ROOT / 'tools' / 'connection-smoke.init.gradle'),
                   f'-PconnectionSmokeLoader={loader}', f'-PconnectionSmokeDirectory={directory}',
                   f':{loader}:prepareConnectionSmoke']
        if args.offline:
            command.insert(1, '--offline')
        with (directory / 'prepare.log').open('w', encoding='utf-8') as log:
            preparation = subprocess.run(command, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT,
                                         timeout=args.prepare_timeout)
        if preparation.returncode:
            raise RuntimeError(f'Gradle preparation failed; see {directory / "prepare.log"}')
        server = launch(directory / 'server-launch.json', server_dir, ['nogui'], server_log)
        report['server_pid'] = server.pid
        message(f'{loader}: waiting for dedicated server (PID {server.pid})')
        wait_for(lambda: re.search(r'Done \([^)]+\)!', read_text(server_log)), args.timeout,
                 [('server', server)], 'Dedicated server startup timed out')
        message(f'{loader}: dedicated server ready on 127.0.0.1:{args.port}')
        for attempt in range(1, args.attempts + 1):
            client_log = directory / f'client-{attempt}-console.log'
            quickplay = client_dir / f'quickplay-{attempt}.json'
            baseline = len(read_text(server_log))
            client = launch(directory / 'client-launch.json', client_dir,
                            ['--username', PLAYER, '--uuid', UUID, '--width', '854', '--height', '480',
                             '--quickPlayMultiplayer', f'127.0.0.1:{args.port}',
                             '--quickPlayPath', quickplay.name], client_log)
            message(f'{loader}: connection {attempt}/{args.attempts}, client PID {client.pid}')

            def connected():
                logs = read_text(server_log)[baseline:]
                errors = FATAL.search(logs + '\n' + read_text(client_log))
                if errors:
                    raise RuntimeError('Connection error: ' + errors.group())
                return f'{PLAYER} joined the game' in logs and quickplay.exists()

            wait_for(connected, args.timeout, [('server', server), ('client', client)],
                     f'Connection {attempt} did not enter the multiplayer world')
            joined = datetime.now().isoformat()
            message(f'{loader}: world joined; observing {args.seconds}s')
            until = time.monotonic() + args.seconds
            while time.monotonic() < until:
                if server.poll() is not None or client.poll() is not None:
                    raise RuntimeError('A game process exited during the online observation')
                logs = read_text(server_log)[baseline:]
                errors = FATAL.search(logs + '\n' + read_text(client_log))
                if errors or f'{PLAYER} lost connection:' in logs:
                    raise RuntimeError('Disconnected or packet error during online observation: ' +
                                       (errors.group() if errors else logs[-2000:]))
                time.sleep(1)
            report['attempts'].append({'attempt': attempt, 'status': 'PASS', 'joined_at': joined,
                                       'online_seconds': args.seconds,
                                       'quickplay': json.loads(quickplay.read_text(encoding='utf-8'))})
            # Ask the real client window to close, letting Minecraft disconnect normally.
            close_client(client)
            client = None
            wait_for(lambda: f'{PLAYER} lost connection:' in read_text(server_log)[baseline:],
                     30, [('server', server)], 'Server did not observe the client disconnect')
            message(f'{loader}: connection {attempt} PASS')
        report['status'] = 'PASS'
    except (OSError, RuntimeError, TimeoutError, subprocess.SubprocessError, ValueError) as error:
        report['error'] = str(error)
        message(f'{loader}: FAIL: {error}')
    finally:
        stop(client)
        stop(server, 'stop')
        report['finished_at'] = datetime.now().isoformat()
        (directory / 'result.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
        message(f'{loader}: result {directory / "result.json"}')
    return report


def close_client(process):
    if process.poll() is not None:
        return
    if os.name == 'nt':
        import ctypes
        from ctypes import wintypes
        user32 = ctypes.windll.user32
        callback_type = ctypes.WINFUNCTYPE(wintypes.BOOL, wintypes.HWND, wintypes.LPARAM)

        @callback_type
        def close_window(window, _):
            owner = wintypes.DWORD()
            user32.GetWindowThreadProcessId(window, ctypes.byref(owner))
            if owner.value == process.pid and user32.IsWindowVisible(window):
                user32.PostMessageW(window, 0x0010, 0, 0)
            return True

        user32.EnumWindows(close_window, 0)
        try:
            process.wait(timeout=30)
            return
        except subprocess.TimeoutExpired:
            pass
    stop(process)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--loader', choices=['neoforge', 'fabric', 'all'], default='all')
    parser.add_argument('--seconds', type=int, default=30)
    parser.add_argument('--attempts', type=int, default=2)
    parser.add_argument('--port', type=int, default=25575)
    parser.add_argument('--timeout', type=int, default=240)
    parser.add_argument('--prepare-timeout', type=int, default=900)
    parser.add_argument('--offline', action='store_true', help='Use cached Gradle dependencies only')
    parser.add_argument('--accept-eula', action='store_true', help='Accept Minecraft EULA for isolated test servers')
    args = parser.parse_args()
    if args.seconds < 1 or args.attempts < 1 or not 1 <= args.port <= 65535:
        parser.error('seconds and attempts must be positive; port must be 1..65535')
    loaders = ['neoforge', 'fabric'] if args.loader == 'all' else [args.loader]
    results = [run_loader(loader, args) for loader in loaders]
    return 0 if all(result['status'] == 'PASS' for result in results) else 1


if __name__ == '__main__':
    sys.exit(main())
