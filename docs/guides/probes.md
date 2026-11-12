# 探针使用与诊断指南

本指南说明项目开发阶段如何授权使用、运行与编写 NeoForge 运行时探针（AutoJS + Rhino + kit 帧末截图）。核心回归套件内部架构、用例清单与模块选跑矩阵见[核心探针](../../tools/probes/README.md)。

## 1. 授权与两类探针边界

Agent 调用任何探针前，须遵守仓库根目录 `AGENTS.md` 中的「探针调用授权」：

- 未获用户明确授权时，禁止主动执行探针脚本或启动带探针的游戏进程。
- 已获授权时，仅在用户允许范围内执行，并在完成后准确报告运行范围、截图路径与断言结果。

| 探针形态 | 存放位置 | 用途与生命周期 |
|---|---|---|
| **核心回归探针** | [`tools/probes/`](../../tools/probes/README.md) | 覆盖主链路记账、同步、持久化与关键视口；随代码长期演进并纳入 Git |
| **临时诊断 / 截图探针** | 会话 `scratch/` 目录或 `.local/`（不入库） | 定向复现 UI 缺陷、切换语言截多图、排查崩溃；任务验收后随临时目录清理，勿污染 `tools/probes/` |

## 2. 环境准备与快速校验

首次在本机运行探针前完成以下步骤：

1. **构建宿主 JAR 与 Manifest**：用户级 Gradle 属性 `meteos_ui_kit_maven_dir` 指向本地 UI Kit Maven 仓库，运行 `./gradlew.bat build writeProbeArtifact --no-daemon` 生成 `build/probes/artifact.json`。
2. **本地探针配置**：复制 [`config.example.json`](../../tools/probes/config.example.json) 为仓库根目录 `.local/probes.json`（已纳入 Git 忽略），填入本机 `autojsJar` 与 `rhinoJar` 绝对路径；接受 Minecraft EULA 后显式设置 `"acceptEula": true`。
3. **检查端口与配置**：默认使用 `serverMcpPort`（25585）、`clientMcpPort`（25586）和 `gamePort`（25575），三个端口需互异且空闲。

快速干跑验证配置与产物配对（不启动游戏进程）：

```powershell
python tools/probes/run.py --validate --only runtime-artifact
```

## 3. 运行核心回归探针

`tools/probes/run.py` 串联「配置校验 → 运行目录准备 → 专用服务端 → 客户端 → `probe-run.json` 断言与 PNG 证据输出」。已有最新构建时传 `--artifact build/probes/artifact.json` 可跳过重复 Gradle 编译：

```powershell
# 运行指定核心用例
python tools/probes/run.py --artifact build/probes/artifact.json --only scan-take-journal --only journal-viewport

# 运行完整核心回归套件（将使用默认隔离运行目录并重新构建）
python tools/probes/run.py
```

判定与输出约定：

- 退出码：`0` 为选定用例全通过，`1` 为业务断言失败，`2` 为配置、MCP 协议、截图采集或环境错误。
- 判定源：每次运行在全新运行目录下生成 `probe-run.json`（唯一判定源）、`manifest.json`、服务端/客户端日志及派生 HTML / JUnit 报告。
- 定向选跑映射表与核心用例职责见[核心探针](../../tools/probes/README.md)。

## 4. 编写临时诊断与 GUI 截图探针

当需要多语言截图、滚动手感排查或复现特定战利品表崩溃时，在会话 `scratch/` 目录编写独立 Python 诊断脚本，直接复用 [`probelib`](../../tools/probes/probelib/) 提供的 [`Runtime`](../../tools/probes/probelib/runtime.py) 与 [`Case`](../../tools/probes/probelib/cases.py)。

### 4.1 最小脚本骨架

```python
import sys
from pathlib import Path

ROOT = Path(r'<仓库根目录>')
sys.path.insert(0, str(ROOT / 'tools/probes'))

from probelib.cases import Case, wait_for
from probelib.runtime import Runtime, read_config

config = read_config(ROOT / '.local/probes.json')
run_dir = Path(r'<全新临时运行目录>')
run_dir.mkdir(parents=True, exist_ok=False)

runtime = Runtime(ROOT, run_dir, config)
record = dict(name='inspect', phase='prepare', actions=[], assertions=[], screenshots=[])

try:
    runtime.prepare(ROOT / 'build/probes/artifact.json')
    runtime.start('server')
    case = Case(runtime, record)

    # 如需切换语言，应在启动客户端前写入 client/options.txt（见 4.2）
    options_file = runtime.directory / 'client/options.txt'
    options_file.parent.mkdir(parents=True, exist_ok=True)
    options_file.write_text('lang:zh_cn\n', encoding='utf-8')

    # 通过 case.client 首次启动并等待双端玩家就绪
    client = case.client
    case.server.command('execute as UsbProbe run usb give journal')
    ...
finally:
    runtime.close()
```

