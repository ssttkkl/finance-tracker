## 1. 思考与计划

- [x] 1.1 基于远端 `origin/refactor/web` 最新 `381b486` 复核 `CashImportPage`、收支账本 `CashTable`、时间格式化和现有预览测试；记录比较基线和工作树状态。
- [x] 1.2 完成 `grill-me` `/grilling` 需求澄清记录：目标、范围、非目标、字段删减、时间边界、响应式断点、风险和验收标准已写入 proposal / design / spec。
- [x] 1.3 按 `docs/ui-design-rules.md` 和 Hallmark 规则确定 `Index-First / Import Preview Grid`、Cobalt token、桌面固定列与小屏卡片策略。

## 2. 原型与确认门禁

- [x] 2.1 创建独立原型 `prototype/index.html` 和 `prototype/tokens.css`，表达正常状态、月份分组、双行交易信息、中文类型、合并金额、状态摘要与操作栏。
- [x] 2.2 在原型中覆盖加载、空、错误、确认中、完成、禁用和焦点示例，并确保原型不依赖生产路由、后端或网络服务。
- [x] 2.3 使用真实浏览器检查原型在 320、375、390、414、768、1440 px 无横向溢出、按钮不换行、长文本不遮挡金额；保存 1440 / 390 截图路径。
- [x] 2.4 运行 Hallmark 自检 / slop checklist，记录预检、反模式 finding、可见文字必要性和 prototype 修复结论。
- [x] 2.5 将原型路径、设计选择、状态覆盖、响应式检查写回 `design.md`；用户已确认原型，允许进入生产 Web UI 实现。

## 3. 测试先行与生产实现（用户确认原型后）

- [x] 3.1 先更新 `web/tests/CashImportPage.test.tsx` 失败回归：可读时间、月份分组、双行交易信息、中文类型、无分类 / 内部枚举、金额币种合并、状态摘要和状态行。
- [x] 3.2 先更新或新增预览表格组件测试：正常、加载、空、错误、确认中、成功、键盘焦点与禁用操作均有可访问名称和稳定结构。
- [x] 3.3 实现固定业务字段的导入预览表格 / 行模型和展示格式化，复用现有时间、状态和流水类型语义，不改变 API 请求和确认逻辑。
- [x] 3.4 实现桌面固定列比例、月分组摘要、方向金额色和双行交易信息；移除分类、独立币种、对方账号等预览列渲染。
- [x] 3.5 实现 820 px 以下记录卡片布局，覆盖 320、375、390、414、768 px，保持页面级无横向滚动和可操作文字单行。

## 4. 审查与验证

- [x] 4.1 完成独立范围 / 产品复核：只改变导入预览可见层级，删除项均为当前核对阶段冗余信息，确认动作和状态合同保留。
- [x] 4.2 完成工程 / 安全 / 时间语义复核：无 API、持久化、金额、分类写入、来源快照或内部枚举泄露回归。
- [x] 4.3 按仓库门禁审查最终 `CashImportPage` / 预览视图和样式；当前运行时无独立 Hallmark `audit` 动作入口，已完成人工等价审查，未发现 critical / major finding。
- [x] 4.4 运行受影响 Vitest、TypeScript 检查、生产构建、`git diff --check`；代码变化后已重新运行受影响浏览器 QA。
- [x] 4.5 使用生产预览真实浏览器走通第三步正常 / 加载 / 空 / 错误 / 成功与第四步确认入口，检查键盘焦点、控制台、网络和 320 / 375 / 390 / 414 / 768 / 1440 px；记录 1440 / 390 截图。
- [x] 4.6 运行 `openspec validate redesign-import-preview-table --strict`、`openspec validate --all --strict` 和 `openspec doctor`，回写当前 `HEAD`、比较基线、命令、结果、未解决风险与回滚准备。

## 5. 发布与反思

- [x] 5.1 记录无迁移发布、预览 UI 回滚步骤和观察项；未获明确授权前不提交、推送、创建 PR、合并或部署。
- [x] 5.2 记录可复用防复发规则：导入预览按业务字段白名单渲染，分类不在未分类阶段展示，时间 / 月份必须沿用浏览器本地展示边界。

## 原型阶段证据

