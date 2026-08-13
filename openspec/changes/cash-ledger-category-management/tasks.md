## 1. 思考

- [x] 1.1 阅读项目上下文、领域词表、收支账本、账本记录、现金流水分类、交易关系主规格，以及现有模型、迁移、Application Service、Web 与测试。
- [x] 1.2 显式运行项目 `/grilling`，确认工作区共享分类目录、最多 5 级、所有节点可分配、关系确认同步、受控删除、显式批量选择和无 AI 行为等产品边界。
- [x] 1.3 识别旧 `category` 混用流水类型和用户分类的根因，确认升级后只由 `record_type` / `record_subtype` 表达系统类型，并更新 `DOMAIN_GLOSSARY.md`。

## 2. 计划

- [x] 2.1 创建独立 change，完成 proposal、五份 delta spec 与 design，覆盖目标、非目标、数据模型、API、事务、并发、SQLite / PostgreSQL 等价、一次性重建、回滚和未来 AI 边界。
- [x] 2.2 使用 Hallmark 沿用现有 Cobalt、Noto Sans SC、IBM Plex Mono 与侧栏，创建 `prototype/index.html`，覆盖分类维护、受控删除、账本筛选、详情修改和批量分类。
- [x] 2.3 根据 UI 文案复核 Flow-Back：在 `AGENTS.md` 增加信息层级与文案门禁，同步 proposal、spec 和 design；删除原型中的实现术语、常驻说明、教学区和重复操作影响。
- [x] 2.4 按用户反馈调整分类页入口：移除页头一级分类按钮，将“新建一级分类”作为分类列表最后一行的可点击项，并约束新建后仍保持在列表末尾。

## 3. 任务拆分与一致性

- [x] 3.1 将分类目录、流水引用、投影派生、关系同步、导入保留、旧字段删除、分类筛选、详情修改和批量分类映射到失败测试、实现、审查与验证任务。
- [x] 3.2 先增加失败测试，覆盖旧 `category` 隐式依赖、一次性数据重建、其他财务字段保留、`manual_overrides` 清理、来源快照保留和投影重建。
- [x] 3.3 建立 SQLite 与真实 PostgreSQL 共享契约夹具，覆盖 5 层树、同级规范化重名、移动子树、工作区隔离、删除并发、关系冲突和批量版本失效。
- [x] 3.4 为 Web 建立失败测试与可访问性断言，覆盖树键盘操作、按需错误、删除确认、父分类筛选、详情修改、显式选择、筛选清空选择和批量失败。

## 4. 构建（测试先行）

- [x] 4.1 在失败迁移测试之后实现 `cash_categories`、`cash_category_states`、`cash_transactions.category_id`、投影派生分类与双后端等价 schema 重建；删除旧 `category` 列和值，不增加兼容逻辑。
- [x] 4.2 在失败仓储测试之后实现稳定分类 ID、ID 路径、深度、同级顺序、规范化名称唯一、同工作区父子约束和目录 `revision`。
- [x] 4.3 在失败 Application Service 测试之后实现分类创建、编辑、移动、排序、删除影响读取和乐观并发；移动在一个事务内更新子树路径。
- [x] 4.4 在失败分类命令测试之后实现单笔与批量分类：校验当前投影版本，解析并去重全部有效现金成员，原子更新 `category_id` 并只发布一次投影版本。
- [x] 4.5 在失败关系测试之后实现关系进入已确认状态时采用展示基准流水分类并同步全部现金成员；待确认、拒绝、拆分和解除遵守 design 规则。
- [x] 4.6 在失败删除并发测试之后实现受控删除：有子分类时拒绝；已使用叶子分类确认后先改为无分类再删除；影响或版本变化时返回最新影响。
- [x] 4.7 删除导入、报表、财富计算、查询和 API 对旧 `category` 类型语义的读取；导入不设置用户分类，重导入保留既有 `category_id`。
- [x] 4.8 实现分类目录、删除影响、单笔 / 批量分类和父分类后代筛选 API；移除旧自由文本分类请求、响应和筛选兼容路径。
- [x] 4.9 以已批准原型实现 `/cash-categories` 和一级导航，复用现有 Web token 与组件，不新增依赖；支持桌面树 + 编辑区和移动列表 + 编辑抽屉。
- [x] 4.10 在收支账本实现分类列、树筛选、详情分类、显式行选择和只包含“修改分类 / 取消选择”的批量栏，并保证用户正常状态无实现说明。

## 5. 审查