### 4.2 多语言与资源包切换

- 在调用 `runtime.start('client')`（或首次访问 `case.client`）**之前**写入 `<run_dir>/client/options.txt` 的 `lang:zh_cn` 或 `lang:en_us`。
- 在游戏已启动后调用 `mc.reloadResourcePacks().join()` 会阻塞主线程超过 AutoJS `3000ms` 同步求值上限，导致 `eval_js` 超时断开；多语种截屏应分两次独立 `Runtime`（各自全新目录）预先写入 `options.txt`。

### 4.3 GUI 操作、悬停与截图时序

1. **打开笔记与点击控件**：使用 `Probe.openJournal()` 或执行 `/execute as UsbProbe run ...` 后 `case.stable_layout()`，再用 `case.click('<layout-key>')` 点击目标；每次交互前后重新读取 `Probe.layout()` 获取最新坐标与可见裁剪区。
2. **模拟滚轮与按键**：可通过 `client.evaluate(...)` 直接调用当前 `Screen.mouseScrolled(x, y, 0, delta)` 或 `Screen.keyPressed(keyCode, scanCode, modifiers)`；发送滚轮后若界面启用平滑滚动插值，使用 `wait_for(...)` 或短轮询等待几帧再截图，确保看到稳定位置或滚动中帧。
3. **悬停 Tooltip 截图**：使用 `case.hover('<layout-key>')` 移动真实 GLFW 指针并沿回调触发 `MouseHandler`，再调用 `case.screenshot('<tag>')`；`Case.screenshot` 会自动等待 Toast 消失、布局连续两帧稳定并验证输出 PNG 尺寸。

### 4.4 Rhino / AutoJS 短求值注意事项

| 场景 | 正确写法 | 说明 |
|---|---|---|
| **求值耗时限制** | 保持单次 `eval_js` 为纯同步短操作 | AutoJS `eval_js` 有约 3 秒同步超时；耗时过程用 Python `wait_for(...)` 分步轮询 |
| **需要玩家源的指令** | `case.server.command('execute as UsbProbe run ...')` | `server.command(...)` 以控制台身份执行，无实体执行者，依赖玩家上下文的指令必须包一层 `execute as` |
| **Java 反射与类型判断** | 直接使用 `Probe.type('...')` 与 `instanceof` | Rhino 代理对象调用 JS `.getClass()` 易触发歧义；优先通过 [`shared.js`](../../tools/probes/scripts/shared.js) 与 [`client.js`](../../tools/probes/scripts/client.js) 封装的助手访问类 |
| **Windows 运行目录锁定** | 每次测试创建全新目录，避免覆盖旧 `run_dir` | Windows 上日志句柄未完全释放前复用旧目录容易触发文件占用或残留 `world/` 状态干扰 |

## 5. 常见故障排查

| 现象 | 排查重点 |
|---|---|
| `Port ... already in use` | 检查是否有上一次异常退出的 `java.exe` 残留占用 `25575 / 25585 / 25586`，清理后再启动 |
| `宿主发布 JAR 未内嵌 kit` 或版本不一致 | 重新执行 `./gradlew.bat build writeProbeArtifact --no-daemon` 更新 `build/probes/artifact.json` |
| `eval_js` 超时断开 | 核对脚本是否在单次 `eval_js` 内执行了 `.join()`、重型资源重载或死循环 |
| 客户端启动即退出 | 检查运行目录下的客户端进程日志、`acceptEula` 配置，以及桌面/OpenGL 会话是否可用 |

## 6. 相关文档

- [核心探针（套件结构与用例矩阵）](../../tools/probes/README.md)
- [开发流程](development.md)
- [仓库工具](../../tools/README.md)
- [UI Kit 宿主接入](../dev/internals/ui-kit-api.md)
