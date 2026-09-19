## Why

移动端调试登录时，经常需要在同一构建中切换到不同的后端实例。当前后端地址只能在构建前通过环境变量固定，修改地址需要重新构建，阻碍了联调和问题复现。

## What Changes

- 增加构建时调试开关 `EXPO_PUBLIC_FT_API_ORIGIN_OVERRIDE_ENABLED`，仅值为 `1` 或 `true` 时控制登录页显示后端地址输入框。
- 调试开关开启时，在登录和注册模式中共用并预填当前地址；用户可以修改，提交前执行与现有 Native 配置一致的 origin 校验。
- 有效地址成为当前 Native API client 的请求 origin，登录和注册请求都使用该地址；认证成功后才持久化到本机。
- 提供“恢复默认”操作，恢复构建时 `EXPO_PUBLIC_FT_API_ORIGIN` 指定的地址并清除本地覆盖；退出登录不清除当前调试地址。
- 默认构建不展示该调试功能，也不读取或使用历史调试覆盖地址。

## Capabilities

### New Capabilities

- `mobile-login-api-origin`: 移动端登录前可选地覆盖 API origin，并受构建时调试开关控制。

### Modified Capabilities

- 无。

## Impact

- 影响 `mobile` 登录页、会话状态、API client 创建和环境变量类型声明。
- 影响共享 presentation 语义 ID，但不改变 Web 登录页；该控件是 Native 调试专属平台差异。
- 不修改后端 API、登录协议、令牌格式或业务数据。
- 不新增依赖；调试地址属于本地设备配置，使用现有受控本机存储，不进入后端或日志。
- 回滚方式为移除调试入口及其环境变量读取；默认关闭时对正式构建行为无影响。

## Confirmed Decisions

- 调试开关缺省关闭，只在 Native 构建时开启。
- 地址输入框同时作用于登录和注册；不新增登录后的地址设置页。
- 只保存成功认证所使用、且通过 origin 校验的地址；退出登录保留。
- 允许 `https` origin；`http` 继续遵循现有非生产构建限制，不因调试入口放宽安全规则。
- 空地址无效；“恢复默认”清除覆盖并显示构建地址。
