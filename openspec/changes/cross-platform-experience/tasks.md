## 1. 思考

- [x] 1.1 阅读 `AGENTS.md`、`openspec/project-context.md`、相关主规格、Expo 迁移 change、`DOMAIN_GLOSSARY.md` 和 `docs/ui-design-rules.md`。
- [x] 1.2 以 Web 为事实源确定第一阶段页面范围、Native 已覆盖范围、未覆盖缺口和 `compact`/`regular`/`wide` 设备矩阵。
- [x] 1.3 在独立 `.worktrees/cross-platform-experience` 中确认基线 `fdb766c`，保留主工作树的既有未提交文件。

## 2. 计划与设计

- [x] 2.1 创建 proposal、`cross-platform-presentation`/`cash-ledger-browser`/`workspace-entry` delta specs 和 design。
- [x] 2.2 完成 Hallmark 预检，确定 Cobalt、`Index-First`、N3 Side-rail、Ft1 session/status footer，并创建 `prototype/index.html`。
- [x] 2.3 更新 `DOMAIN_GLOSSARY.md` 的 `presentation 契约`、响应式布局等级、跨端 parity journey 和平台差异登记术语。
- [x] 2.4 记录 UI 原型状态、验收尺寸、平台差异、Native 缺口和回滚策略。

## 3. 任务拆分与一致性

- [x] 3.1 在 `AGENTS.md` 增加跨平台影响检查规则：Web Compact 与 Native Phone 共享 presentation，Native 大屏对齐 Web，未登记差异视为 defect。
- [x] 3.2 复核 proposal、specs、design、prototype、plan 与本 tasks 的页面范围、状态、响应式阈值和非目标一致。
- [x] 3.3 将跨平台影响矩阵写入本文件：Web、Native、共享包和测试均受影响；分类、投资、工作区管理只登记缺口。

### 跨平台影响矩阵

| 影响区域 | Web | Native | 共享层 | 验证 |
|----------|-----|--------|--------|------|
| 认证与工作区入口 | 共享文案、入口区域和语义 ID | 共享文案、自动选择和窗口布局 | `copy`、screen contract、semantic ID | Web / Mobile 契约测试 |
| 收支账本 | 共享标题、筛选/列表/详情入口和响应式检查点 | 共享标题、筛选、列表、空/错误状态和 `testID` | `ledger` contract、responsive tier | Web Vitest / Playwright、Mobile test |
| 手工记账与凭证 | 共享字段、保存/取消和详情区域标识 | 共享字段、保存/返回和详情区域标识 | `record` contract、platform differences | 受影响单测与类型检查 |
| 账单导入与关系审查 | 共享步骤、状态和关键操作标识 | 共享步骤、状态和关键操作标识 | `import` contract、parity journeys | Web / Mobile 受影响测试 |
| 分类、投资、工作区管理 | 保留 Web 既有能力 | 登记为暂未覆盖，不伪造入口 | 平台差异与缺口记录 | parity review |

## 4. 构建：共享契约与 Web

- [x] 4.1 添加 `packages/presentation` workspace manifest，并先添加会因契约缺失而失败的单元测试。
- [x] 4.2 实现 Web 事实源 copy、screen regions/actions/states、`compact`/`regular`/`wide` classifier、stable semantic IDs、platform differences 和 parity journeys。
- [x] 4.3 扩展 `packages/design-tokens` 的响应式/component tokens，并验证最小触控目标、移动单列和表单宽度约束。
- [x] 4.4 将 Web 第一阶段页面的共享文案和关键区域/操作接入 presentation；保留 Web 行为、路由、键盘和 DOM 控件。
- [x] 4.5 添加 Web `data-testid` 和 Compact/Regular/Wide parity hooks，覆盖登录、工作区、账本、记账/凭证、导入/关系审查。

## 5. 构建：Expo Native 重写