- 基线：`HEAD` 为 `381b48660fb33ccb794f7e3392c0cdf4cbb0ce3b`，已通过 `git fetch origin refactor/web` 与 `git merge --ff-only origin/refactor/web` 对齐远端最新 `refactor/web`；比较基线为用户指定的该远端提交，工作树除本变更原型文件外无其他修改。
- OpenSpec：`openspec --version` → `1.7.0`；`openspec validate redesign-import-preview-table --strict` → 通过；`openspec doctor` → Root / OpenSpec root ok；`git diff --check` → 通过。
- 原型检查：使用仓库 Web 依赖安装的 Playwright、系统 Google Chrome 和本地静态服务器 `python3 -m http.server 8765 --bind 127.0.0.1` 打开 `http://127.0.0.1:8765/prototype/index.html`，检查 320、375、390、414、768、1440 px。每个宽度 `document.documentElement.scrollWidth === window.innerWidth`、`body.scrollWidth === window.innerWidth`，可见按钮均未换行；控制台 / 页面错误为 0。
- 状态检查：真实点击“加载中 / 空状态 / 错误 / 完成 / 正常”状态按钮，确认每次只有对应面板可见；聚焦“焦点”按钮得到 3 px 可见焦点环；“确认导入”禁用状态可见。截图：`/tmp/ft-import-preview-prototype-1440.png`、`/tmp/ft-import-preview-prototype-390.png`。
- Hallmark：沿用项目缓存的 Cobalt / Noto Sans SC / IBM Plex Mono 预检；采用 `Index-First`（区别于现有 `Workbench / Ledger Grid`），无装饰图像、无额外字体、无渐变背景、无卡片套卡片、无长文案教学区。原型中的状态切换控件是审查工具，不进入生产 UI。
- 内置浏览器：Paseo 浏览器连接发现为空，按浏览器技能排查后未执行成功；以上 Playwright + 本机 Chrome 为等价替代证据。原型阶段生产页面浏览器 QA 尚未开始，已在实施阶段补跑，证据见下文。
- 当前门禁：用户已确认原型，任务 `2.5` 已完成；第 3–5 组进入实施、审查与验证阶段。

## 实施阶段证据

- 用户确认：用户已确认原型，并明确要求导入预览与收支账本复用统一表格组件；决定已回写 `design.md`。
- 实现范围：新增 `web/src/components/TransactionTable.tsx`；`CashTable.tsx` 改为收支账本适配器；`CashImportPage.tsx` 按固定业务字段白名单映射导入行；`styles.css` 删除旧的 1250px 宽表规则并补充共享桌面 / 小屏布局。未修改 API、请求参数、确认导入合同、分类写入或数据库。
- 测试先行：目标失败回归先在旧宽表上失败；实现后 `cd web && npm test -- --run tests/CashImportPage.test.tsx tests/CashTable.test.tsx` → 34/34 通过。新增空状态、共享组件加载骨架、月分组、双行交易信息、中文流水类型、金额方向和状态摘要断言。
- Web 验证：`cd web && npm test` → 11 个测试文件 / 130 个测试通过；`npx tsc --noEmit` → 通过；`npm run build` → 通过；`git diff --check` → 通过。
- 浏览器 QA：`cd web && npx playwright test tests/cash-ledger.e2e.ts -g "独立导入处理页面扫描账户并完成四步确认"` → 通过；同文件 `-g "导入预览加载|独立导入处理页面扫描账户并完成四步确认"` → 2/2 通过；覆盖真实生产预览、正常 / 加载 / 空 / 错误 / 完成、确认入口、焦点和禁用步骤按钮，控制台错误为 0，所有按钮未换行，页面级无横向滚动。宽度：320、375、390、414、768、1440 px。截图：`/tmp/cash-import-preview-production-1440.png`、`/tmp/cash-import-preview-production-390.png`。
- Hallmark 审查：审查目标为 `CashImportPage`、`TransactionTable`、`CashTable` 和最终共享样式及上述两张截图；检查 Cobalt token、中文字体、Index-First 列表节奏、无渐变 / 无卡片套卡片、文案必要性、键盘焦点、长文本截断和响应式边界。运行时没有独立 `audit` action，按等价人工清单审查，finding 为 0；修复了月份分组 `colSpan` 漏含金额列和桌面发生时间过窄两个 minor finding。
- 全量 E2E：`cd web && npm run test:e2e` → 34/35 通过；唯一失败为远端基线已有的 `暗色模式下侧栏导航文字保持可读`，测试固定期待 7 个侧栏元素但实际返回 8 个，失败位置为 `web/tests/cash-category-management.e2e.ts:423`；本变更未修改侧栏、分类管理页面或该测试，保留为范围外残余风险。
- OpenSpec：`openspec validate redesign-import-preview-table --strict`、`openspec validate --all --strict` → 分别通过、32/32 通过；`openspec doctor` → Root / OpenSpec root ok；当前 `HEAD` 为 `381b48660fb33ccb794f7e3392c0cdf4cbb0ce3b`，比较基线同为用户指定的 `origin/refactor/web` 最新提交。未获提交 / 推送 / PR / 合并 / 部署授权，当前仅保留工作树改动。
- 发布与回滚：无需数据迁移；回滚时恢复 `CashImportPage` 对 `TransactionTable` 的使用、`CashTable` 适配改动、共享样式和对应测试即可，导入会话和数据库无需回迁。观察项为生产环境长文本、跨浏览器本地月份边界和导入状态摘要与后端数量一致性。

## 反思与防复发规则

- 导入预览只允许按业务字段白名单组装共享行模型，不得再次直接遍历 `preview.columns`。
- 分类在导入确认前不是核对字段，不在导入预览首屏展示；状态和流水类型必须通过显式中文映射，不能输出内部枚举。
- 时间格式与月份分组必须复用浏览器本地展示边界；月汇总保持字符串精度，不用二进制浮点计算金额。
