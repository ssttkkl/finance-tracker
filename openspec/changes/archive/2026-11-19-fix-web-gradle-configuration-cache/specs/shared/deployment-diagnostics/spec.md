## ADDED Requirements

### Requirement: Web 生产分发兼容 Gradle 配置缓存

启用 Gradle 配置缓存时，Wasm Web 生产构建 MUST 成功生成分发包。构建配置有变化时，分发包 MUST 使用新配置。

#### Scenario: 在相同配置下重复构建

- **WHEN** 使用相同的 Web 构建参数连续执行生产构建
- **THEN** Gradle 可以复用配置缓存，Web 分发包携带对应版本与 Sentry 配置

#### Scenario: 修改 Web Sentry 配置

- **WHEN** 修改或清空 Web 的 Sentry DSN 后重新构建
- **THEN** 分发包使用新 DSN，清空时不附带 Sentry SDK
