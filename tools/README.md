# 仓库工具

校验使用 Python 3.12+ 标准库与 Git，默认从工具位置定位项目，中文诊断使用 UTF-8。公共输入取 Git 受控文件与未忽略新文件，生成目录和本地任务材料保持在源码检查范围之外。

## 校验入口

| 用途 | 命令 |
|---|---|
| 固定顺序执行源码检查 | `python tools/check-all.py` |
| 局部执行，仍保持固定顺序 | `python tools/check-all.py --only docs --only i18n` |
| 平台与端隔离 | `python tools/check-isolation.py` |
| UI Kit 消费边界 | `python tools/check-ui-kit-boundaries.py` |
| 文档链接、图片和导航 | `python tools/check-docs.py` |
| 同时核对本地根规则的出站链接 | `python tools/check-docs.py --include-local-rules` |
| 中英语言文件配对、JSON、值与 key | `python tools/check-i18n.py` |
| JSON 与 Mixin/SPI/平台资源接线 | `python tools/check-wiring.py` |

每个入口支持 `--root <项目目录>`；具体参数查 `--help`。通过返回 `0`，发现违规返回 `1`，缺少必需输入或工具环境错误返回 `2`。诊断包含文件、可确定的行号、规则 ID 和问题。

文档检查解析文件目标和 Wiki 无扩展名页名，隔离代码围栏与行内代码，核对文档入口可达性。标题锚点由编辑核对，新增交叉引用使用文件链接与小节名称。写作语义按[文档维护](../docs/README.md)审校。

语言检查覆盖源码资源中的 `en_us.json` 与 `zh_cn.json`；格式合法、顶层对象、翻译字符串和 key 集合分别核对。接线检查读取源码配置，生成的 refmap 和元数据展开结果由构建处理；外部类型由编译依赖解析。

## 依赖与边界检查

Java 引用扫描会跳过注释、字符串、字符字面量与 Text Block，解析显式导入、静态导入、全限定引用以及源码可判定的同包和通配导入类型。Loader API 与平台实现类型留在平台源集，客户端类型由客户端所有者消费。

客户端所有者统一按模组根包下的 `client` 包及其子包识别（规则见 [boundaries.py](toolslib/boundaries.py)）。`core`、公共 `platform` 与公共 Loader 入口仅使用共享契约；原版 Minecraft、Loader 客户端 API、渲染类型与 UI Kit 仅由客户端所有者消费。`ModPayloads` 中的 S2C 条目仅声明类型与 Codec，客户端接收函数由 `ClientPayloadBindings` 持有。

隔离检查同时核对 `common` 下的 `core / client / platform` 归属、Loader 下的 `client / platform` 归属、根包保留入口以及 `package` 与源码目录路径的一致性。接线检查要求 Mixin 的 `package` 限定在专属注入包，且客户端 Mixin 登记在 `client` 数组；SPI、元数据与反射加载的运行时语义结合对应入口及实机验收核对。

UI Kit 检查覆盖公开包消费、客户端归属、已退出仓内的旧 kit 目录/包以及遗留原生控件。静态引用与可定位结构由脚本判定；环境守卫、反射、回调与游戏效果结合源码及实机验收核对。

## 构建与产物

`./gradlew.bat build` 执行同一源码门禁，并由 `checkUiKitPackaging` 核对本次双端发布物。源码门禁在构建图中去重；产物路径直接取 Fabric `remapJar` 与 NeoForge `jar` 的输出，后者包含 `jarJar` 任务生成的内嵌内容。

`check-ui-kit-packaging.py` 也可接收 `--fabric-jar` 和 `--neoforge-jar` 显式路径。它核对内嵌平台库、精确 dev 版本约束、宿主误展开的库类与旧 kit。必需输入缺失返回 `2`，归档或元数据违规返回 `1`。

Gradle 默认使用 `python`，可通过 `-Prepository_python=<解释器路径>` 指定。独立 UI 库由用户级 `meteos_ui_kit_maven_dir` 提供；PR / 分支 CI 直接运行源码检查。现有 release 构建仍需要可访问的 UI 库 Maven 来源。

## 生成与诊断

| 入口 | 用途 |
|---|---|
| `scripts/generate_journal_icon_atlas.py` | 生成笔记图标图集 |
| `scripts/drawer/`、`tools/draw_book_side_tabs.py` | 生成界面纹理和图标，按各脚本参数选择输出 |
| `scripts/nbt_tools/nbt_converter.py` | NBT 数据转换 |
| `scripts/loot_simulation_profile.py`、`scripts/read_loot_probability_save.py` | 读取模拟与概率保存数据 |
| `scripts/check-ui-kit-boundaries.ps1` | 兼容入口，转交平台/端隔离与 UI Kit Python 检查 |

生成和诊断工具的参数、依赖与写入位置查脚本说明。运行校验只读取输入，生成操作按明确的输出路径执行。

## 相关文档

- [开发流程](../docs/guides/development.md)
- [UI Kit 宿主接入](../docs/dev/internals/ui-kit-api.md)
