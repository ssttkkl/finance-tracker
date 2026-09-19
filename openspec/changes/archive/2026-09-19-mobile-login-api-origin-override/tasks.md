## 1. 思考与范围

- [x] 1.1 阅读 `AGENTS.md`、`openspec/project-context.md`、`DOMAIN_GLOSSARY.md`、相关主规格、现有 active change、Native 登录代码和测试，确认该变更为 A 类。
- [x] 1.2 通过 `grill-me` / `grilling` 完成需求澄清，记录构建开关、展示范围、持久化、校验、恢复默认和退出登录决策。
- [x] 1.3 完成 Cross-platform Impact Check：Web 不改；Native 增加调试入口；共享层只增加稳定语义 ID 和对应平台差异登记。

## 2. 计划与 UI 原型

- [x] 2.1 更新 `proposal.md`，明确范围、非目标、回滚和已确认决策。
- [x] 2.2 创建 `specs/mobile-login-api-origin/spec.md`，覆盖开关、校验、请求路由、本机持久化、恢复默认和退出登录场景。
- [x] 2.3 创建 `design.md`，记录 origin 解析数据流、存储边界、替代方案、风险、响应式策略和跨端差异。
- [x] 2.4 创建 `prototype/index.html`，表达默认隐藏、调试默认值、空/聚焦、无效、加载/禁用、成功和恢复默认状态；检查 320、375、390、414、768 px 无横向滚动。
- [x] 2.5 用户确认原型与 Native 认证卡片中的字段顺序、文案和状态范围后再进入生产代码实现。
- [x] 2.6 使用 Hallmark component-scope 设计约束：沿用 Cobalt Native tokens、4-point 间距、44 px 触控目标和 motion-cut；不新增页面级结构。

## 3. OpenSpec 校验与测试先行

- [x] 3.1 运行 `openspec validate --all --strict` 和 `openspec doctor`，修复所有 artifact 格式或依赖问题；2026-09-18 预实现运行结果为 41 项全部通过，OpenSpec root 正常。
- [x] 3.2 为 origin 规范化、开关解析、HTTP/HTTPS 边界、非法 origin 阻断和默认回退编写失败的 Vitest 测试，并确认测试因生产行为缺失而失败；随后实现并验证 `mobile/src/platform/config.test.ts` 11 个测试通过。
- [x] 3.3 为有效地址的 SecureStore 读写、无效历史值清理、关闭开关忽略历史覆盖和恢复默认编写失败的 Vitest 测试，并确认测试因生产行为缺失而失败；随后实现注入式存储逻辑并验证 11 个配置/存储测试通过，包含读写失败回退和清理失败回退。
- [x] 3.4 为共享 Native 调试语义 ID 与平台差异登记补充失败的 presentation 测试，并确认测试因生产行为缺失而失败；随后实现并验证 `packages/presentation/src/index.test.ts` 6 个测试通过。

## 4. 构建与实现

- [x] 4.1 在 `mobile/src/platform/config.ts` 抽取构建地址校验、调试开关解析、当前地址覆盖和恢复默认接口，保持现有 origin 安全规则；配置测试通过。
- [x] 4.2 在现有 `expo-secure-store` 上增加独立的地址覆盖存储适配器和可注入的读写逻辑，不与会话 Token 共用键或清理生命周期。
- [x] 4.3 让 `SessionProvider` 在首次 `session` 请求前恢复地址覆盖，并在登录/注册成功后保存当前有效地址；认证失败、退出登录和存储异常遵循规格语义；生命周期测试通过。
- [x] 4.4 确认既有 `mobileApiClient` 已通过函数形式的动态 `baseUrl` 读取当前 origin；补充环境变量类型声明和 Native README，使所有请求读取当前 origin，且构建开关使用方式可发现。
- [x] 4.5 更新共享 presentation 语义 ID及平台差异登记，不改变 Web 登录页和核心认证操作合同；共享 presentation 测试通过。
- [x] 4.6 更新 Native 登录/注册表单：按开关显示“后端地址”、支持输入校验、“恢复默认”、加载禁用、错误提示和同一字段跨模式保留；Native typecheck 通过。

