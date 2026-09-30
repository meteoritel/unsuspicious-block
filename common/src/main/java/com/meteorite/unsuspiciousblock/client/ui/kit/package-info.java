/**
 * UI kit：Java 声明式客户端 UI 组件集（阶段 E 记录的**公开边界**，尚未拆包发布）。
 *
 * <p><b>公开入口</b>（第三方宿主可直接依赖，签名变更按兼容策略走变更记录）：</p>
 * <ul>
 *   <li>内容排版：{@link com.meteorite.unsuspiciousblock.client.ui.kit.UiDocument}、
 *       {@link com.meteorite.unsuspiciousblock.client.ui.kit.UiNode}、
 *       {@link com.meteorite.unsuspiciousblock.client.ui.kit.UiIcon}、
 *       {@link com.meteorite.unsuspiciousblock.client.ui.kit.UiTransform}、
 *       {@link com.meteorite.unsuspiciousblock.client.ui.kit.UiMetrics}、
 *       {@link com.meteorite.unsuspiciousblock.client.ui.kit.TextMeasurer}、
 *       {@link com.meteorite.unsuspiciousblock.client.ui.kit.TextScroll}</li>
 *   <li>控件与交互：{@link com.meteorite.unsuspiciousblock.client.ui.kit.UiControl}、
 *       {@link com.meteorite.unsuspiciousblock.client.ui.kit.UiControlStyle}、
 *       {@link com.meteorite.unsuspiciousblock.client.ui.kit.UiControlGroup}、
 *       {@link com.meteorite.unsuspiciousblock.client.ui.kit.UiScrollView}、
 *       {@link com.meteorite.unsuspiciousblock.client.ui.kit.UiLinearLayout}</li>
 *   <li>焦点：{@link com.meteorite.unsuspiciousblock.client.ui.kit.UiFocusTarget}、
 *       {@link com.meteorite.unsuspiciousblock.client.ui.kit.UiFocusManager}</li>
 *   <li>导航：{@link com.meteorite.unsuspiciousblock.client.ui.kit.UiNavigationHistory}（泛型快照、有界历史与有效性过滤）</li>
 *   <li>灯箱：{@link com.meteorite.unsuspiciousblock.client.ui.kit.UiLightbox}（含 Content / Gallery / Labels）、
 *       {@link com.meteorite.unsuspiciousblock.client.ui.kit.UiImageView}、
 *       {@link com.meteorite.unsuspiciousblock.client.ui.kit.LightboxImage}</li>
 *   <li>命中与几何：{@link com.meteorite.unsuspiciousblock.client.ui.kit.UiRect}、
 *       {@link com.meteorite.unsuspiciousblock.client.ui.kit.UiTarget}、
 *       {@link com.meteorite.unsuspiciousblock.client.ui.kit.UiAction}、
 *       {@link com.meteorite.unsuspiciousblock.client.ui.kit.UiNineSlice}</li>
 * </ul>
 *
 * <p><b>内部实现</b>（同为 {@code public}，但不承诺兼容，宿主不应依赖）：没有独立的内部类型，
 * 内部口径都落在各类的非公开成员上——{@link com.meteorite.unsuspiciousblock.client.ui.kit.UiDocument}
 * 的排版缓存与私有几何公式、{@code UiMetrics} 的计时细节、{@code UiTransform} 的裁剪工具等。
 * 它们可以随实现改动而不进变更记录。</p>
 *
 * <p><b>依赖方向</b>：本包只依赖 Minecraft 客户端通用类型、Java 标准库、JOML、LWJGL（按键常量）与
 * JetBrains annotations——不 import 任何项目包、不 import 两端 loader API、不引用 {@code Constants.MOD_ID}；
 * {@code client/} 以外的代码不得引用本包。该约束由 {@code scripts/check-ui-kit-boundaries.ps1} 检查：
 * 规则 1–4 是黑名单，规则 5 是 import 白名单（只允许 {@code java.*} / {@code net.minecraft.*} /
 * {@code org.jetbrains.*} / {@code org.joml.*} / {@code org.lwjgl.*}）。</p>
 *
 * <p><b>宿主适配层</b>：{@code client/ui/overlay/}（模态与 `OverlayLayer` 适配、
 * {@code LightboxOverlay}）与 {@code client/ui/sample/}（开发用示例页）属于宿主/示例层，
 * 允许依赖项目常量与平台服务；它们依赖 kit，而不是反过来。</p>
 *
 * <p><b>公开行为约定</b>：几何入口对负尺寸一律**钳制**而不是抛异常——{@code UiControl.setBounds} 与
 * {@code UiDocument.setViewport} 钳到 ≥0，{@link com.meteorite.unsuspiciousblock.client.ui.kit.UiScrollView}
 * 同口径，{@link com.meteorite.unsuspiciousblock.client.ui.kit.UiLightbox#setBounds(int, int)} 钳到 ≥1；
 * 只有 {@link com.meteorite.unsuspiciousblock.client.ui.kit.UiRect} 自身在构造期拒绝负尺寸。
 * {@link com.meteorite.unsuspiciousblock.client.ui.kit.UiLightbox} 默认用
 * {@link com.meteorite.unsuspiciousblock.client.ui.kit.UiControlStyle#DARK} 暗底，文本默认色按结构底色反推，
 * {@code setStyle} 覆盖样式后文本色随之重算（除非先调用 {@code setTextColor}）；
 * 其 {@code Labels} 新增 default 方法 {@code zoomReadout(int)}，既有实现无需改动。
 * {@link com.meteorite.unsuspiciousblock.client.ui.kit.TextScroll} 另有非交互工具方法
 * {@code trimToWidth(Font, String, int)}（超宽时截断并补 ASCII 省略号），与悬停滚动入口并存。
 * 条目与兼容说明见 {@code docs/dev/internals/ui-kit-api.md}。</p>
 *
 * <p><b>尚未完成</b>：独立 Gradle 模块与发布脚本，需要另立拆包任务；当前只冻结边界与检查方式。
 * 子包拆分（{@code api/} 与 {@code internal/}）已评估并决定不做，理由见
 * {@code docs/dev/internals/ui-kit-api.md} 的「拆包就绪门槛」。</p>
 */
package com.meteorite.unsuspiciousblock.client.ui.kit;
