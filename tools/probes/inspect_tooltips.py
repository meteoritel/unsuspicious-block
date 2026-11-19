"""纯检查物品 Tooltip 的可复用 NeoForge 探针模板：校验双语文案、配色、行宽并采集悬停截图。"""
import argparse
from datetime import datetime
import json
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from toolslib.cli import configure_console
from toolslib.repo import repo_root
from probelib.autojs import ProbeError
from probelib.cases import Case, png_dimensions, wait_for
from probelib.runtime import LaunchNeeds, Runtime, WITH_CLIENT, read_artifact, read_config, write_json

DEFAULT_MAX_LINE_PX = 240
CAT_STATES = [
    ('acquainted', 0, 0),
    ('trusted', 45, 0),
    ('best_friend', 100, 9),
]


def capture_container_screenshot(case: Case, png_path: Path):
    """在原版容器界面采帧末截图，并校验 PNG 原始尺寸。"""
    png_path.parent.mkdir(parents=True, exist_ok=True)
    case.action('client', 'Probe.dismissNotifications()')
    case.client.evaluate(f'Probe.capture({json.dumps(str(png_path))})')
    result = wait_for(lambda: case.client.evaluate('Probe.captureResult()'), lambda value: value is not None)
    dims = png_dimensions(png_path)
    if dims != [result['width'], result['height']]:
        raise AssertionError(f'PNG 尺寸不一致: expected={[result["width"], result["height"]]}, actual={dims}')
    return result


def check_sample_issues(sample_id: str, shift: bool, cat_tag: str | None, data: dict, max_line_px: int) -> list[str]:
    """检查单条 Tooltip 的未翻译键与超宽风险。"""
    issues = []
    for line in data['lines']:
        text = line['text']
        if ('unsuspiciousblock.' in text or 'key.unsuspiciousblock.' in text) and not text.startswith('['):
            issues.append(f'疑似未翻译键: {text}')
        if line['widthPx'] > max_line_px:
            issues.append(f'行宽超限 ({line["widthPx"]}px > {max_line_px}px): {text}')
    label = sample_id + (f'_{cat_tag}' if cat_tag else '') + ('_shift' if shift else '')
    return [f'[{label}] {msg}' for msg in issues]


def run_locale(root: Path, config: dict, artifact: Path, base_dir: Path, lang: str,
               items_filter: set[str] | None, shift_modes: list[bool],
               take_screenshots: bool, max_line_px: int) -> dict:
    """在独立全新目录运行单一语种的 Tooltip 全量检查。"""
    run_dir = (base_dir / lang).resolve()
    run_dir.mkdir(parents=True)
    runtime = Runtime(root, run_dir, config, LaunchNeeds(WITH_CLIENT))
    record = dict(name=f'tooltip-{lang}', phase='prepare', actions=[], assertions=[], screenshots=[])
    results = []
    warnings = []
    try:
        runtime.prepare(artifact)
        options_file = runtime.directory / 'client/options.txt'
        options_text = options_file.read_text(encoding='utf-8').replace('lang:en_us', f'lang:{lang}')
        options_file.write_text(options_text, encoding='utf-8')

        runtime.start('server')
        script_path = root / 'tools/probes/scripts/tooltip_inspect.js'
        script = script_path.read_text(encoding='utf-8')
        runtime.controls['server'].install(script)

        case = Case(runtime, record)
        case.client
        runtime.controls['client'].install(script)

        chest_info = case.server.evaluate('Probe.prepareTooltipChest()')
        slots = chest_info['slots']
        wait_for(lambda: case.client.evaluate(f'Probe.containerReady({len(slots)})'), bool)

        for idx, sample_id in enumerate(slots):
            if items_filter and sample_id not in items_filter and not any(sample_id.startswith(f) for f in items_filter):
                continue

            cat_variants = CAT_STATES if sample_id == 'hand_of_cat_self' else [(None, 0, 0)]
            for cat_tag, favor, lives in cat_variants:
                if cat_tag is not None:
                    case.client.evaluate(f'Probe.setCatClientState({favor}, {lives}, true)')
                else:
                    case.client.evaluate('Probe.setCatClientState(0, 0, false)')

                for shift in shift_modes:
                    case.client.evaluate(f'Probe.setTooltipShift({"true" if shift else "false"})')
                    tooltip_data = case.client.evaluate(f'Probe.inspectSlotTooltip({idx}, false)')
                    issues = check_sample_issues(sample_id, shift, cat_tag, tooltip_data, max_line_px)
                    warnings.extend(issues)

                    screenshot_rel = None
                    if take_screenshots:
                        suffix = (f'_{cat_tag}' if cat_tag else '') + ('_shift' if shift else '')
                        png_path = base_dir / 'screenshots' / lang / f'{sample_id}{suffix}.png'
                        case.client.evaluate(f'Probe.hoverContainerSlot({idx})')
                        wait_for(lambda: case.client.evaluate(f'Probe.isContainerSlotHovered({idx})'), bool, timeout=10)
                        capture_container_screenshot(case, png_path)
                        screenshot_rel = f'screenshots/{lang}/{png_path.name}'

                    results.append(dict(
                        id=sample_id,
                        lang=lang,
                        catState=cat_tag,
                        shift=shift,
                        hasTooltipImage=tooltip_data['hasTooltipImage'],
                        lines=tooltip_data['lines'],
                        issues=issues,
                        screenshot=screenshot_rel,
                    ))
        return dict(lang=lang, count=len(results), warnings=warnings, samples=results)
    finally:
        cleanup_errors = runtime.close()
        if cleanup_errors:
            warnings.extend([f'[{lang} cleanup] {err}' for err in cleanup_errors])


