"""构建确切 JAR、隔离运行 NeoForge 并执行核心探针。"""
import argparse
from datetime import datetime
import json
from pathlib import Path
import sys
import time
import traceback
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from toolslib.cli import configure_console
from toolslib.repo import repo_root
from probelib.autojs import ProbeError
from probelib.cases import CASES, Case
from probelib.report import render
from probelib.runtime import Runtime, read_artifact, read_config, write_json


def main():
    configure_console()
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config', type=Path, help='本地依赖配置，默认 .local/probes.json')
    parser.add_argument('--only', action='append', choices=CASES, help='精确选例，可重复')
    parser.add_argument('--validate', action='store_true', help='只校验输入，不构建或启动')
    parser.add_argument('--run-dir', type=Path, help='本次全新输出目录')
    parser.add_argument('--artifact', type=Path, help='使用本次已构建的 artifact manifest，跳过宿主构建')
    arguments = parser.parse_args()
    root = repo_root()
    selected = list(dict.fromkeys(arguments.only or CASES))
    directory = (arguments.run_dir or root / 'neoforge/build/probe-runs' / datetime.now().strftime('%Y%m%d-%H%M%S-%f')).resolve()
    runtime = None
    record = dict(selected=selected, cases=[], phase='validate', cleanupErrors=[])
    exit_code = 0
    try:
        config = read_config(arguments.config or root / '.local/probes.json')
        if directory.exists():
            raise ProbeError(f'运行目录须尚不存在: {directory}')
        if arguments.artifact:
            read_artifact(arguments.artifact)
        if arguments.validate:
            print(f'输入通过: {", ".join(selected)}')
            return 0
        directory.mkdir(parents=True)
        runtime = Runtime(root, directory, config)
        record['phase'] = 'prepare'
        runtime.prepare(arguments.artifact)
        record['phase'] = 'launch'
        runtime.start('server')
        record['phase'] = 'cases'
        for name in selected:
            surface, execute = CASES[name]
            case = dict(name=name, surface=surface, status='error', assertions=[], screenshots=[], duration=0)
            record['cases'].append(case)
            started = time.monotonic()
            try:
                probe = Case(runtime, case)
                execute(probe)
                if surface == 'gui' and not case['screenshots']:
                    raise ProbeError('GUI 用例缺少实际 PNG')
                case['status'] = 'pass'
            except AssertionError as error:
                case.update(status='fail', message=str(error))
                exit_code = max(exit_code, 1)
            except (ProbeError, OSError, ValueError, KeyError, TypeError, zipfile.BadZipFile) as error:
                case.update(status='error', message=str(error), traceback=traceback.format_exc())
                exit_code = 2
            finally:
                if case['status'] != 'pass':
                    probe.diagnostic_screenshot()
                if 'client' in runtime.controls:
                    try:
                        runtime.controls['client'].evaluate('Probe.releaseInput()')
                    except ProbeError as error:
                        case.setdefault('cleanupErrors', []).append(str(error))
                        exit_code = 2
                case['duration'] = round(time.monotonic() - started, 3)
                case['diagnostics'] = {side: control.diagnostics[:] for side, control in runtime.controls.items()}
                case['actionRecoveries'] = {side: control.recoveries[:] for side, control in runtime.controls.items()}
                write_json(directory / 'probe-run.json', record)
                print(f'{name}: {case["status"]}')
            if case['status'] == 'error':
                break
        for name in selected[len(record['cases']):]:
            record['cases'].append(dict(name=name, surface=CASES[name][0], status='error', message='前序工具错误，未执行',
                                        assertions=[], screenshots=[], duration=0))
    except (ProbeError, OSError, ValueError, KeyError, TypeError, zipfile.BadZipFile) as error:
        record.update(error=str(error), traceback=traceback.format_exc())
        print(f'[{record["phase"]}] {error}', file=sys.stderr)
        exit_code = 2
        if directory.exists():
            for name in selected[len(record['cases']):]:
                record['cases'].append(dict(name=name, surface=CASES[name][0], status='error', message=str(error),
                                            assertions=[], screenshots=[], duration=0))
    finally:
        if runtime:
            record['cleanupErrors'] = runtime.close()
            if record['cleanupErrors']:
                exit_code = 2
            record['exitCode'] = exit_code
            write_json(directory / 'probe-run.json', record)
            try:
                render(directory)
            except (OSError, ValueError, KeyError) as error:
                record['reportError'] = str(error)
                record['exitCode'] = exit_code = 2
                write_json(directory / 'probe-run.json', record)
            print(f'证据: {directory}')
    return exit_code


if __name__ == '__main__':
    raise SystemExit(main())