- [x] 5.1 完成本会话产品 / 工程设计复核，按严重级记录范围、数据流、并发、迁移、双后端、回滚、测试 finding 及其采纳和回写位置。
- [x] 5.2 对规划原型运行独立 Hallmark 等价审查，专门检查实现术语、重复说明、无效卡片、过度强调、非必要标签、文案预算、无障碍和响应式；修复全部 critical / major 后重新审查。
- [x] 5.3 实施完成后运行产品、工程、安全和最终 diff 独立复核，检查实现与 proposal、spec、design、prototype 偏离及遗漏测试。
- [x] 5.4 对最终生产 UI 运行 `$hallmark audit <target>`，修复全部 critical / major finding 后重新 audit；原型审查不得替代生产 UI 审查。

## 6. 测试与 QA

- [x] 6.1 运行原型 JavaScript 语法检查和静态浏览器 QA；覆盖分类创建外壳、编辑、排序、删除阻止 / 影响、分类筛选、详情修改、批量分类、加载 / 空 / 错误状态，以及 320、375、390、414、768、1440 px 无横向溢出。
- [x] 6.2 生成并人工复核 `prototype/review/` 的 1440 px 与 390 px 分类、编辑和批量截图；运行用户可见实现术语静态搜索、`openspec validate cash-ledger-category-management --strict` 与 `git diff --check`。
- [x] 6.3 运行新增回归、受影响测试、完整 SQLite Application Service / 迁移 / API 契约与完整 Python 回归。
- [x] 6.4 人工准备数据库名以 `_test` 结尾的真实 PostgreSQL，配置 `FT_TEST_POSTGRES_URL` 后运行同一迁移、Application Service、关系、查询与 API 契约矩阵。
- [x] 6.5 运行 Web Vitest、类型检查、生产构建、实现术语静态搜索和 `git diff --check`。
- [x] 6.6 运行生产预览与 Playwright，覆盖主流程、错误 / 空 / 加载、删除并发、批量版本失效、键盘、焦点恢复及 320、375、390、414、768、1440 px。
- [x] 6.7 运行 `openspec validate --all --strict`、`openspec doctor` 和适用性能 / 安全检查，并记录当前 `HEAD`、比较基线、实际命令、结果和残余风险。

### 当前规划与原型证据

- 执行时间：`2026-08-12 19:50 +0800`；当前 `HEAD` 与比较基线均为 `77712be548101d3fb7ee1020c3bc313cd4a4f12a`。
- `node --check openspec/changes/cash-ledger-category-management/prototype/app.js`：通过。
- Playwright 原型 QA（本地 `http://127.0.0.1:4179/`，inline Node 脚本）：分类与账本主路径、分类创建、父分类删除保护、叶子分类影响确认、批量分类、筛选清空选择、详情修改、加载更多、加载 / 空 / 错误状态，以及 320 / 375 / 390 / 414 / 768 / 1440 px 响应式均通过；页面无 console error 或 page error。
- QA 初审发现并修复：移动端继承桌面编辑状态、移动端缺少可见的“新增子分类”入口、加载更多异步回调失效；同时重新生成 1440 px / 390 px review 截图。
- 用户可见实现术语和原型状态控件静态搜索：当前 prototype 未命中门禁词。
- `openspec validate cash-ledger-category-management --strict`：通过；`git diff --check`：通过。
- `openspec validate --all --strict`：20 项通过；`openspec doctor`：通过。
- 产品 / 工程复核结论：0 个 critical、0 个 major、0 个未解决 minor。数据流覆盖分类目录 → 流水引用 → 展示派生 → 关系同步 → 导入保留；并发边界覆盖目录版本、投影版本、删除影响和双后端事务；迁移、备份、恢复及测试缺口均已明确留在后续实施任务。复核期间将移动端入口和批量成功语义回写 `design.md`。
- 本轮 UI 复核结论：一级分类入口已从页头移至分类列表最后一行；该行使用可点击按钮语义，新增一级分类时始终插入其前方，避免入口被新建节点推离列表末尾。
- 本轮验证（2026-08-12 21:52 +0800，`HEAD` `77712be548101d3fb7ee1020c3bc313cd4a4f12a`）：`node --check openspec/changes/cash-ledger-category-management/prototype/app.js` 通过；Playwright 原型回归确认分类页头部没有 `data-add-category` 一级入口，初始与新建后列表最后一项均为“新建一级分类”，新建分类的上级为空；320、375、390、414、768、1440 px 均无横向溢出，且无 console/page error。`git diff --check`、`openspec validate cash-ledger-category-management --strict`、`openspec validate --all --strict` 和 `openspec doctor` 均通过。环境未提供可执行的 `hallmark` CLI，本轮按已读取的 Hallmark audit 门禁完成等价手工复核；无 critical、major 或未解决 minor finding。
- Hallmark 等价审查：环境没有可执行的 `hallmark` CLI，按已读取的 Hallmark 设计 / audit 门禁完成手工复核；覆盖 `prototype/index.html`、`styles.css`、`tokens.css`、`app.js` 和六张 review 截图。初审移除顶部“原型状态”控件、删除确认中的重复解释句，复核后 0 个 critical、0 个 major、0 个 minor；正常页面无实现术语、教学区、装饰性卡片或常驻帮助，移动和桌面截图通过。
- 上述“未运行 Python、数据库、Web 构建或生产预览”是规划阶段的历史记录；实施阶段验证证据见下方“实施与最终审查证据”。