## 5. 审查

- [x] 5.1 产品/范围复核完成：逐项对照 `proposal.md` 与 spec，确认没有新增登录后设置页、后端协议或 Web 功能；Native-only 差异已登记在共享 platform differences 和 Cross-platform Impact Check。
- [x] 5.2 工程/安全复核完成：origin 注入使用动态 `baseUrl`，Provider 在首个 `session` 前等待恢复，Token 与地址覆盖使用不同存储键，认证失败不写入，SecureStore 失败不撤销成功认证；范围搜索未发现地址日志或额外认证请求字段。未发现 critical/major finding。
- [x] 5.3 Native 设计复核和 Hallmark `audit` 完成，范围为 `mobile/src/app/(auth)/login.tsx`、Native Cobalt 登录卡片和 `prototype/index.html`：字段只在调试构建出现，信息顺序为邮箱/密码/后端地址/认证操作，地址错误贴近字段，恢复默认触控目标为 44 px，Native 输入使用系统焦点与键盘；状态覆盖正常、聚焦、无效、加载/禁用、成功。Finding：critical 0、major 0、minor 0，无需修复或二次审计。
- [x] 5.4 最终 diff 复核完成：artifact 与实现一致，Web 登录组件未改，新增用户可见文案仅为“后端地址”“恢复默认”和校验错误；`git diff --check` 通过。工程复核补充了 `packages/api-client/src/index.test.ts` 的动态 `baseUrl` 回归测试。

## 6. 测试与 QA

