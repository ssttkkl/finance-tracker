## Context

当前 Native 通过 `mobile/src/platform/config.ts` 校验 `EXPO_PUBLIC_FT_API_ORIGIN`，并把 `nativeApiOrigin` 作为 `mobileApiClient` 的动态 `baseUrl`。登录页已经同时承载登录和注册；会话 Provider 在首次会话请求完成后决定显示认证页还是受保护页面。详见 `proposal.md` 与 `specs/mobile-login-api-origin/spec.md`。

本变更没有数据库、服务端 API、财务金额、工作区数据或跨后端持久化影响。地址是设备本地配置，不属于工作区或账本事实。

## Goals / Non-Goals

**Goals:**

- 通过 Expo 构建环境变量在编译时控制 Native 调试入口是否可见。
- 让登录和注册在认证请求前使用同一份经校验的当前 origin。
- 在首次会话请求前恢复有效的本机调试地址，避免先请求构建地址再切换。
- 认证成功后保存地址；“恢复默认”清除覆盖；退出登录保留覆盖。
- 维持现有 origin 校验和生产环境的 HTTPS 边界，并为 Native 调试控件提供稳定语义 ID。

**Non-Goals:**

- 不在 Web 登录页增加地址选择器。
- 不增加登录后的全局地址设置页、后端地址发现、多个地址列表或地址连通性探测。
- 不修改后端登录协议、Token 格式、工作区会话、业务数据或服务器日志。
- 不为本地地址配置引入新的存储依赖。

## Decisions

### 1. Build-time gate and origin resolution

使用 `EXPO_PUBLIC_FT_API_ORIGIN_OVERRIDE_ENABLED` 作为 Expo 公共构建变量；只有值为 `1` 或 `true`（忽略大小写及首尾空白）时启用。构建地址始终来自 `EXPO_PUBLIC_FT_API_ORIGIN`。

Native 维护一个进程内的可选地址覆盖，`mobileApiClient` 继续通过函数形式读取当前 origin：

```text
build env ──validate──▶ 构建地址
                           │
                  debug enabled?
                           │ yes
                           ▼
SecureStore ──validate──▶ 当前地址覆盖 ──┐
                                        ├──▶ API client request
登录/注册输入 ──validate──▶ 当前地址覆盖 ──┘
```

会话 Provider 先完成本机地址恢复，再发起首次 `session` 请求。认证提交前将输入值校验并放入内存；登录或注册成功后再写本机存储。认证失败不会替换已经保存的地址，但允许用户用当前输入立即重试。

### 2. Shared validation, storage, and lifecycle

把现有 Native origin 校验抽为可测试的共享函数，构建地址、持久化地址和表单输入共用同一规则。校验结果统一去除末尾 `/`。不允许路径、查询参数、片段、用户名、密码或空值；`http` 仅在非生产构建且带显式端口时允许，`https` 保持现有规则。

复用已存在的 `expo-secure-store`，新增独立的地址配置存储键，不与会话 Token 共用键或清理生命周期。存储读取使用依赖注入的最小接口，便于在 Vitest 中验证；无效历史值安全地回退到构建地址并清除。存储失败不应撤销已经成功的服务端认证，当前进程内地址仍保持有效。

关闭调试开关时不读取、不使用历史覆盖，也不显示调试控件。热重载或测试环境中重新初始化时，内存覆盖会先回到构建地址，避免隐藏配置泄漏到默认路径。

### 3. Authentication UI and presentation boundary

在现有 `login.tsx` 的登录/注册表单中增加一个可选的“后端地址”字段。它位于密码字段之后、认证错误之前；右侧提供“恢复默认”次要操作。控件仅在调试开关开启时渲染，不加入 Web DOM，也不改变核心登录按钮语义。

新增 `auth.api-origin` 和 `auth.api-origin-reset` 语义 ID，复用共享 presentation 包的稳定标识机制；调试字段不是 Web 与 Native 的核心 parity 区域，因此通过 Native-only 平台差异登记，不把 Web 登录页伪装成已支持该调试能力。错误直接贴近地址字段显示“请输入有效的后端地址。”，网络或凭据失败继续使用既有认证错误语义。