- [x] 5.1 为 Native layout classifier、Shell regions 和 shared `testID` 添加失败回归测试。
- [x] 5.2 重写 `NativeShell` 的 safe-area、compact 单列、regular 密度和 wide Web-like side rail/content frame；不得按设备型号分支。
- [x] 5.3 重写登录/注册与工作区选择/创建页面，接入 Web copy、状态、操作顺序和语义 ID。
- [x] 5.4 重写收支账本页面，接入 Web 的标题、筛选/列表/空/错误/加载、账户/金额/来源/详情和新增/导入区域。
- [x] 5.5 重写记账和凭证详情页面，保持权限、字段、保存/取消/失败/成功转换及服务端金额字符串语义。
- [x] 5.6 重写账单选择、密码、账户映射、流水核对、关系审查和导入完成页面，保持 Web 阶段顺序、关系决定和幂等写入语义。
- [x] 5.7 对分类管理、投资账本和工作区管理保持显式暂不可用/无入口状态，不创建伪 Native 业务实现。

## 6. 审查

- [x] 6.1 完成产品/范围复核：Web 事实源、第一阶段范围、未覆盖缺口和成功标准无偏离。
- [x] 6.2 完成工程复核：共享包无平台副作用，Native 不复制领域计算，不改变 API/数据库/权限/幂等边界。
- [x] 6.3 完成设计/响应式复核：信息层级、状态、无障碍、safe-area、320/375/390/414/600/768/800/1024/1440 无横向滚动。
- [x] 6.4 完成跨端 parity review：区域、文案、操作、状态转换、业务结果逐屏对照；所有例外都有平台差异登记和 invariant。
- [x] 6.5 对最终 UI 执行 Hallmark `audit`，记录 target、输出、finding 严重级别、采纳/拒绝理由；修复 critical/major 后重新 audit。
- [x] 6.6 完成最终 diff review，检查 artifact 偏离、遗漏测试、缺口误实现、依赖变化和回滚边界。

## 7. 测试、QA 与发布准备

- [x] 7.1 运行 presentation/design-tokens/core/contracts/api-client 受影响测试与类型检查，记录先红后绿证据。
- [x] 7.2 运行 Web Vitest、构建和生产预览；用真实浏览器执行 320、375、390、414、768、1024、1440 视口的主流程、正常/空/错误、关键点击、键盘焦点和横向滚动检查。
- [x] 7.3 运行 Mobile Vitest、typecheck、Android/iOS export；在可用 Android/iOS 模拟器或真机验证 phone/tablet/large。无法运行时记录准确阻断错误和补跑条件。
- [x] 7.4 本变更单独验证时记录当前 `HEAD`、比较基线、实际命令、时间、URL、视口、截图路径、控制台/网络错误、未解决风险和回滚说明；两个变更已在同一 feature 工作树完成联合验收，证据见下文。
- [x] 7.5 运行 `openspec validate --all --strict`、`openspec doctor`、`git diff --check` 和本变更相称的回归；数据库/PostgreSQL 矩阵记录为不适用（无存储行为变化）。
- [ ] 7.6 本变更单独验证通过后，只提交直接相关文件到现有 `feat/cross-platform-experience`；待另一个 change 合入后，在同一 feature 分支完成一次联合验收，再推送并创建指向 `refactor/web` 的 PR。与本变更无关的脏文件不纳入、不删除。

## 8. 反思

- [x] 8.1 记录本阶段发现的可复用跨端规则、未登记差异和需要后续 change 的 Native 缺口。
- [x] 8.2 评估是否需要把 parity journey、Native UI E2E runner、分类/投资/工作区管理纳入下一阶段，不在本 change 偷渡范围。

## 验证证据（独立阶段）