- [x] 6.1 验证完成（2026-09-19，工作树 `HEAD=1be64284015c48b05e0e601da8d6e1e2abe717de`）：`npm run test --workspace finance-tracker-mobile` 为 5 files/21 tests passed；`npm run typecheck --workspace finance-tracker-mobile` 通过；`npm exec vitest run packages/presentation/src/index.test.ts packages/api-client/src/index.test.ts` 为 2 files/9 tests passed；随后 `npm run test:shared`（14 tests）与 `npm run typecheck:shared` 通过，`npm run test:web` 为 15 files/145 tests passed，`npm run build:web` 通过。补充动态 `baseUrl` 测试后，`packages/api-client/src/index.test.ts` 3 tests passed。
- [x] 6.2 Android/iOS export 验证完成（2026-09-19）：以下四组命令均退出码 0，Metro 分别产出 Android 2.9 MB、iOS 2.6 MB bundle；环境变量均使用 `EXPO_PUBLIC_FT_API_ORIGIN=https://build.example.com`，分别覆盖 `EXPO_PUBLIC_FT_API_ORIGIN_OVERRIDE_ENABLED=0` 与 `1`：`npm run export:android --workspace finance-tracker-mobile`、`npm run export:ios --workspace finance-tracker-mobile`。仅有 Expo/Node `NO_COLOR` 等非阻断 warning。
- [x] 6.3 Native 主流程已完成可执行范围（2026-09-19 重试）：iPhone 16 模拟器（393×852）在 flag-off Metro（默认 `8081`）下登录页没有“后端地址”字段；在 flag-on Metro 下验证预填构建地址、非法地址阻断及“请输入有效的后端地址。”、恢复默认、登录/注册模式切换保留输入。使用本机临时服务验证成功登录请求命中所选 origin，成功后进入“创建工作区”，退出登录后仍保留调试地址；冷启动恢复后首个 `GET /api/v1/auth/session` 也命中恢复的地址。截图：`/tmp/finance-tracker-mobile-default-hidden.png`、`/tmp/finance-tracker-mobile-debug-login.png`、`/tmp/finance-tracker-mobile-invalid-origin.png`、`/tmp/finance-tracker-mobile-restore.png`。本次 Android 复测使用 `Medium Phone API 36` 模拟器（1080×2400）：先补齐缺失的 NDK `27.1.12297006/source.properties`，再执行 `EXPO_PUBLIC_FT_API_ORIGIN=https://build.example.com EXPO_PUBLIC_FT_API_ORIGIN_OVERRIDE_ENABLED=1 npm run android --workspace finance-tracker-mobile -- --device 'Medium_Phone_API_36'`，Gradle 构建、安装和 Metro 启动均成功。Android flag-on 验证地址校验、恢复默认、登录/注册模式切换，使用 `http://10.0.2.2:8765` 临时服务登录成功并进入“创建工作区”，服务端日志收到 `POST /api/v1/auth/login`；退出登录后地址保留，重新冷启动后地址恢复，临时服务收到首个 `GET /api/v1/auth/session`。截图：`/tmp/finance-tracker-android-debug-login.png`、`/tmp/finance-tracker-android-restore.png`。切换 flag-off Metro 后登录页只显示邮箱、密码和认证操作，不显示“后端地址”或“恢复默认”，截图 `/tmp/finance-tracker-android-default-hidden.png`；首次等待期间的黑屏是 Metro 首包尚未完成，等待 bundle 后复核通过。Android/iOS Native 主流程均已覆盖；仅保留正常 Expo/React Native deprecation warning，无应用异常或红屏。
- [x] 6.4 Native 响应式检查完成可执行范围（2026-09-19 重试）：iPad (A16) 模拟器 `820×1180` 竖屏 flag-on 登录页会显示调试字段，截图 `/tmp/finance-tracker-mobile-tablet.png` 无横向溢出；Android `1080×2400` flag-on/off 截图同样无横向溢出。Native 响应式逻辑测试覆盖 `390`、`768`、`1024`、`1366`，原型静态检查覆盖 `320`、`375`、`390`、`414`、`768`。`mobile/app.json` 明确锁定 portrait，因此 `1440×900` 横向 Native 截图不属于可运行窗口；wide 分支仍由 `1024`/`1366` 逻辑测试覆盖。强制旋转截图 `/tmp/finance-tracker-mobile-tablet-landscape.png` 未作为产品证据使用。
- [x] 6.5 PostgreSQL 双后端矩阵不适用：本变更无数据库、持久化事实源、迁移或服务端行为变化；已由 OpenSpec design 和本任务记录，不替代 Native QA。

## 7. 发布准备与归档

- [x] 7.1 已更新本任务文件，记录实际命令、结果、当前 `HEAD=1be64284015c48b05e0e601da8d6e1e2abe717de`、当前分支 `unlucky-otter`、比较基线为本次变更前同一 `HEAD`、执行时间为 2026-09-19 CST、Native Android/iOS 补测过程、环境修复、日志结果和残余风险。`npm ci` 使用 Node 26.8.1/npm 11.19.0/OpenSpec 1.7.0 完成；安装报告保留 16 个 moderate vulnerability，未执行越权的 audit fix。Android 首次构建仅因本机 NDK 安装不完整失败，补齐 SDK 组件后已成功，不属于代码残余风险。
- [x] 7.2 `openspec validate --all --strict` 与 `openspec doctor` 已通过（同步主规格后为 42/42、Root ok），受影响测试/构建、Native QA 和 `git diff --check` 已通过；delta 已同步到 `openspec/specs/mobile-login-api-origin/spec.md`，同步后重新校验通过。
- [x] 7.3 已使用 `$openspec-archive-change mobile-login-api-origin-override` 归档完整 change；归档不代表提交、推送或发布授权。

## 8. 反思

- [x] 8.1 已沉淀可复用规则：构建地址与调试地址分词；首个 `session` 请求必须在本机覆盖恢复之后；成功认证后才写 SecureStore，失败不覆盖；动态 `baseUrl`、关闭开关不读存储和生产 HTTP 拒绝均有回归测试。Native dev-client 的 shell/profile 与 app-config 构建耦合记录为环境残余风险，不改项目源码范围。