原型位于 `prototype/index.html`，沿用 Native 的 Cobalt 色板、4-point 间距和触控目标；展示默认构建隐藏字段、调试默认值、聚焦、无效值、提交中禁用和成功返回状态，并检查 320、375、390、414、768 px 宽度无横向滚动。原型顶部保留 Hallmark 预检与自检标记。

### 4. Transaction, compatibility, and rollback boundary

不存在数据库事务，也不需要 PostgreSQL/SQLite 双后端矩阵：认证 HTTP 请求与本机 SecureStore 写入是两个明确边界。服务端认证成功先决定用户结果，本地写入仅保存客户端调试偏好；写入失败不会重复认证、重试请求或修改服务端数据。

回滚时移除地址字段、配置存储和环境变量读取即可；构建开关缺省关闭，旧版本或未配置开关的构建仍使用原有 `EXPO_PUBLIC_FT_API_ORIGIN`。若本地残留配置由新版本写入，关闭开关的旧/新构建均忽略它，不影响 Token。

## Cross-platform Impact Check

| 层级 | 影响 | 处理 |
|------|------|------|
| Web | 不展示、不读取该调试配置，登录协议与地址来源不变。 | 不修改 Web 登录组件；保留既有 `VITE_FT_API_ORIGIN` 行为。 |
| Native | 登录/注册页增加构建开关控制的地址输入、校验、恢复默认和本机恢复。 | 修改 Native 配置、会话 Provider、认证页面和 Native 测试。 |
| 共享层 | 只新增两个 Native 调试控件的稳定语义 ID及可复用文案；不改变核心认证区域、操作和状态合同。 | 在 `presentation` 中登记 ID；在平台差异中说明 Native-only 条件。 |

允许的平台差异是“Web 无该调试入口、Native 调试构建有该入口”；不变量是关闭开关时两端都使用各自构建配置的默认 API origin，认证字段、会话结果、错误和 Token 语义不变。

## Risks / Trade-offs

- **[Risk]** 调试构建可能把内部测试 origin 编入公共 JavaScript 环境变量。→ 仅在明确的 Native 调试构建中开启；正式构建缺省关闭，输入值不上传、不写日志。
- **[Risk]** 用户输入的错误 origin 可能让认证失败或请求到错误实例。→ 复用 origin-only 校验，提交前阻断；不做自动探测或静默回退。
- **[Risk]** SecureStore 写入失败会导致下次启动无法恢复地址。→ 不阻断本次成功认证；当前进程保留地址，并在任务记录中保留该残余风险。
- **[Risk]** 首次 `session` 请求早于地址恢复会命中错误后端。→ Provider 在首个会话请求前等待地址恢复；存储值无效时安全回退构建地址。
- **[Risk]** 新增共享语义 ID但 Web 不渲染对应控件。→ 不把调试控件加入核心 Web parity 的必需区域，在 `platformDifferences` 和本变更任务中登记 Native-only 条件。

## Migration Plan

1. 发布包含代码但不开启 `EXPO_PUBLIC_FT_API_ORIGIN_OVERRIDE_ENABLED` 的构建，验证其行为与现有 Native 登录一致。
2. 调试构建显式设置该开关，并同时提供有效的 `EXPO_PUBLIC_FT_API_ORIGIN` 作为恢复默认地址。
3. 需要回滚时不设置该开关或重新构建关闭开关；构建地址继续由 `EXPO_PUBLIC_FT_API_ORIGIN` 提供，历史覆盖不会生效。

## Open Questions

无。构建开关取值、展示范围、持久化、校验、恢复默认和退出登录语义已由需求澄清确认。

## Hallmark Preflight and Prototype Record

- Preflight：复用项目已有 Cobalt Native 色板、Noto Sans SC / IBM Plex Mono 术语、4-point 间距和 44 px 触控目标；项目没有 Native 专用 motion library，采用 motion-cut。
- Scope：component-scope；跳过页面级 macrostructure、导航和 footer，保留现有认证卡片的信息架构。
- Prototype：`openspec/changes/mobile-login-api-origin-override/prototype/index.html`。
- 状态覆盖：默认隐藏、调试默认值、空/无效、聚焦、加载/禁用、成功、恢复默认。
- 响应式检查：320、375、390、414、768 px；最终 Native QA 另覆盖 390 px phone、768 px tablet 和 1440 px wide contract 记录。
