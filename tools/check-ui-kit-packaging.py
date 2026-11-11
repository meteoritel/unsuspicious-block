"""检查本次 Fabric / NeoForge 发布 JAR 的 UI 库内嵌与元数据。"""
import argparse
from io import BytesIO
from pathlib import Path
import sys
import tomllib
from zipfile import BadZipFile, ZipFile

from toolslib.checks import Finding, execute
from toolslib.json_input import loads
from toolslib.repo import properties, repo_root

GROUP = 'com.meteorite.meteosuikit'
LIBRARY_ID = 'meteos_ui_kit'
LEGACY = 'com/meteorite/unsuspiciousblock/client/ui/kit/'
LIBRARY_CLASSES = 'com/meteorite/meteosuikit/'


def read_mod(jar: ZipFile, module: str) -> dict:
    if module == 'fabric':
        result = loads(jar.read('fabric.mod.json').decode('utf-8'))
    else:
        metadata = tomllib.loads(jar.read('META-INF/neoforge.mods.toml').decode('utf-8'))
        result = metadata['mods'][0]
    if not isinstance(result, dict):
        raise ValueError('模组元数据须为对象')
    return result


def check(root: Path, archives: dict[str, Path]) -> list[Finding]:
    props = properties(root)
    for key in ('meteos_ui_kit_version', 'version', 'mod_id', 'minecraft_version'):
        if not props.get(key):
            raise ValueError(f'gradle.properties 缺少必需属性：{key}')
    version = props['meteos_ui_kit_version']
    findings = []
    for module, path in archives.items():
        if not path.is_file():
            raise ValueError(f'发布 JAR 不存在：{path}')
        location = path.as_posix()

        def report(rule: str, detail: str):
            findings.append(Finding(location, rule, detail))

        try:
            with ZipFile(path) as jar:
                names = set(jar.namelist())
                for name in names:
                    if name.startswith((LEGACY, LIBRARY_CLASSES)) and name.endswith('.class'):
                        report('packaging.host-class', f'宿主包含旧 kit 或展开的库类：{name}')
                    if 'meteos-ui-kit-common-' in name and name.endswith('.jar'):
                        report('packaging.common', f'内嵌了 common 编译库：{name}')
                host = read_mod(jar, module)
                if host.get('id' if module == 'fabric' else 'modId') != props['mod_id']:
                    report('packaging.host-id', '发布物的模组 ID 与 gradle.properties 不一致')
                if host.get('version') != props['version']:
                    report('packaging.host-version', '发布物版本与 gradle.properties 不一致')
                if module == 'fabric':
                    required = host.get('depends', {}).get(LIBRARY_ID, '')
                    expected_constraint = version
                    entries = [entry['file'] for entry in host.get('jars', [])]
                    nested = [name for name in names if name.endswith('.jar') and 'meteos-ui-kit-' in name]
                else:
                    metadata = tomllib.loads(jar.read('META-INF/neoforge.mods.toml').decode('utf-8'))
                    dependencies = metadata.get('dependencies', {}).get(props['mod_id'], [])
                    declared = [entry for entry in dependencies if entry.get('modId') == LIBRARY_ID]
                    if len(declared) != 1:
                        raise ValueError('NeoForge 须声明唯一 UI 库依赖')
                    required = declared[0]['versionRange']
                    expected_constraint = f'[{version}]'
                    entries = loads(jar.read('META-INF/jarjar/metadata.json').decode('utf-8'))['jars']
                    library_entries = [entry for entry in entries if entry['identifier']['group'] == GROUP]
                    expected_artifact = f'meteos-ui-kit-neoforge-{props["minecraft_version"]}'
                    if len(library_entries) != 1:
                        raise ValueError('Jar-in-Jar 须声明唯一 UI 平台库')
                    entry = library_entries[0]
                    if entry['identifier']['artifact'] != expected_artifact:
                        report('packaging.coordinate', f'UI 平台坐标不匹配：{entry["identifier"]}')
                    if entry['version']['artifactVersion'] != version or entry['version']['range'] != required:
                        report('packaging.range', 'Jar-in-Jar 版本或范围与宿主依赖不一致')
                    entries = [entry['path'] for entry in entries]
                    nested = [name for name in names if name.endswith('.jar') and 'meteos-ui-kit-' in name]
                if required != expected_constraint:
                    report('packaging.range', f'宿主须精确匹配已验证库版本：期望 {expected_constraint}；实际 {required}')
                expected_name = f'meteos-ui-kit-{module}-{props["minecraft_version"]}-{version}.jar'
                if len(nested) != 1 or Path(nested[0]).name != expected_name:
                    report('packaging.nested', f'应仅内嵌当前平台库：{expected_name}；实际：{nested}')
                    continue
                nested_name = nested[0]
                if nested_name not in entries:
                    report('packaging.declaration', f'加载器未声明内嵌库：{nested_name}')
                with ZipFile(BytesIO(jar.read(nested_name))) as library:
                    library_mod = read_mod(library, module)
                    mod_id = library_mod.get('id' if module == 'fabric' else 'modId')
                    if mod_id != LIBRARY_ID or library_mod.get('version') != version:
                        report('packaging.library-metadata', f'{nested_name} 的模组 ID 或版本不匹配')
                    for name in library.namelist():
                        if name.startswith(LEGACY) and name.endswith('.class'):
                            report('packaging.legacy', f'{nested_name}!/{name}')
        except (BadZipFile, KeyError, ValueError, TypeError, AttributeError, IndexError) as error:
            report('packaging.metadata', f'归档或元数据无效：{error}')
    return findings


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', help='项目根目录，默认由脚本位置定位')
    parser.add_argument('--fabric-jar', required=True, type=Path, help='本次 Fabric remapJar 输出')
    parser.add_argument('--neoforge-jar', required=True, type=Path, help='本次 NeoForge 最终 jar 输出，包含 Jar-in-Jar 内容')
    args = parser.parse_args()
    sys.exit(execute(lambda: check(repo_root(args.root), {'fabric': args.fabric_jar.resolve(), 'neoforge': args.neoforge_jar.resolve()})))
