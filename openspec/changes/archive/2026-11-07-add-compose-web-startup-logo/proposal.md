## Why

Compose Web 在 Wasm 下载与初始化完成前没有内容，使用者会看到空白页；缺少运行特性的浏览器也无法进入应用。按用户确认的范围，用居中 Logo 呼吸动画表达启动状态，并在不兼容时给出明确的浏览器升级要求。

## What Changes

- 初始 HTML 显示现有应用 Logo；Compose 首帧绘制后撤下启动层，不设置最短等待时间。
- 沿用主题背景，支持系统减少动态效果设置与无障碍加载名称。
- Wasm 应用包加载前检测基础 WebAssembly、GC 与当前 Kotlin 需要的旧版异常处理；不支持时停止动画，显示升级提示与 Chrome、Edge、Firefox、Safari 最低版本，单独说明 iPhone/iPad 系统要求。
- 不加进度条、百分比、自动 JS 降级或通用网络错误处理；不更改登录、会话恢复及 Native 启动。JS 开发目标不因缺少 GC 被额外拦截。

## Capabilities

### New Capabilities

无。

### Modified Capabilities

- `shared/cross-platform-presentation`：增加 Web 启动前的 Logo 加载态、首帧交接及浏览器不兼容提示合同。

## Impact

B 类局部增强：仅 Web HTML/CSS、平台启动脚本、两个 Web 入口及共用 Web 启动函数、浏览器回归与功能地图。无 API、数据库、财务语义或依赖版本变化。兼容检测在本地完成，不按 User-Agent 阻拦，不增加依赖。当前 worktree 已隔离，分支为 `feat/compose-web-wasm-loading-animation`。

验收：支持运行要求时，脚本和 Wasm 尚未加载即显示居中呼吸 Logo，首帧后撤下，减少动态效果时静止，应用保持可操作。缺少基础 Wasm、GC 或旧版异常处理时不请求应用包或 Wasm，显示可访问且可滚动的升级说明与已核实的最低版本；网络或应用错误不被误报为浏览器过旧。部署需整体更新 Web 分发文件，回滚同一批启动资源和入口代码即可。