def render_markdown(report: dict) -> str:
    """生成便于人工审阅的 Markdown Tooltip 巡检报告。"""
    lines = [
        '# 物品 Tooltip 巡检报告',
        '',
        f'- 产物：`{report["artifact"]}`',
        f'- 最大行宽阈值：`{report["maxLinePx"]} px`',
        f'- 总样本数：`{sum(len(loc["samples"]) for loc in report["locales"])}`',
        f'- 告警数：`{len(report["warnings"])}`',
        '',
    ]
    if report['warnings']:
        lines.append('## 告警清单')
        lines.append('')
        for w in report['warnings']:
            lines.append(f'- {w}')
        lines.append('')

    for loc in report['locales']:
        lines.append(f'## 语言：{loc["lang"]}')
        lines.append('')
        for sample in loc['samples']:
            tag = sample['id']
            if sample['catState']:
                tag += f' ({sample["catState"]})'
            if sample['shift']:
                tag += ' [Shift]'
            lines.append(f'### `{tag}`')
            if sample['hasTooltipImage']:
                lines.append('- 富组件：`TooltipComponent` 图像预览存在')
            if sample['screenshot']:
                lines.append(f'- 截图：`{sample["screenshot"]}`')
            lines.append('')
            lines.append('| # | 宽度(px) | 文本 | 颜色片段 |')
            lines.append('|---:|---:|---|---|')
            for line in sample['lines']:
                spans = ', '.join(
                    f'`{s["hex"] or "default"}{"*" if s["italic"] else ""}`:{s["text"]}'
                    for s in line['spans']
                )
                lines.append(f'| {line["index"]} | {line["widthPx"]} | {line["text"]} | {spans} |')
            lines.append('')
    return '\n'.join(lines)


def main() -> int:
    configure_console()
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config', type=Path, help='本地依赖配置，默认 .local/probes.json')
    parser.add_argument('--artifact', type=Path, help='宿主 artifact.json，默认 build/probes/artifact.json')
    parser.add_argument('--lang', choices=('zh_cn', 'en_us', 'both'), default='both', help='检查语种')
    parser.add_argument('--shift', choices=('up', 'down', 'both'), default='both', help='Shift 展开模式')
    parser.add_argument('--items', help='逗号分隔的样本 ID 或前缀过滤')
    parser.add_argument('--screenshots', dest='screenshots', action='store_true', default=True, help='采集容器悬停 PNG')
    parser.add_argument('--no-screenshots', dest='screenshots', action='store_false', help='只检查文本/色值/宽度，不截 PNG')
    parser.add_argument('--max-line-px', type=int, default=DEFAULT_MAX_LINE_PX, help='单行最大像素宽度告警阈值')
    parser.add_argument('--run-dir', type=Path, help='本次全新输出目录')
    parser.add_argument('--background', action='store_true', help='在独立 Windows desktop 运行客户端')
    args = parser.parse_args()

    root = repo_root()
    config = read_config(args.config or root / '.local/probes.json', WITH_CLIENT)
    if args.background:
        config['background'] = True
    artifact_path = (args.artifact or root / 'build/probes/artifact.json').resolve()
    read_artifact(artifact_path)

    base_dir = (args.run_dir or root / 'neoforge/build/probe-runs' / ('tooltip-' + datetime.now().strftime('%Y%m%d-%H%M%S-%f'))).resolve()
    if base_dir.exists():
        raise ProbeError(f'运行目录须尚不存在: {base_dir}')
    base_dir.mkdir(parents=True)

    langs = ['zh_cn', 'en_us'] if args.lang == 'both' else [args.lang]
    shift_modes = [False, True] if args.shift == 'both' else [args.shift == 'down']
    items_filter = {s.strip() for s in args.items.split(',') if s.strip()} if args.items else None

    locales = []
    all_warnings = []
    for lang in langs:
        loc_result = run_locale(root, config, artifact_path, base_dir, lang, items_filter,
                                shift_modes, args.screenshots, args.max_line_px)
        locales.append(loc_result)
        all_warnings.extend(loc_result['warnings'])

    report = dict(
        artifact=str(artifact_path),
        maxLinePx=args.max_line_px,
        warnings=all_warnings,
        locales=locales,
    )
    write_json(base_dir / 'tooltip-report.json', report)
    (base_dir / 'tooltip-report.md').write_text(render_markdown(report), encoding='utf-8')
    print(f'Tooltip 巡检完成：{base_dir}（告警 {len(all_warnings)} 条）')
    return 1 if all_warnings else 0


if __name__ == '__main__':
    raise SystemExit(main())
