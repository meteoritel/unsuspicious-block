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
from probelib.cases import Case
from probelib.report import render
from probelib.runtime import Runtime, digest, gradle, read_artifact, read_config, read_recipe_card_artifact, write_json
from probelib.selection import CASES, SUITES, describe_cases, sessions


def case_record(name, **fields):
    return dict(name=name, surface=CASES[name].surface, status='error', assertions=[], screenshots=[], duration=0,
                **fields)


def run_case(name, runtime):
    spec = CASES[name]
    record = case_record(name, session=runtime.directory.name)
    started = time.monotonic()
    evidence = dict(name=name, assertions=[], screenshots=[])
    probe = Case(runtime, evidence, spec.required)
    try:
        spec.execute(probe)
        probe.verdict.complete()
        if spec.surface == 'gui' and not evidence['screenshots']:
            raise ProbeError('GUI 用例缺少实际 PNG')
        record['status'] = 'pass'
    except AssertionError as error:
        record.update(status='fail', message=str(error))
    except Exception as error:
        record.update(status='error', message=str(error), traceback=traceback.format_exc())
    finally:
        if record['status'] != 'pass':
            probe.diagnostic_screenshot()
        if probe.cleanup():
            record['primaryStatus'] = record['status']
            record['primaryMessage'] = record.get('message')
            record.update(status='error', message='清理失败: ' + '; '.join(evidence['cleanupErrors']))
        record['duration'] = round(time.monotonic() - started, 3)
        record['diagnostics'] = {side: control.diagnostics[:] for side, control in runtime.controls.items()}
        record['actionRecoveries'] = {side: control.recoveries[:] for side, control in runtime.controls.items()}
    record.update(assertions=evidence['assertions'], screenshots=evidence['screenshots'],
                  checks=list(probe.verdict.results.values()))
    return evidence | record


def execute_plan(root, directory, plan, config, arguments, record, recipe_cards):
    artifact = arguments.artifact
    if artifact is None:
        record['phase'] = 'build'
        gradle(root, ['build', 'writeProbeArtifact'], directory / 'build.log')
        artifact = root / 'build/probes/artifact.json'
    release_hash = digest(Path(read_artifact(artifact)['jar']))
    exit_code = 0
    for index, (needs, names) in enumerate(plan, start=1):
        session_dir = directory / f'{index:02d}'
        session_dir.mkdir()
        session = dict(name=session_dir.name, selected=names, **needs.describe())
        record['sessions'].append(session)
        runtime = Runtime(root, session_dir, config, needs, recipe_cards=recipe_cards if needs.recipe_cards else None)
        try:
            record['phase'] = 'prepare'
            runtime.prepare(artifact, arguments.recipe_card_artifact if needs.recipe_cards else None)
            if runtime.manifest['dependencies'][0]['sha256'] != release_hash:
                raise ProbeError('宿主发布物在会话之间发生变化')
            record['phase'] = 'launch'
            runtime.start('server')
            record['phase'] = 'cases'
            for name in names:
                result = run_case(name, runtime)
                record['cases'].append(result)
                write_json(directory / 'probe-run.json', record)
                print(f'{name}: {result["status"]}')
                exit_code = max(exit_code, {'pass': 0, 'fail': 1, 'error': 2}[result['status']])
                if exit_code == 2:
                    break
        finally:
            errors = runtime.close()
            record['cleanupErrors'].extend(f'{session_dir.name}: {message}' for message in errors)
            session['manifest'] = f'{session_dir.name}/manifest.json' if runtime.manifest else None
            if errors:
                exit_code = 2
        if exit_code == 2:
            break
    return exit_code


def main():
    configure_console()
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config', type=Path, help='本地依赖配置，默认 .local/probes.json')
    selection = parser.add_mutually_exclusive_group(required=True)
    selection.add_argument('--list', action='store_true', help='列出用例、套件和启动需求')
    selection.add_argument('--only', action='append', choices=CASES, help='精确选例，可重复，保留输入顺序')
    selection.add_argument('--suite', choices=SUITES, help='选择任务套件；all 包含全部注册专项')
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
    if arguments.list:
        print(json.dumps(describe_cases(), ensure_ascii=False, indent=2))
        return 0
    root = repo_root()
    source = 'explicit' if arguments.only else arguments.suite
    selected = list(dict.fromkeys(arguments.only)) if arguments.only else list(SUITES[source])
    plan = sessions(selected)
    recipe_card_selected = any(needs.recipe_cards for needs, _ in plan)
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
    record = dict(selection=source, selected=selected, sessions=[], cases=[], phase='validate', cleanupErrors=[])
    exit_code = 0
    owns_directory = False
    try:
        sides = tuple(dict.fromkeys(side for needs, _ in plan for side in needs.sides))
        config = read_config(arguments.config or root / '.local/probes.json', sides)
        if arguments.background:
            config['background'] = True
        if directory.exists():
            raise ProbeError(f'运行目录须尚不存在: {directory}')
        if arguments.artifact:
            read_artifact(arguments.artifact)
        host_artifact = arguments.artifact or root / 'build/probes/artifact.json'
        if arguments.recipe_card_artifact:
            arguments.recipe_card_artifact = arguments.recipe_card_artifact.resolve(strict=True)
            if host_artifact.exists():
                read_recipe_card_artifact(arguments.recipe_card_artifact, read_artifact(host_artifact))
        if arguments.validate:
            print(json.dumps(dict(selection=source, selected=selected, sessions=[
                dict(selected=names, **needs.describe()) for needs, names in plan
            ]), ensure_ascii=False, indent=2))
            return 0
        directory.mkdir(parents=True)
        owns_directory = True
        exit_code = execute_plan(root, directory, plan, config, arguments, record, recipe_cards)
    except (ProbeError, OSError, ValueError, KeyError, TypeError, zipfile.BadZipFile) as error:
        record.update(error=str(error), traceback=traceback.format_exc())
        print(f'[{record["phase"]}] {error}', file=sys.stderr)
        exit_code = 2
    finally:
        if owns_directory:
            if record['cleanupErrors']:
                exit_code = 2
            for name in selected[len(record['cases']):]:
                record['cases'].append(case_record(name, message=record.get('error', '前序工具错误，未执行'), executed=False))
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
