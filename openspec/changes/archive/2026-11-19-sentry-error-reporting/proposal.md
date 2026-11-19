## Why

部署维护者需要集中发现 Web 与 API 的异常，并按版本与请求 ID 回查本地诊断。一次故障可能发生在后端响应后，集中错误事件需要保留足够定位信息，同时保护凭据和财务内容。

## What Changes

- 用户确认仅接入脱敏错误上报，在本机模拟接收端验证实际事件。
- API 与 Wasm Web 分别配置 DSN，采集未预期异常、运行故障和认证 5xx。
- 使用合成数据验证载荷、禁用状态、上报失败隔离和本地生产主流程。
- 性能追踪、会话回放、线上 Sentry 配置与部署留待后续。

## Capabilities

### Modified Capabilities

- `shared/deployment-diagnostics`：增加可配置的错误上报。
