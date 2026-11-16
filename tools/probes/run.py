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
from probelib.cases import CASES, CORE_CASES, Case
from probelib.report import render
from probelib.runtime import Runtime, read_artifact, read_config, read_recipe_card_artifact, write_json


def main():
    configure_console()
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config', type=Path, help='本地依赖配置，默认 .local/probes.json')
    selection = parser.add_mutually_exclusive_group()
    selection.add_argument('--only', action='append', choices=CASES, help='精确选例，可重复，保留输入顺序')
    selection.add_argument('--suite', choices=('core', 'all'), help='默认 core；all 包含全部注册专项')
    parser.add_argument('--recipe-card-artifact', type=Path,
                        help='配方卡片工具 artifact.json；仅 recipe-card-export 分支使用')
    parser.add_argument('--recipe-card-namespace', action='append',
                        help='配方卡片分支的命名空间，可重复，默认 unsuspiciousblock；使用 * 表示全部')
    parser.add_argument('--recipe-card-category', action='append',
                        help='配方卡片分支的 JEI 分类 ID，可重复')
    parser.add_argument('--recipe-card', action='append',
                        help='配方卡片分支的配方 ID，可重复')
    parser.add_argument('--recipe-card-scale', type=int, default=3,
                        help='配方卡片分支的整数倍率，默认 3')
    parser.add_argument('--recipe-card-variant', type=int, default=0,
                        help='配方卡片分支的候选序号，默认 0')
    parser.add_argument('--validate', action='store_true', help='只校验输入，不构建或启动')
    parser.add_argument('--run-dir', type=Path, help='本次全新输出目录')
    parser.add_argument('--artifact', type=Path, help='使用本次已构建的 artifact manifest，跳过宿主构建')
    parser.add_argument('--background', action='store_true', help='在独立 Windows desktop 运行客户端，不抢焦点或鼠标')
    arguments = parser.parse_args()
    root = repo_root()
    source = 'explicit' if arguments.only else arguments.suite or 'core'
    selected = list(dict.fromkeys(arguments.only)) if arguments.only else list(CASES if source == 'all' else CORE_CASES)
    recipe_card_selected = 'recipe-card-export' in selected
    recipe_card_arguments = (arguments.recipe_card_artifact is not None
                             or arguments.recipe_card_namespace is not None
                             or arguments.recipe_card_category is not None
                             or arguments.recipe_card is not None
                             or arguments.recipe_card_scale != 3
                             or arguments.recipe_card_variant != 0)
    if recipe_card_arguments and not recipe_card_selected:
        parser.error('配方卡片参数仅适用于 recipe-card-export 分支')
    if not 1 <= arguments.recipe_card_scale <= 6:
        parser.error('--recipe-card-scale 需要 1..6')
    if arguments.recipe_card_variant < 0:
        parser.error('--recipe-card-variant 需要非负整数')
    if any(value == '' for values in (arguments.recipe_card_namespace, arguments.recipe_card_category,
                                      arguments.recipe_card) if values for value in values):
        parser.error('配方卡片筛选值不能为空')
    recipe_cards = None
    if recipe_card_selected:
        recipe_cards = dict(namespaces=arguments.recipe_card_namespace or ['unsuspiciousblock'],
                            categories=arguments.recipe_card_category or [],
                            recipes=arguments.recipe_card or [], scale=arguments.recipe_card_scale,
                            variant=arguments.recipe_card_variant)
    directory = (arguments.run_dir or root / 'neoforge/build/probe-runs' / datetime.now().strftime('%Y%m%d-%H%M%S-%f')).resolve()
    runtime = None
    record = dict(selection=source, selected=selected, cases=[], phase='validate', cleanupErrors=[])
    exit_code = 0
    try:
        config = read_config(arguments.config or root / '.local/probes.json')
        if arguments.background:
            config['background'] = True
        if directory.exists():
            raise ProbeError(f'运行目录须尚不存在: {directory}')
        if arguments.artifact:
            read_artifact(arguments.artifact)
        host_artifact = arguments.artifact or root / 'build/probes/artifact.json'
        if arguments.recipe_card_artifact and host_artifact.exists():
            read_recipe_card_artifact(arguments.recipe_card_artifact.resolve(),
                                      read_artifact(host_artifact))
        if arguments.validate:
            if recipe_card_selected and arguments.recipe_card_artifact:
                print(f'配方卡片产物通过: {arguments.recipe_card_artifact}')
            print(f'输入通过: {", ".join(selected)}')
            return 0
        directory.mkdir(parents=True)
        runtime = Runtime(root, directory, config, recipe_cards)
        record['phase'] = 'prepare'
        runtime.prepare(arguments.artifact, arguments.recipe_card_artifact.resolve()
                        if arguments.recipe_card_artifact else None)
        record['phase'] = 'launch'
        runtime.start('server')
        record['phase'] = 'cases'
        for name in selected:
            surface, execute = CASES[name]
            case = dict(name=name, surface=surface, status='error', assertions=[], screenshots=[], duration=0)
            record['cases'].append(case)
            started = time.monotonic()
            probe = None
            try:
                probe = Case(runtime, case)
                execute(probe)
                if surface == 'gui' and not case['screenshots']:
                    raise ProbeError('GUI 用例缺少实际 PNG')
                case['status'] = 'pass'
            except AssertionError as error:
                case.update(status='fail', message=str(error))
                exit_code = max(exit_code, 1)
            except Exception as error:
                # 用例边界保留场景实现错误的完整证据，并终止当前实例。
                case.update(status='error', message=str(error), traceback=traceback.format_exc())
                exit_code = 2
            finally:
                if case['status'] != 'pass' and probe is not None:
                    probe.diagnostic_screenshot()
                if probe is not None and probe.cleanup():
                    case['primaryStatus'] = case['status']
                    case['primaryMessage'] = case.get('message')
                    case.update(status='error', message=case.get('message', '') + '\n清理失败: ' + '; '.join(case['cleanupErrors']))
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
