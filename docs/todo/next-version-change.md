## [未发布 / Unreleased]

### 修复 / Fixed
- 考古笔记搜索框：点击放大镜展开后可直接键入（此前必须再点一次输入框）；C 键带物品打开、切页、收起与调整窗口后的焦点与草稿行为明确化，打开模态期间不再误收字符。
- 猫之恩惠 HUD 绘制后成对恢复着色器颜色与混合状态，不再污染同一渲染通道内的后续 HUD。
- 解析仪透视描边改为不写深度，避免遮挡其后的世界绘制。
- 战利品表管理页：补筛选/搜索无匹配的空态提示、资源包来源显示具体资源包 id、导入失败按原因分类反馈并写日志。
- 换服务器后管理页不再显示上一连接的条目、翻译或编辑权限；窗口 resize 不再丢失备注、保留上限与管理页搜索草稿。
- 删除 21 个定义了但从未使用的本地化键（中英同步）。

### 变化 / Changed
- 灯箱控制栏默认改用暗底样式（`UiControlStyle.DARK`），文本默认色按结构底色的对比度反推；需要浅底的宿主请显式 `setStyle(PARCHMENT)`（或先 `setTextColor` 固定）。
- 纸面/面板文本色统一到达标对比度（≥4.5:1），改从语义色表取值；进度读数从进度条填充色上移出。
- kit 几何入口的负尺寸由抛异常改为钳制；`UiDocument` 的 Frame 子文档开始参与悬停与命中；控件只在内容确实越界时设置裁剪。
- 日志本地存储改为脏标记 + 每 tick 合并落盘，断开连接、切世界与离开世界前强制落盘。
- 管理页的协议组包与文件导入校验移到独立动作/校验类，列表几何与截断收敛为一份实现。

### 新增 / Added
- `text/TooltipBuilder`（自 `client/tooltip/TooltipBuilder` 迁移）与 `text/ClientTooltipBridge`：两端可达类不再直接引用客户端包。
- `TextScroll.trimToWidth(Font, String, int)`、`TextScroll` 的已测宽度重载、`UiLightbox.Labels.zoomReadout(int)`。

### 性能 / Performance
- 目录投影按 `catalogRevision` 缓存，日志详情不再每帧重建整份目录投影。
- 搜索输入加防抖；关系索引、网格与子表闭包按目录/状态版本缓存。
- 解析仪 HUD 的派生文本与测量宽度随快照缓存；淘洗介质索引与护盾球体几何不再逐帧重建；面板文本宽度按（文本 + 字体实例 + 语言）缓存。

### 兼容 / Compatibility
- `UiControl.setBounds` / `UiDocument.setViewport` 对负尺寸不再抛 `IllegalArgumentException`（改为钳制）。
- 依赖 `TooltipBuilder` 旧包路径的下游代码需改为 `com.meteorite.unsuspiciousblock.text.TooltipBuilder`（模组内已全部迁移）。