- 基线：`fdb766cd02e0eed7f88d7cea960b966c49963f05`；本阶段验证在 `feat/cross-platform-experience` 工作树进行；联合验证须在质量门禁变更合入后重跑。
- 已通过：`npm run test:shared`（contracts 1、api-client 2、core 4、presentation 5）、`npm run typecheck:shared`、`npm run test --workspace finance-tracker-mobile`（8 tests）、`npm run typecheck --workspace finance-tracker-mobile`、`npm run build:web`、`openspec validate cross-platform-experience --type change --strict`、`openspec doctor`、`git diff --check`。
- 已通过：`npm run export:android --workspace finance-tracker-mobile` 与 `npm run export:ios --workspace finance-tracker-mobile`，均生成 Expo `dist` bundle；本环境未连接 Android/iOS 模拟器或真机，因此未宣称设备级 UI 通过，补跑条件为可用设备/模拟器。
- Web 全量 Vitest 在 Node `v26.8.1`、尚未合入 `add-pr-quality-gates` 的隔离工作树中为 `96 passed / 48 failed`；失败均为 jsdom 在未设置 `--localstorage-file` 时没有 `localStorage`，集中于既有 `AccessApp`、`InvestmentLedgerPage`、`api-access` 测试，新增 `presentation-parity` 已通过。联合验证将使用质量门禁变更中的共享 jsdom storage setup 重跑。
- 真实浏览器：生产预览 `http://127.0.0.1:4173/`，后端临时 SQLite `http://127.0.0.1:8000`；完成注册、创建工作区、空账本查看、打开/关闭记账抽屉、导入入口与禁用下一步检查；390 px 检查键盘可聚焦控件与禁用保存状态。视口 `320/375/390/414/768/1024/1440` 均无横向滚动（`scrollWidth === clientWidth`），导入主流程和空状态通过，清空控制台后无控制台错误；初始未认证请求的 `401` 为预期会话边界。截图：`/tmp/cross-platform-experience-web-320.png`、`-375.png`、`-390.png`、`-414.png`、`-768.png`、`-1024.png`、`-1440.png`、`/tmp/cross-platform-experience-web-ledger-390.png`、`-record-390.png`、`-import-390.png`。
- Hallmark runtime 当前无可调用的 `audit` action；按同一审查维度完成人工等价复核，target 为上述生产预览认证工作区及 `openspec/changes/cross-platform-experience/prototype/index.html`。检查了 Cobalt token、N3 rail/compact menu、空/错误/禁用/焦点状态、safe-area、触控目标和 required viewports，未发现 critical/major；不将其表述为工具已执行。
- 范围审查结论：只保留本 change 直接相关的 mobile/web/presentation/design-token/package/OpenSpec 文件；`docs/superpowers/` 下的既有计划/spec 未纳入，工作树中其他脏文件不删除。回滚为按 feature commit 回退共享 presentation/UI 适配提交，既有后端/API/数据库不变。

## 联合验收证据（2026-09-14，Asia/Shanghai）

- 联合 feature：`feat/cross-platform-experience`，跨平台提交 `0b770a5`；比较基线 `refactor/web` / `fdb766cd02e0eed7f88d7cea960b966c49963f05`。质量门禁直接文件已应用但尚未形成最终提交；最终 `HEAD`、推送和 PR 信息由 `add-pr-quality-gates` 的发布任务记录。
- 前端联合结果：`npm run test:shared` 通过（contracts 1、api-client 2、core 4、presentation 5）；`npm run typecheck:shared` 通过；`npm run test:web` 为 `15 files / 144 tests passed`；`npm run build:web` 通过；E2E `38 passed`；生产预览 `11 passed`；视觉回归 `15 passed`。共享文案将账本错误态操作从“重试”统一为“重新读取”，因此额外更新直接相关的 `web/tests/cash-ledger.visual.e2e.ts-snapshots/cash-ledger-error-darwin.png`，未放宽阈值或跳过测试。
- 后端联合结果：SQLite 功能套件 `1525 passed, 182 skipped`，compileall 通过；专用 `finance_tracker_test` PostgreSQL 容器（`postgres:16-alpine`，主机端口 55432，数据库名以 `_test` 结尾）功能套件 `1731 passed, 2 skipped, 2 failed`。失败为既有分类目录 p95（本机 316.9ms > 250ms）和 100k 投影性能样本中的 SQLite 夹具状态/性能波动；两项分类/投资性能测试单独复跑通过，PostgreSQL 独立性能参数通过，SQLite 独立 100k 重建在本机两次 p95 为 5.664s/9.293s（预算 5s）。未修改预算或无关性能实现；远程 Linux CI 仍是最终性能门禁。
- 联合浏览器/设计证据沿用本变更独立阶段的真实浏览器记录：`http://127.0.0.1:4173/`、临时 SQLite 后端、`320/375/390/414/768/1024/1440`；截图仍位于 `/tmp/cross-platform-experience-web-*.png`。生产预览/E2E/视觉重跑无新增控制台或网络错误；设备级 Android/iOS UI 和 Hallmark runtime `audit` 仍分别受无可用设备、无可调用 action 阻断，已记录人工等价审查和补跑条件。
