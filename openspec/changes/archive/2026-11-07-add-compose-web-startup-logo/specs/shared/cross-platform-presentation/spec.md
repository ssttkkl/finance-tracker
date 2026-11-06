## ADDED Requirements

### Requirement: Web 启动期间显示居中 Logo 呼吸动画

满足运行要求的 Web 浏览器 MUST 在应用脚本与 Wasm 尚未就绪时显示视口居中的现有应用 Logo，以轻缓呼吸动画表达加载状态；MUST 沿用当前主题背景，不显示进度条或百分比。系统要求减少动态效果时 MUST 静止显示 Logo。启动层 MUST 提供加载中的无障碍名称且不抢焦点。

Compose 首个内容帧成功绘制后，Web MUST 自动撤下启动层，不人为延长等待，不遗留遮挡或阻断正常操作。此加载层只覆盖 Web 运行时启动，MUST NOT 改变各平台登录、会话恢复、字体加载及业务加载的语义；Android 和 iOS 继续使用现有原生启动路径。

#### Scenario: 运行时尚未加载

- **WHEN** 使用者打开 Web 页面，应用脚本或 Wasm 仍在加载
- **THEN** 页面显示居中的应用 Logo 呼吸动画，不等待 Compose 才开始显示，也不以资源挂载代替首帧完成

#### Scenario: Compose 首帧交接

- **WHEN** Compose 成功绘制首个内容帧
- **THEN** 启动层自动撤下，应用可见且可操作，不为了展示完整动画额外等待

#### Scenario: 使用者要求减少动态效果

- **WHEN** 系统启用减少动态效果并打开 Web
- **THEN** Logo 静止显示，首帧交接保持相同行为

### Requirement: Wasm 浏览器不兼容时提示明确的升级要求

Web Wasm 应用 MUST 在请求应用包之前检测实际 WebAssembly、垃圾回收和当前编译所需旧版异常处理能力。缺少任一运行要求时 MUST 停止加载动画，显示升级或更换浏览器的提示，不继续请求应用包或 Wasm；MUST NOT 根据浏览器名称或 User-Agent 单独判定兼容性。

提示 MUST 列出 Chrome 119、Edge 119、Firefox 120、Safari 18.2 及以上的升级参考版本，提供官方兼容说明链接，并单独说明 iPhone/iPad 升级至 iOS/iPadOS 18.2 或更新版本、仅更新浏览器 App 可能仍无法使用。提示 MUST 不依赖应用运行时或 Web 字体，支持键盘访问和受限视口滚动。JS 开发目标 MUST NOT 因缺少 WasmGC 被额外拒绝。网络和应用错误 MUST NOT 被误报为浏览器不兼容。

#### Scenario: 缺少基础 Wasm 或 GC

- **WHEN** 当前浏览器没有 WebAssembly 或不能验证当前 GC 模块
- **THEN** 停止呼吸动画并显示浏览器升级提示和版本参考，不请求应用包或 Wasm

#### Scenario: 缺少旧版异常处理

- **WHEN** 浏览器支持基础 Wasm 和 GC，但不支持本应用需要的旧版异常处理
- **THEN** 显示相同升级提示，不把仅支持基础 Wasm 当作可运行，不继续加载应用

#### Scenario: 浏览器品牌未知但能力满足

- **WHEN** 当前浏览器实际具备应用需要的全部运行能力，无论其名称和 User-Agent 是什么
- **THEN** 正常加载应用，不显示不兼容提示

#### Scenario: 支持能力但资源请求失败

- **WHEN** 检测通过后应用资源的网络请求失败
- **THEN** 不将请求失败转换成浏览器版本过低提示
