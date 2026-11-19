## Why

Render 的 Compose Web 生产构建已生成 Wasm 和 JavaScript 产物，Gradle 保存配置缓存时却无法序列化两个 Web 资源任务引用的脚本对象，导致发布失败。

## What Changes

- 用户确认只修复 Gradle 配置缓存兼容性。保留 Render 构建命令和默认配置缓存。
- Gradle 在配置阶段解析任务所需的公开构建参数、版本和路径。任务动作只依赖可序列化的值。
- 验收覆盖 Sentry 开关、变更 DSN 后刷新配置与相同输入复用配置缓存。SDK 版本、校验和、本地诊断与后端不在范围内。

## Capabilities

### Modified Capabilities

- `shared/deployment-diagnostics`：恢复启用配置缓存的生产构建。