## 7. 发布准备

- [x] 7.1 在 design 记录维护窗口、升级前备份、迁移摘要校验、投影重建、启动顺序、观察项和只能恢复完整备份或前向修复的回滚边界。
- [x] 7.2 实施与完整验证通过后复核发布清单；未经用户明确授权不得提交、推送、创建 PR、合并、部署、迁移真实数据库或操作真实账本。

## 8. 反思

- [x] 8.1 将“UI 不解释内部实现、正常状态文案预算、1440 px / 390 px 截图和独立冗余文案审查”沉淀为仓库级 `AGENTS.md` 门禁。
- [x] 8.2 实施或发布里程碑后记录迁移、分类一致性、删除并发、UI 文案和双后端验证中的可复用结论；归档前同步 delta spec。

### 实施与最终审查证据（2026-08-13）

- 产品 / 工程 / 安全 / 最终 diff 复核：实现覆盖 proposal、五份 delta spec、design 与批准原型；分类目录、流水引用、投影派生、关系同步、删除并发、批量版本绑定、导入保留和一次性删除旧 `category` 列均有实现与回归覆盖。静态扫描发现的 `category` 局部变量仅用于来源账单方向转换或新分类对象，不是旧字段、旧 API 或兼容读取；不做无关重命名。0 个 critical、0 个 major、0 个未解决 minor。
- 生产 UI Hallmark 等价 audit：环境中 `command -v hallmark` 无输出，无法执行 CLI；按已读取 `$hallmark audit` 门禁人工审查分类页、账本页、分类选择器、账本表格、生产样式和视觉快照。检查实现术语、重复说明、无效卡片、过度强调、非必要状态标签、键盘 / 焦点、加载 / 空 / 错误 / 删除确认 / 批量状态和 320、375、390、414、768、1440 px；0 个 critical、0 个 major、0 个未解决 minor。一级分类入口仍为分类列表最后一行，未回到右上角。
- Web 验证：`cd web && npm test -- --run`：53 passed；`npm run build`：通过；`npm run test:e2e -- --reporter=line`：11 passed；`npm run test:visual -- --reporter=line`：12 passed；`npm run test:preview -- --reporter=line`：3 passed。视觉快照因新增分类列 / 新分类 DTO 夹具而更新，并在更新后重新通过。
- Python / SQLite 验证：`uv run pytest -q`：1311 passed、155 skipped，1 个既有 `httpx` / Starlette 弃用警告；迁移、Application Service、API、关系、查询、性能和财富回归均包含在矩阵中。
- OpenSpec / 工程卫生：`openspec validate --all --strict`：20 passed；`openspec doctor`：通过；`git diff --check`：通过；生产代码可见文案实现术语静态搜索：0 命中。当前 `HEAD` 与比较基线均为 `77712be548101d3fb7ee1020c3bc313cd4a4f12a`。
- PostgreSQL 双后端：已完成。使用临时 `postgres:16-alpine` 容器和数据库 `finance_tracker_test`，通过 `FT_TEST_POSTGRES_URL` 与 `FT_REQUIRE_TEST_POSTGRES=1` 显式运行迁移、分类 Application Service、分类 API、投影 parity、Web API、关系、PostgreSQL adapter 和 statement import 矩阵：194 passed、1 个既有 `httpx` / Starlette 弃用警告；验证后已停止并移除临时容器，不接触其他 PostgreSQL 容器或真实账本。
- 发布清单复核：已完成。未执行提交、推送、PR、合并、部署、真实数据库迁移或真实账本写入；当前工作树保留全部实现和验证证据，等待用户明确授权后再进入交付动作。
