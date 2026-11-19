"""原始记录是唯一判定源，展示报告按已持久化状态派生。"""
import html
import json
from pathlib import Path
from urllib.parse import quote
import xml.etree.ElementTree as ET


def render(directory: Path):
    record = json.loads((directory / 'probe-run.json').read_text(encoding='utf-8'))
    cases = list(record['cases'])
    cleanup = record.get('cleanupErrors', []) + [error for case in cases for error in case.get('cleanupErrors', [])]
    if cleanup:
        cases.append(dict(name='runner-cleanup', surface='runner', status='error', duration=0,
                          message='; '.join(cleanup), assertions=[], screenshots=[]))
    suite = ET.Element('testsuite', name='neoforge-probes', tests=str(len(cases)),
                       failures=str(sum(case['status'] == 'fail' for case in cases)),
                       errors=str(sum(case['status'] == 'error' for case in cases)))
    sections = []
    for case in cases:
        node = ET.SubElement(suite, 'testcase', name=case['name'], classname=case['surface'], time=str(case['duration']))
        if case['status'] != 'pass':
            ET.SubElement(node, 'failure' if case['status'] == 'fail' else 'error', message=case['message']).text = case['message']
        links = ''.join(f'<li><a href="{quote((Path(case.get("session", "")) / shot["relative"]).as_posix())}">'
                        f'{html.escape(shot["relative"])}</a></li>' for shot in case['screenshots'])
        sections.append(f'<section><h2>{html.escape(case["name"])}: {case["status"]}</h2>'
                        f'<p>阶段: {html.escape(case.get("phase", record["phase"]))}</p>'
                        f'<p>{html.escape(case.get("message", ""))}</p><ul>{links}</ul>'
                        f'<pre>{html.escape(json.dumps(case.get("checks", []) + case["assertions"], ensure_ascii=False, indent=2))}</pre></section>')
    ET.indent(suite)
    ET.ElementTree(suite).write(directory / 'junit.xml', encoding='utf-8', xml_declaration=True)
    files = [path.name for path in sorted(directory.glob('*.log'))]
    for session in record.get('sessions', []):
        if session.get('manifest'):
            files.append(session['manifest'])
        files.extend(path.relative_to(directory).as_posix()
                     for path in sorted((directory / session['name']).glob('*.log')))
    (directory / 'index.html').write_text('<!doctype html><meta charset="utf-8"><title>NeoForge probes</title>'
        '<style>body{font:16px system-ui;max-width:70em;margin:2em auto}pre{white-space:pre-wrap}section{border-top:1px solid #ccc}</style>'
        '<h1>NeoForge probes</h1><p><a href="probe-run.json">原始记录</a></p>'
        + '<p>退出码: ' + str(record['exitCode']) + '</p><ul>'
        + ''.join(f'<li><a href="{quote(path)}">{html.escape(path)}</a></li>' for path in files)
        + '</ul>' + ''.join(sections), encoding='utf-8')
