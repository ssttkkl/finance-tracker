## Context

当前服务端已经由 `src/ft/web/app.py` 的 `create_runtime_app` 装配 FastAPI、认证上下文、工作区服务、导入暂存和投资读取服务；`src/ft/web/routes.py` 已提供收支账本、现金流水、关系和账单导入接口。`web/` 是独立的 React 19 + Vite 应用，API 类型、请求封装和浏览器文件处理仍集中在 `web/src/api/`。

`src/ft/cli.py` 同时包含服务启动、现金流水、账户、账单导入、关系、投资、报表、同步和投影运维入口。它直接装配 Application Service，因此删除文件前必须先确认每项用户侧能力已有 Web/API/Native 承载；领域层、导入器、连接器和持久化适配器不是 CLI，不随 CLI 一起删除。

本设计采用 npm workspace 作为 JavaScript/TypeScript 单仓库边界。Expo 从当前稳定 SDK 57 开始，官方文档支持 npm workspace，并自动处理 SDK 52 之后的 Metro monorepo 配置；Expo Router 同时支持 React Native 和 Web，但本次只把它用于 Native 路由，避免把现有 DOM/CSS Web 页面强行改写。参考：[Expo monorepo 指南](https://docs.expo.dev/guides/monorepos/)、[Expo Router 文档](https://docs.expo.dev/versions/latest/sdk/router/)。

## Goals / Non-Goals

**Goals:**

- 建立 `mobile/` Expo Android/iOS 应用、共享 TypeScript 包和清晰的平台适配边界。
- 让 Web 与 Native 复用 API 契约、金额/数量字符串语义、请求错误模型、导入会话状态和纯业务选择器。
- 让 Native 完成登录、工作区、收支账本、手工记账、凭证入口、导入和关系审查的首个纵向切片。
- 保留现有 Web 的完整桌面能力和现有 URL 路由，保证迁移期间可回退。
- 用显式 Uvicorn/开发脚本替代 `ft web`，并在验证替代能力后删除全部 CLI 公共面。
- 对客户端触发的写请求保留工作区隔离、权限检查、来源溯源、幂等和精确十进制语义。

**Non-Goals:**

- 不把现有 `web/` 改造成 Expo Web，不引入 React Native Web 作为现有页面的兼容层。
- 不重写 Python Domain、Application Service、FastAPI 路由体系、账本 schema 或既有财务规则。
- 不引入离线写入、离线 outbox、后台同步、冲突合并或本地数据库作为事实源。
- 不在客户端复制余额、投影、投资估值、关系配对或导入解析规则；客户端只消费服务端结果。
- 不在本变更内完成 App Store、Google Play 上架、生产 EAS 发布或推送通知；先完成可运行的开发构建与发布前检查。

## Decisions

### 1. 采用根级 npm workspace，保留 Web 应用目录

目标结构如下：

```text
package.json                 # private workspace 根配置
web/                          # 现有 React/Vite Web，初期保留现有路由与 CSS
mobile/                       # Expo SDK 57 + Expo Router Android/iOS 应用
packages/
  contracts/                  # API DTO、错误码、角色和金额字符串类型
  api-client/                 # 与运行环境无关的 HTTP 客户端
  core/                       # 导入会话、账本筛选和表单状态机等纯逻辑
  design-tokens/              # Web CSS 与 Native StyleSheet 共用的语义 token
```

选择 npm workspace 而不是继续维护两个互不相识的 package lock，是为了让 `web`、`mobile` 和共享包在同一次依赖安装中解析到唯一的 React/React Native/Expo 版本。迁移完成后根目录 `package-lock.json` 是 Node 依赖事实源；`web/package-lock.json` 只在依赖切换阶段保留，切换完成后删除，避免开发者在错误目录安装出第二套依赖。

Expo 应用先固定 SDK 57 的兼容版本，依赖通过 `npx expo install` 写入，避免手工组合不匹配的 React Native 原生版本。文件导入采用 `expo-document-picker`，文件读取/二进制上传采用 `expo-file-system`；官方文档说明 `copyToCacheDirectory: true` 可保证选择后立即读取，且 SDK 57 的 `File` 支持 `bytes()` 与二进制上传。参考：[DocumentPicker](https://docs.expo.dev/versions/latest/sdk/document-picker/)、[FileSystem](https://docs.expo.dev/versions/latest/sdk/filesystem/)。

### 2. 共享契约，不共享 DOM 页面

先把 `web/src/api/types.ts` 中的 DTO 和错误解析迁入 `packages/contracts`，Web 通过包导入并保留必要的兼容 re-export。`packages/api-client` 提供注入式客户端：

- 构造参数包含 `baseUrl`、`fetch` 实现和 `TokenStore`，不得读取 `window`、`import.meta.env` 或浏览器全局对象。
- 所有金额、数量、汇率和成本在 TypeScript 合同中保持字符串；客户端不能用 `number` 重新计算账务结果。
- 统一解析 HTTP 状态、服务端 `error.code`、权限错误、版本冲突、导入密码错误和存储错误；原始账单和 Token 不进入错误文本。
- 导入确认继续携带 `Idempotency-Key`、预览摘要、关系决策和账户映射，服务端 Application Service 仍负责最终校验和事务。

`packages/core` 只放无平台副作用的 reducer、状态机、选择器和表单 payload 构造器。Web 的 `HTMLElement`、Portal、焦点恢复、History API、`File`、`EventSource` 和 Native 的 `View`、原生导航、系统文件 URI 不得进入共享包。

### 3. Native 采用 Expo Router，Web 暂不换路由

Native 路由按认证和工作区分组组织：

```text
mobile/src/app/
  _layout.tsx
  index.tsx
  (auth)/login.tsx
  (app)/_layout.tsx
  (app)/workspace.tsx
  (app)/ledger.tsx
  (app)/record.tsx
  (app)/import.tsx
```

`index.tsx` 根据会话状态重定向到登录或工作区；`(auth)` 与 `(app)` 两个 route group layout 也分别守卫已登录和未登录状态，退出时先清理本地令牌再回到登录页。工作区选择继续调用现有 `/api/v1/auth/workspaces/{id}/select`，不在客户端自行推断或持久化工作区权限。Native 使用 Expo Router 的 typed route 能力；现有 Web 保留 `web/src/routing.ts`，待后续页面共享迁移完成后再单独评估 Web 路由统一。

移动端页面使用单列、触控目标不小于 44 px、清晰的加载/空/错误/成功状态；列表使用 `FlatList` 或等价的虚拟化列表，不把 HTML `<table>` 搬到 Native。当前原型位于 `prototype/index.html`，采用 `Narrative Workflow` 的阶段结构来表达「进入工作区 → 看收支 → 记一笔 → 导入账单」，并覆盖正常、空、加载、错误、成功、禁用和删除确认状态。

### 4. TokenStore 与文件来源按平台注入

共享层只依赖以下接口：

```ts
type TokenStore = {
  get(): Promise<string | null>;
  set(value: string): Promise<void>;
  clear(): Promise<void>;
};
```

Web 实现继续使用 `finance-tracker:session-token` 的 `localStorage` 适配器；Native 使用符合 SecureStore 键名约束的 `finance-tracker-session-token` 保存短会话 Token，不保存账单、密码或完整用户资料。两端的存储键隔离，避免原有 Web 会话因 Native 存储限制而失效。Expo 官方将 SecureStore 定位为设备上的加密 key-value 存储，同时提醒大值可能失败，因此不会把缓存账本塞入其中。参考：[Expo SecureStore](https://docs.expo.dev/versions/latest/sdk/securestore/)。

文件来源接口返回文件名、媒体类型、大小和一次性读取/上传能力。Web 使用浏览器 `File`；Native 使用 `DocumentPicker.getDocumentAsync({ copyToCacheDirectory: true })` 加 `expo-file-system` `File`。取消文件选择直接结束当前选择动作，不创建导入会话。账单密码只作为当前请求内存参数，并在请求结束后丢弃。

### 5. 认证与服务端边界保持现状

本变更不重做 Bearer Session 协议。客户端统一发送 `Authorization: Bearer <token>`，服务端仍以认证用户、活动工作区和成员角色解析上下文；`viewer` 的写入请求仍由现有中间件拒绝。Native 的 API 地址通过开发/构建环境注入，不能硬编码 `127.0.0.1` 作为真机地址；本地真机文档使用开发机局域网地址，生产环境只接受 HTTPS。

如果后续需要短期 access token、refresh token、设备撤销或显式 workspace path，应另开认证/工作区契约变更，不在本次迁移中混入。

### 6. CLI 删除采用能力清单和两阶段切换

先建立 CLI 能力清单：

| 现有入口 | 替代面 | 处理方式 |
|----------|--------|----------|
| `web` | Uvicorn 命令/开发脚本 | 保留 FastAPI 工厂，删除 CLI 分发 |
| `add`、`checkin`、`transfer`、`acct` | Web/API/Native 记账流程 | 复用现金 Application Service |
| `import`、`convert` | Web/Native 导入会话 | `convert` 不再作为独立用户步骤 |
| `relations`、`funding-relations` | Web/Native 关系审查 | 复用现有关系服务和错误合同 |
| `stock` | 投资 API 与后续 Native 投资流程 | 现有 Web 读取保持不变，写入边界先补齐 |
| `list`、`report` | Web/API 查询 | 不在客户端重新计算报表 |
| `sync` | 受控后端同步脚本或 API | 凭据只在服务端受控位置加载 |
| `projections` | 显式后端运维脚本或 API | 不暴露为普通用户功能 |
| `fact-delete` | Web/Native 删除确认流程 | 保留实际删除和关系影响确认语义 |

替代入口通过契约测试后，才执行删除：移除 `pyproject.toml` 的 `[project.scripts]`、`src/ft/cli.py`、仅被 CLI 使用的 `src/ft/cli/import_cmd.py` 和 CLI 展示适配器；纯解析器、导入器、Application Service、Domain、连接器和 Web 路由保持。CLI 专属测试改为 API/应用服务合同测试或删除无对应行为的渲染测试。

### 7. 不新增账本 schema；客户端写入沿用既有事务

本次共享层和客户端本身不持久化账本数据，不新增 Alembic migration。新增 HTTP 边界时必须调用既有 Application Service，让每个写请求沿用当前 Unit of Work 的事务边界；导入确认的预览摘要、来源快照、账户映射、关系决策和 `Idempotency-Key` 仍按当前导入事务一次提交。

对于现有已经按业务身份幂等的导入、同步和投资事件，客户端保留服务端合同；对于暂不具备通用请求幂等的手工写入，首个客户端禁止自动重试，超时显示「未确认」并先重新读取服务端状态。若验证发现移动端需要通用写入重试，必须先补充独立的持久化幂等契约和 SQLite/PostgreSQL 矩阵，不以客户端缓存或双写规避。

### 8. 本地和发布命令显式化

建议的命令面：

```text
# 后端
FT_DATABASE_URL=... uv run uvicorn ft.web.app:create_runtime_app --factory --host 127.0.0.1 --port 8000

# Web
npm run dev --workspace web

# Expo Native
npm run start --workspace mobile
npm run android --workspace mobile
npm run ios --workspace mobile
```

Expo SDK 57 的原生依赖以 development build 为主要验证目标；`npx expo start` 负责 Metro，真机需要局域网可达的 `FT_API_ORIGIN`，不把 Expo Go 的版本偶合当作发布条件。EAS 配置留在后续发布变更。

### 9. iOS 原生依赖的可重复兼容处理

当前 Xcode 26.3 / Swift 6.2 会触发 Expo SDK 57 的 `expo-modules-jsi` 源码兼容问题：`RuntimeScheduler` 的 `SWIFT_RETURNS_RETAINED` 注解无法通过编译，且并发检查会拒绝部分未标注的指针/弱引用捕获。移动端保留一个版本锁定、幂等且只在预期原文匹配时执行的 config plugin：

- 通过 `withPodfileProperties` 将 `EXPO_USE_PRECOMPILED_MODULES` 固定为 `false`，让 `expo-constants` 的 app config 在源码构建链中正确嵌入；
- 在 `prebuild` 期间修补 `RuntimeScheduler.h`、`JavaScriptRuntime.swift` 和 `EventEmitter.swift` 的已知 Xcode 兼容点；
- 依赖源形状不匹配时直接失败，不静默生成部分修补结果；重复 `prebuild` 不重复写入补丁；
- 不修改生成的 `ios/`、`android/` 或 `node_modules/`，也不把补丁放进发布脚本之外的人工步骤。

该 plugin 属于 Expo 构建适配层，不改变业务代码或 API 合同。升级 Expo/Xcode 时必须重新运行原生构建和 plugin fixture；若上游版本已包含修复，应删除相应补丁并保留源码构建开关的验证结论。Expo 官方 issue 记录了上述 Xcode 26.3 兼容症状及对应修复方向：[expo/expo#49214](https://github.com/expo/expo/issues/49214)、[expo/expo#48961](https://github.com/expo/expo/issues/48961)。

### 10. GitHub Actions Native CI

Native CI 放在 `.github/workflows/mobile-ci.yml`，只依赖根级 `package-lock.json`，不把 Expo 生成的 `mobile/android/` 或 `mobile/ios/` 纳入版本控制。工作流提供三类触发：所有 Pull Request、推送到 `refactor/web`，以及 `workflow_dispatch` 手动运行；同一分支的新运行会取消仍在排队的旧运行。

工作流拆成质量、Android 和 iOS 三个 job：

- `quality` 使用 Node 24，执行根级 `npm ci`、共享包和 Mobile 的 Vitest/typecheck，以及 Android/iOS JavaScript export，尽早发现 workspace、路由和 bundler 回归。
- `android` 使用 Ubuntu、Java 17 和 Android SDK 36，安装项目当前 Gradle/Expo 需要的 platform、build-tools 和 NDK，在 `mobile/` 工作目录执行 `CI=1 npx expo prebuild --platform android --no-install` 后运行 `:app:assembleDebug`，上传 `app-debug.apk`。
- `ios` 使用 macOS runner，在 `mobile/` 工作目录执行 `CI=1 npx expo prebuild --platform ios --no-install`，再执行 `pod install --no-repo-update` 和 Generic iOS Simulator Debug build；构建时显式关闭 code signing，再将生成的 `FinanceTracker.app` 压缩后上传。

三个 job 都使用 `actions/setup-node` 的 npm cache；Android 通过 `actions/setup-java` 的 Gradle cache 减少重复下载。Artifact 只保留短期测试用途，命名为 `finance-tracker-android-debug` 和 `finance-tracker-ios-simulator`。工作流不配置 EAS、Apple/Google 凭据、证书、provisioning profile 或生产 API 地址，因此不能被误认为商店发布门禁。后续若需要签名 release，应另开变更，明确 secrets、环境保护、版本号、签名轮换和回滚策略。

## Risks / Trade-offs

- [共享包扩大 TypeScript 构建范围] → 先只迁移 DTO、API client、纯状态和 token；每个 workspace 独立执行类型检查，禁止共享包依赖 DOM 或 Native 模块。
- [Expo SDK、React 和 React Native 版本不一致] → 使用 SDK 57 模板和 `npx expo install`；根级 lockfile 只允许一套 React/React Native 版本，并在 Metro 导出检查中验证。
- [Native 文件 URI 与 Web `File` 语义不同] → 统一 FileSource 接口；Native 选择后复制到 cache，上传使用二进制请求，取消和读取失败分别建模，不把 URI 暴露给领域层。
- [CLI 删除遗漏隐藏用户流程] → 删除前运行命令清单、模块引用、脚本和活跃文档扫描；未找到替代面的命令不得静默删除，必须落入 API、Web/Native 或受控运维脚本。
- [移动网络超时造成重复手工记账] → 首期禁用自动重试并在超时后先读回服务端；把通用幂等列为明确后续契约，不伪称客户端重试安全。
- [当前活动工作区是服务端可变状态] → 本次完全沿用现有认证合同，并在客户端切换后强制重新读取；显式 workspace path 另开变更。
- [Native UI 与现有 Web 视觉漂移] → 共享语义 token 和文案术语，保留平台原生渲染；先以首个纵向切片验证任务流，再扩大页面范围。
- [Xcode/Swift 更新破坏 Expo 原生依赖] → 兼容补丁只允许预期源码形状、带 fixture 且通过 prebuild 自动应用；升级依赖时优先移除补丁或切换到上游修复，不编辑生成目录。
- [GitHub runner 的 Node/Android SDK/Xcode 版本漂移] → 在 workflow 中固定 Node 主版本、Java 17、Android API/build-tools/NDK 版本和 macOS runner 标签；Native artifact 仅作为未签名测试产物，runner 或 Expo 升级时重新执行 prebuild 与原生构建。
- [未签名 CI 产物被误当作发布包] → workflow 名称、artifact 名称和文档明确 Debug/Simulator/unsigned 属性；不注入任何签名或生产凭据，商店 release 另行设计。
- [PostgreSQL 与 SQLite 行为不等价] → 无 schema 变更不降低既有双后端门禁；任何新增 API 写入都补同一 Application Service 的 SQLite 与真实 PostgreSQL 合同矩阵。

## Migration Plan

1. 记录当前 `HEAD`、Web/API 基线和 CLI 能力清单；完成本变更的原型与 OpenSpec 校验。
2. 建立根级 npm workspace，创建 `packages/contracts`、`packages/api-client`、`packages/core`、`packages/design-tokens`，让现有 Web 通过共享包构建并通过回归。
3. 创建 Expo SDK 57 Native 应用和 TokenStore、文件来源、API client 注入；先实现认证、工作区和收支账本纵向切片。
4. 补齐 Native 的手工记账、凭证入口、导入会话和关系审查；对现有 API 缺口新增薄路由适配，并为每个写入边界补契约测试。
5. 为投影运维和同步提供显式后端脚本/API，确认所有用户侧 CLI 能力有替代面；更新 README、开发脚本和活跃文档。
6. 在 SQLite 回归和 Web/Native 构建通过后，执行真实 PostgreSQL `_test` 数据库矩阵；完成浏览器 QA、Native 可运行检查、安全扫描和独立 diff 复核，并让 GitHub Actions 产出未签名 Android/iOS 测试 artifact。
7. 删除 CLI 注册、模块、CLI 专属测试和活跃引用；再次运行仓库扫描、OpenSpec 校验、完整回归和 Web QA。
8. 回滚时保留数据库和账本数据不变；若客户端不满足发布条件，继续使用 Web/API；CLI 删除后的本地服务使用显式 Uvicorn 命令，不恢复已删除的入口。

## UI 原型与审查记录

- Hallmark 预检：沿用现有 React/Vite Web 的 Noto Sans SC、IBM Plex Mono、Cobalt OKLCH token、紧凑规则线和 `motion-cut` 语汇。
- 原型路径：`openspec/changes/migrate-expo-remove-cli/prototype/index.html`。
- 信息架构：进入工作区 → 看收支 → 记一笔 → 导入账单；删除了桌面侧栏、宽表格、技术状态标签和重复教学段落。
- 原型状态：正常列表、空账本、填写、加载、错误、成功、禁用、账单密码提示、账户映射和删除确认。
- 响应式预检目标：320 px、375 px、414 px、768 px；需确认无横向滚动、可点击文字不换行，另按项目规则补查 390 px 和 1440 px。
- Hallmark 最终 `audit`：2026-09-06 按 `docs/ui-design-rules.md` 完成人工等价审查；运行时没有可执行的 Hallmark CLI，因此未声称执行不存在的命令。critical/major 均为 0，既有 Web CSS 的少量非 token 像素字面量作为 minor 延期；截图与详细证据回写 `tasks.md`，审查日志写入 `.hallmark/log.json`。本原型的预检自评为 `P5 H5 E4 S5 R5 V4`。
