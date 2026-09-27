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
 *   <li>灯箱：{@link com.meteorite.unsuspiciousblock.client.ui.kit.UiLightbox}（含 Content / Gallery / Labels）、
 *       {@link com.meteorite.unsuspiciousblock.client.ui.kit.UiImageView}、
 *       {@link com.meteorite.unsuspiciousblock.client.ui.kit.LightboxImage}</li>
 *   <li>命中与几何：{@link com.meteorite.unsuspiciousblock.client.ui.kit.UiRect}、
 *       {@link com.meteorite.unsuspiciousblock.client.ui.kit.UiTarget}、
 *       {@link com.meteorite.unsuspiciousblock.client.ui.kit.UiAction}</li>
 * </ul>
 *
 * <p><b>内部实现</b>（同为 {@code public}，但不承诺兼容，宿主不应依赖）：{@code UiNineSlice} 以及
 * 各个类里标注为内部的口径（排版缓存、私有几何公式等）。{@link com.meteorite.unsuspiciousblock.client.ui.kit.UiDocument}
 * 的排版缓存、{@code UiMetrics} 的计时细节属实现细节。</p>
 *
 * <p><b>依赖方向</b>：本包只依赖 Minecraft 客户端通用类型、Java 标准库与 JOML/annotations——
 * 不 import 任何项目包、不 import 两端 loader API、不引用 {@code Constants.MOD_ID}；
 * {@code client/} 以外的代码不得引用本包。该约束由 {@code scripts/check-ui-kit-boundaries.ps1} 检查。</p>
 *
 * <p><b>宿主适配层</b>：{@code client/ui/overlay/}（模态与 `OverlayLayer` 适配、
 * {@code LightboxOverlay}）与 {@code client/ui/sample/}（开发用示例页）属于宿主/示例层，
 * 允许依赖项目常量与平台服务；它们依赖 kit，而不是反过来。</p>
 *
 * <p><b>尚未完成</b>：把本包物理拆成 {@code api/} 与 {@code internal/} 子包、独立 Gradle 模块与发布脚本，
 * 需要另立拆包任务；当前只冻结边界与检查方式，详见 {@code docs/dev/internals/ui-kit-api.md}。</p>
 */
package com.meteorite.unsuspiciousblock.client.ui.kit;
