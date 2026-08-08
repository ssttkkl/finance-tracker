# Tasks

## 0. 需求与流程门禁

- [x] 0.1 在任何实现前显式调用 `grill-me` 技能的 `/grilling` 动作（不是 shell 命令），并把用户、目标、范围、非目标、验收标准、边界和风险写入本 change；本轮已完成对话澄清和 artifact 记录。
- [x] 0.2 先阅读 `$domain-glossary`、`DOMAIN_GLOSSARY.md` 与 `$chinese-documentation`，区分内部术语和用户术语，形成“内部字段保留、用户界面简化”的文案边界。
- [x] 0.3 选择并读取适用的 OpenSpec、Hallmark、测试与验证技能，确认本次为 A 类 UI 变更，并检查当前工作树、基线、active change 和外部写授权边界。
- [ ] 0.4 在开始代码前完成 proposal、spec、design、tasks 与原型的相互核对；原始 change 满足了实现前核对，但本轮用户反馈是在已有实现后回流，未满足“先回写 artifact、再实现”的顺序门禁。
- [x] 0.5 调用 `grill-me` 技能的 `/grilling` 动作，澄清轮询、手动刷新、均摊成本、负成本、周期表现、买卖与出入金口径、合并规则、总览分母和浏览器偏好，并将结论回写 proposal、spec、design、tasks 与词表。
- [x] 0.6 调用 `grill-me` 技能的 `/grilling` 动作确认删除边界：不单独展示已卖出部分的盈亏，不预留独立“已实现盈亏”界面字段；周期合计仍包含浮盈亏与期间已实现盈亏。

## 1. 思考

- [x] 1.1 阅读 `investment-event-model`、`ledger-records`、`portfolio-valuation`、`time-semantics`、现有收支账本 Web、投资查询代码和测试，确认当前行为与缺口。
- [x] 1.2 使用 `$openspec-explore` 复核核心任务、范围、失败模式、数据隐私和不改变投资事实的边界。
- [x] 1.3 以首次使用且不了解内部模型的用户视角逐项扫查两页原型，识别过度解释、内部术语、重复指标、不可自由输入的币种控件和不必要更新时间卡片。

## 2. 计划

- [x] 2.1 复核 proposal、delta spec 与 design 的一致性，明确 API、数据流、分页、局部失败、兼容、回滚和安全策略。
- [x] 2.2 使用 Hallmark 创建 `prototype/index.html`，覆盖事件、持仓、筛选、加载、空、错误、行情受限和证据详情，并完成 320、375、414、768 px 检查与设计复核。
- [x] 2.3 根据用户反馈回流设计：去除用户界面的内部关系、投影、渠道、规则和状态枚举，压缩说明文字，保持当前持仓与投资事件的清晰语义边界，并同步原型与 `design.md`。
- [x] 2.4 根据本轮反馈更新原型：将当前持仓和投资事件改为两个独立页面，使用收支账本/投资账本一级导航与当前持仓/投资事件二级导航；持仓页覆盖总览盈亏、表现周期、账户/排序/合并/币种设置、10 秒轮询和手动刷新，事件页覆盖筛选、分页和详情；完成双页面 Hallmark 审查。
- [x] 2.5 根据本轮反馈再次更新当前持仓原型：删除更新时间和指标解释句，以刷新按钮圆形动画表达请求中状态，币种改为可输入控件，卡片显示“当前市值 / 仓位”，总览显示所选时间范围的浮盈亏；重新完成 Hallmark 审查。
- [x] 2.6 根据用户确认重新设计当前持仓展示：总览顺序改为总浮盈亏、近 24 小时盈亏、当前总市值；桌面端改为固定列顺序的持仓表格，手机端降级为保留全部字段的持仓卡片；重新完成 Hallmark 审查。

## 3. 任务拆分与一致性

- [x] 3.1 为每条 requirement 和 scenario 建立失败测试、实现、审查与验证任务映射，确认原型、proposal、spec 和 design 无矛盾。
- [x] 3.2 固定 SQLite 与真实 PostgreSQL 契约夹具、工作区隔离样例、精确十进制样例、版本化分页和行情局部失败验收数据。
- [x] 3.3 为用户术语建立回归断言：渲染结果不得出现内部枚举、规则编号或技术标题；详情、空、错误、键盘和响应式状态仍保持可访问。
- [x] 3.4 为负成本、均摊合并、跨币种禁止合并、周期资产流量、买卖/出入金区分、总览分母和 10 秒轮询建立失败测试与契约映射。
- [x] 3.5 为仓位含现金分母、币种自由输入及无效值恢复、刷新图标忙碌/减少动态效果、总览无解释句、用户可见禁用术语和不拆分周期盈亏建立失败测试与 requirement 映射。
- [x] 3.6 为周期总额包含浮盈亏与已实现盈亏、完全卖出标的计入总额、单标的资金流调整、内部账户转移排除和带时间权重的盈亏率建立失败测试与 requirement 映射。

## 4. 构建

- [x] 4.1 先增加失败测试，再实现投资事件筛选、稳定分页和当前页批量关系摘要的 Application Service 与 persistence adapter。
- [x] 4.2 先增加失败测试，再实现工作区隔离的投资事件证据详情与脱敏 Web API。
- [x] 4.3 先增加失败测试，再复用组合查询实现当前持仓与有界估值状态响应，确保局部行情失败不阻塞事件列表。
- [x] 4.4 以已批准原型实现投资事件、当前持仓、筛选、分页和证据详情 Web 交互，保持十进制字符串和浏览器本地时间语义。
- [x] 4.5 根据本轮反馈将当前持仓从逐卡片 `quote_status` 展示改为整体成功/失败状态，并保留内部诊断字段只供查询和日志使用。
- [x] 4.6 按简化文案策略实现用户界面：事件类型使用“买入 / 卖出”等短标签，详情使用“资金流向 / 更多信息”，收支页使用“相关记录”，隐藏导入渠道、来源字段名和规则编号；不改变 API、查询或账务语义。
- [x] 4.7 先增加失败测试，再实现表现查询：当前/边界资产值、24 小时和可选周期、买卖影响、外部出入金扣除、浮盈亏与已实现盈亏合计、完全卖出标的、单标的资金流调整、精确十进制比率与负成本。
- [x] 4.8 先增加失败测试，再实现持仓分组、账户筛选、标的排序、同标的合并/禁止跨成本币种合并、原始/统一展示币种和总览口径。
- [x] 4.9 先增加失败测试，再实现 10 秒可见性轮询、手动刷新、取消过期请求、首次失败与后续失败恢复，以及浏览器本地偏好还原。
- [x] 4.10 以更新后的双页面原型实现当前持仓页和投资事件页：持仓页包含总览、表格、移动端卡片、周期切换、展示设置、刷新按钮和统一成功/失败状态；事件页包含筛选、分页和详情；不暴露覆盖状态枚举。
- [x] 4.11 以失败测试驱动用户界面精简：将“均摊成本 / 表现周期”映射为“平均成本 / 时间范围”，删除更新时间卡和指标辅助句，实现可输入币种、刷新按钮圆形忙碌反馈及“当前市值 / 仓位”组合字段。
- [x] 4.12 为浏览器持仓偏好增加兼容读取：旧版下拉币种值可迁移为输入值，无效旧值回退到原币种，且不影响账户、排序、合并和时间范围偏好。
- [x] 4.13 以失败测试驱动持仓展示重设计：桌面端使用 9 列持仓表格，浮盈亏四列独立展示且字号一致、通过字重和颜色突出；820 px 及以下将表格行转换为保留全部字段的持仓卡片。

## 5. 审查

- [x] 5.1 完成产品与范围复核，检查核心任务、非目标、成功标准和未实现行为没有被提前写入主规格。
- [x] 5.2 完成工程与安全复核，检查查询边界、N+1、预算、分页一致性、工作区隔离、证据最小披露、兼容和回滚。
- [x] 5.3 已调用 Hallmark 的 `audit` 技能动作审查生产投资页面及 `prototype/index.html`、`prototype/events.html`，按 audit 规则记录并修复 finding，再用 Playwright、原型检查和预览复核；这里的 audit 是技能动作，不是 shell 命令。
- [x] 5.4 完成最终 diff 复核，按严重级别记录 finding、处理结论和 artifact 回写位置。
- [x] 5.5 完成面向新用户的文案审查：逐项检查页面、抽屉、按钮、空/错/加载状态和无障碍名称，确认自解释、低阅读负担且不暴露内部模型；将用户反馈回写到 `proposal.md`、`design.md`、`tasks.md` 和原型。
- [x] 5.6 对用户确认后实现的最终生产 UI 调用 Hallmark `audit` 技能动作，覆盖指标层级、设置负担、成功/失败状态、刷新反馈、响应式和可访问性；按严重级别处理并重新复核。
- [x] 5.7 对更新后的双页原型执行新用户文案审查，逐项确认可见文本只保留业务结果和动作；重点检查平均成本、时间范围、币种输入、仓位、当前浮盈亏、周期浮盈亏以及错误状态，不用解释句替代清晰层级。
- [x] 5.8 删除已卖出部分的独立盈亏后，重新调用 Hallmark `audit` 技能动作审查双页原型；结果为 0 critical、0 major、0 minor。
- [x] 5.9 对新的持仓表格/手机卡片执行 Hallmark `audit` 技能动作，检查列顺序、信息层次、无横向滚动、可访问表头与数值对齐。

## 6. 测试与 QA

- [x] 6.1 运行新增回归、受影响测试、SQLite 与 Docker PostgreSQL 契约矩阵、完整 Python 回归、Web Vitest、构建和 `git diff --check`；真实 PostgreSQL 使用专用 `_test` 数据库执行。
- [x] 6.2 运行生产预览与 Playwright，覆盖主流程、加载、空、错误、行情受限、键盘、焦点恢复和 320、375、414、768 px 响应式行为。
- [x] 6.3 运行 `openspec validate --all --strict` 与 `openspec doctor`，记录当前 `HEAD`、比较基线、实际命令、结果和残余风险。
- [x] 6.4 重新验证页面顺序、中文估值状态文案和原型状态说明。
- [x] 6.5 重新运行用户可见术语回归与原型检查，覆盖投资页、共享收支详情、320、375、414、768 px 及键盘焦点恢复。
- [x] 6.6 验证 10 秒轮询节奏、可见性暂停/恢复、手动刷新、浏览器偏好持久化、周期边界和多币种总览；记录真实 PostgreSQL 条件与未解决风险。
- [x] 6.7 验证刷新动画只在请求中出现且尊重减少动态效果、币种输入可编辑并能恢复无效值、仓位分母包含现金、总览数字顺序正确、旧偏好兼容，以及渲染结果不含禁用术语或冗余解释句。
- [x] 6.8 对更新后的双页原型完成只读验证：320、375、414、768 px 无横向溢出；手动与 10 秒自动刷新状态正确；减少动态效果下不旋转；币种支持自由输入、大写规范、无效值与留空；设置开合文案、投资详情 Esc 关闭与焦点恢复正确；渲染文本不含禁用术语和冗余解释句。
- [x] 6.9 验证双页原型和 change 用户合同不再包含已卖出部分的独立盈亏字段、旧演示值或成对展示样式；周期总览显示所选时间范围内浮盈亏与已实现盈亏的合计金额和比率。
- [x] 6.10 验证新的总览顺序、9 列桌面表格、手机卡片降级、全部字段保留、浮盈亏四列独立展示、等字号层次、数值对齐及 320/375/414/768 px 响应式行为。

## 7. 发布准备

- [x] 7.1 记录路由与 API 回滚、观察项和未解决风险；未经用户明确授权不提交、不推送、不创建 PR、不部署。
- [x] 7.2 完成交付交接记录：列出改动文件、验证命令、基线、残余风险、未执行项、下一步 archive 条件和外部写授权边界。
- [x] 7.3 发布准备时记录浏览器偏好键兼容与回滚方式、轮询失败观察项，以及移除旧占位字段或文案的兼容检查。

## 8. 反思

- [ ] 8.1 归档前同步 delta；沉淀投资浏览查询、证据安全、局部失败和 UI 验证中的可复用规则（已完成规则沉淀；主规格同步与 archive 待用户确认）。
- [x] 8.2 在最终 UI 通过后沉淀“用户界面只显示结果与动作、精确口径留在规格/无障碍语义”的文案规则，并记录防止内部术语和解释句回归的测试位置。

## 执行记录与审查结论

- **需求澄清门禁**：已调用 `grill-me` 技能的 `/grilling` 动作完成需求澄清，将用户、目标、范围、非目标、验收、边界和风险写入 proposal/design/tasks；这是技能调用，不是 shell 命令。
- **流程补录**：本次确实调用了 `grill-me`、术语与中文文案复核、Hallmark `audit` 技能动作、artifact 更新、用户反馈回流、用户可见术语审查、测试、QA 和交付交接；0.4 仍明确记录本轮反馈回流发生在已有实现之后，不能冒充为实现前门禁。
- **本轮需求回流**：用户确认 10 秒轮询、手动刷新、负成本均摊法、买卖与出入金区分的周期盈亏、可切换表现周期、同标的合并规则、投资标的/现金总览分母和浏览器本地偏好；已更新 proposal、delta spec、design、tasks 与 `DOMAIN_GLOSSARY.md`，尚未开始本轮代码实现。
- **本轮原型与 Hallmark 审查**：仅更新 `openspec/changes/investment-ledger-browser/prototype/index.html`、`prototype/events.html` 与 change artifacts，未修改生产代码。当前持仓页删除更新时间卡、轮询解释句和总览口径说明；刷新按钮以圆形动画表达请求中状态；币种改为自由输入；卡片显示“当前市值 / 仓位”；总览显示所选时间范围的浮盈亏金额和比率。Hallmark `audit` 技能动作首轮报告 **0 critical、2 major、2 minor**：持仓突出金额缺少“当前浮盈亏”标签、设置重新展开时的自动化断言竞态、320 px 总览仍为双列且 768 px 周期指标形成半行、币种小写未在失焦时规范。复核后确认设置文案问题是原生 `toggle` 事件尚未稳定的测试竞态，不属于 UI finding；其余三项均修复。最终复审为 **0 critical、0 major、0 minor**。生产 UI 的实现与 Hallmark 审查仍等待用户确认原型。
- **本轮信息架构回流**：用户要求将当前持仓和投资事件拆为两个独立页面，并明确使用两级侧边导航：一级为收支账本/投资账本，二级为当前持仓/投资事件；已将 proposal、spec、design、tasks 的页面边界改为该结构。本轮先重做双页面原型并重新执行 Hallmark 审查，生产 UI 仍等待用户确认。
- **本轮指标与文案回流**：用户再次要求以不了解内部模型的新用户为默认读者，删除更新时间和总览解释句，刷新中状态改由按钮圆形动画表达，币种改为可输入控件，卡片新增含现金分母的仓位。用户随后决定不做已卖出部分的独立盈亏；总览只保留时间范围内的浮盈亏，相关口径门禁、占位和实现任务均已删除。
- **取消独立已卖出盈亏**：已调用 `grill-me` 技能的 `/grilling` 动作确认删除边界，并同步 proposal、delta spec、design、tasks、`DOMAIN_GLOSSARY.md` 与持仓原型。原型从成对指标改为“近 24 小时浮盈亏 / +86.40 USD / +0.61%”；不预留对应 API 字段或实现任务。随后重新执行 Hallmark `audit` 技能动作，结果为 **0 critical、0 major、0 minor**。
- **本轮表格重设计回流**：用户确认总览顺序为“总浮盈亏 → 近 24 小时盈亏 → 当前总市值”，并确认桌面端 9 列持仓表格的固定顺序；浮盈亏、浮盈亏率、近 24 小时盈亏、近 24 小时盈亏率分别独立成列，字号保持一致，仅用字重与颜色营造层次；手机端降级为保留全部字段的持仓卡片。已补入计划、实现、审查和 QA 任务，尚未修改生产 UI。
- **本轮表格原型与审查**：仅修改 `prototype/index.html`、proposal、delta spec、design 和 tasks，未修改生产代码。当前持仓原型已按“总浮盈亏 → 近 24 小时浮盈亏 → 当前总市值”排列；桌面端使用“标的 / 账户、当前单价、数量、当前市值、仓位、浮盈亏、浮盈亏率、近 24 小时盈亏、近 24 小时盈亏率”9 列表格；四个盈亏字段为独立列且数据字号相同，仅以字重和颜色建立层次；820 px 及以下转为保留全部字段的持仓卡片。Hallmark `audit` 技能动作最终结果为 **0 critical、0 major、0 minor**。原型中的单标的“近 24 小时盈亏”金额因演示数据未提供该项而显示 `—`，避免虚构数值，近 24 小时盈亏率仍展示为演示值。
- **本轮周期盈亏算法确认**：用户确认采用“总资产快照法 + 单标的资金流调整法”。总周期盈亏为 `期末资产总额 - 期初资产总额 - 外部入金 + 外部出金`，因此同时包含浮盈亏和期间已实现盈亏；单标的周期盈亏为 `期末市值 - 期初市值 - 期间买入支出 + 期间卖出净收入 + 投资收入 - 相关费用`；买卖、分红和费用的精确边界写入 spec/design。完全卖出的标的不进入当前持仓表，但计入总周期盈亏；不单独展示已实现盈亏指标。周期盈亏率使用带时间权重的投入基数。用户已确认该算法，允许进入失败测试与生产实现。
- **原型与设计复核**：`prototype/index.html` 与 `prototype/events.html` 使用 Cobalt / modern-minimal / Workbench-Ledger Grid，分别覆盖持仓总览、展示设置、刷新、加载/失败状态，以及事件筛选、列表、分页、空/错状态和证据抽屉；两页均通过 320、375、414、768 px 无页面级横向滚动检查。生产 UI 复用两级侧边导航和独立页面信息架构。
- **产品/范围复核**：只读 API 与页面覆盖投资事件筛选、稳定分页、批量关系摘要、证据详情和当前持仓估值；未新增写入、导入、迁移或估值规则，未修改主规格。回滚边界为移除投资页面 hash 路由、三个投资 API 路由和对应查询服务装配；无数据回滚动作。
- **工程/安全复核**：当前页关系使用单次批量查询；游标绑定 workspace、筛选、排序位置和 `LedgerSnapshotModel.version`，版本变化返回 `investment.updated`；事件和证据查询双重限制 workspace；来源快照使用业务字段白名单并过滤敏感键；估值复用 `PortfolioQueryService` 的共享预算和局部状态。未发现阻断性 finding。
- **Hallmark audit**：目标为 `web/src/pages/InvestmentLedgerPage.tsx`、`web/src/components/Investment*.tsx`、`web/src/styles.css` 投资账本区段和 `prototype/index.html`。已按 `.agents/skills/hallmark/references/verbs/audit.md` 完成两轮技能审查；结构戳与 Workbench / Ledger Grid 一致。首轮 finding 按 audit tell 记录为：`major / Eyebrow on every section`（原型各分区标题附近，移除重复眉题）；`major / Invented metrics`（原型事件区原有未标注的数量文案，改为“示例数据”）；`major / Mid-render token improvisation`（原型抽屉、遮罩和导航样式的内联颜色，统一回写 token）；另有一项范围一致性 finding（原型与生产文案漂移，已同步用户术语、状态和详情结构）。上述 finding 全部采纳并回写原型与 `design.md`；最终复核结果为 **0 critical、0 major、0 minor**。`audit` 是 Hallmark 技能动作，不是 shell 命令。
- **用户反馈回补**：投资详情使用“资金流向 / 更多信息”，收支详情使用“相关记录”，用户界面不再显示关系、投影、来源渠道、规则编号或“已配对”。本轮进一步要求当前持仓只呈现整体读取成功或失败，不再把 `complete / stale / partial / unsupported` 映射成逐项状态；原型已更新，生产 UI 对应任务 4.5、4.10 仍待用户确认后实施。
- **最终 diff 复核**：审查了新增 Application Service、关系型适配器、FastAPI 路由装配、React 页面/组件、预览夹具和测试；未发现范围外删除、公共写入或凭据输出。`git diff --check` 通过。
- **验证基线**：比较基线与当前 `HEAD` 均为 `4953f972fe873c87dad219930e804dd3fd58003e`（当前工作树未提交）。执行时间：2026-08-08（Asia/Shanghai）。
- **实际命令与结果**：
  - `uv run pytest -q tests/test_application_investment_web_queries.py tests/contract/test_investment_web_api.py` → SQLite 7 passed；PostgreSQL 参数 1 skipped（未设置专用 `FT_TEST_POSTGRES_URL`）。
  - `uv run pytest -q` → **1272 passed, 143 skipped**，1 个既有 Starlette/httpx deprecation warning，耗时约 242.55s。
  - `npm test -- --run` → **47 passed**；`npm run build` → 通过。
  - `FT_PREVIEW_API_PORT=8777 FT_PREVIEW_WEB_PORT=5177 npm run test:preview` → **4 passed**，覆盖投资主流程、证据关系、局部估值状态和 320/375/414/768 px；因 8766/5173 被工作区外进程占用而使用临时端口。
  - 文案回归后的 `npm test -- --run`（在 `web/`）→ **47 passed**；`npm run build` → 通过。
  - 最终文案回归后的 `FT_PREVIEW_API_PORT=8779 FT_PREVIEW_WEB_PORT=5179 npm run test:preview` → **4 passed**，覆盖投资详情、共享收支详情、简化状态和 320/375/414/768 px。
  - 修复详情断言后的 `FT_PREVIEW_API_PORT=8780 FT_PREVIEW_WEB_PORT=5180 npm run test:preview` → **4 passed**。
  - `node --input-type=module`（Playwright 加载 `prototype/index.html`）→ 320/375/414/768 px 均无横向滚动，标题顺序为“当前持仓”→“投资事件”。
  - 原型用户术语检查（Playwright）→ 320/375/414/768 px 均无技术语义回归，正文未出现“关系投影”“关系影响”“来源行快照”“导入渠道”“已配对”等词。
  - 更新后双页原型最终复审（Playwright）→ **PASS**：320/375/414/768 px 无横向溢出；总览在 320/375/414 px 单列、768 px 周期指标独占整行；手动和 10 秒自动刷新只旋转按钮图标，减少动态效果下不旋转；币种可输入、失焦转大写、无效值报错、留空恢复；显示设置文案正确往返；投资详情 Esc 关闭并恢复焦点；用户可见禁用词为 0。
  - 删除独立已卖出盈亏后的双页原型复审（Playwright）→ **PASS**：320/375/414/768 px 无横向溢出；周期卡只显示“近 24 小时浮盈亏 / +86.40 USD / +0.61%”；旧指标名、`120.30`、`1.02%`、成对数值样式及用户可见禁用词均不存在；刷新、减少动态效果、币种输入和投资详情焦点行为无回归。
  - 持仓表格原型复审（Playwright）→ **PASS**：320/375/414/768 px 无横向溢出；总览顺序为“总浮盈亏 → 近 24 小时浮盈亏 → 当前总市值”；桌面表头严格为 9 列目标顺序，四个盈亏字段独立存在，持仓数值均为 12px，盈亏字段为 700 字重；820 px 及以下隐藏表头并逐行转为卡片，标的、账户及其余全部字段均保留。
  - 持仓表格最终截图：`/tmp/investment-holdings-table-desktop-final.png`、`/tmp/investment-holdings-table-mobile-final.png`；已人工复核桌面表格、手机卡片、刷新按钮和成功/失败状态。
  - 本轮 change artifact 验证：`openspec validate --all --strict` → **17 passed**；`openspec doctor` → **root ok**；`git diff --check` → **通过**。当前 `HEAD` 与比较基线仍为 `4953f972fe873c87dad219930e804dd3fd58003e`，执行时间：2026-08-08（Asia/Shanghai）。
  - `openspec validate --all --strict` → 17 passed；`openspec doctor` → root ok。
  - `git diff --check` → 通过。
- **本轮用户确认后的实现记录**：用户确认周期盈亏算法后，已完成生产实现。周期总额使用期末资产总额减期初资产总额并扣除外部资金流；单标的使用期初/期末市值、买入支出、卖出净收入、投资收入和费用调整；完全卖出标的计入总周期盈亏但不出现在当前持仓表，也没有独立已实现盈亏字段。`funding` 事件用于外部资金流，账户间成对资金转移在总额中抵消。
- **最终验证（2026-08-08，Asia/Shanghai）**：比较基线与当前 `HEAD` 均为 `4953f972fe873c87dad219930e804dd3fd58003e`，工作树未提交。`uv run pytest -q` → **1286 passed, 143 skipped**，1 个既有 Starlette/httpx deprecation warning，耗时 234.22s；受影响 Python 测试 → **32 passed, 1 skipped**；前端 `npm test -- --run` → **50 passed**；`npm run build` → 通过；`FT_PREVIEW_API_PORT=8885 FT_PREVIEW_WEB_PORT=5187 npm run test:preview` → **4 passed**；`openspec validate --all --strict` → **17 passed**；`openspec doctor` → **root ok**；`git diff --check` → 通过。
- **最终 Hallmark audit**：目标为 `web/src/pages/InvestmentLedgerPage.tsx`、`web/src/components/InvestmentHoldings.tsx`、投资账本相关 `web/src/styles.css` 和 `prototype/index.html`。首轮发现 1 个 minor 无障碍问题（持仓表缺少 `caption` / `scope`），已补齐并重新审查；最终 **0 critical · 0 major · 0 minor**。用户可见文案、总览顺序、刷新反馈、桌面 9 列表格、手机卡片和四个独立盈亏列均通过审查。
- **最终显示精度复核**：将持仓合并、仓位、浮盈亏率、周期率、排序和统一币种单价/盈亏的前端计算改为字符串与 `BigInt`，不再使用 JavaScript 二进制浮点重算金额；该次仅改变显示层精度与币种呈现，不改变账本事实。之后重新运行前端 **50 passed**、生产构建通过、预览 Playwright **4 passed**；结构和文案无变化，Hallmark 复审仍为 **0 critical · 0 major · 0 minor**。
- **Docker PostgreSQL 验证与 finding 处理**：使用 `docker exec finance-tracker-postgres-test psql -U finance_tracker -d finance_tracker_test` 确认 PostgreSQL 16.14 可用，并以 `postgresql+psycopg://...@127.0.0.1:55432/finance_tracker_test` 执行真实矩阵。首轮全量结果为 **1444 passed, 10 skipped, 2 failed**：一项为 PostgreSQL 投资 Web API 十进制字符串尾零与 SQLite 不一致，根因在 DTO 边界直接暴露 PostgreSQL `NUMERIC` 去尾零结果，已对投资事件资产/手续费按 18 位合同格式化；修复后 `tests/contract/test_investment_web_api.py tests/test_application_investment_web_queries.py` → **9 passed**。另一项为既有 SQLite 100K 性能 p95 偶发超过 5 秒，单独执行 SQLite + Docker PostgreSQL → **2 passed**，确认非本 change 功能回归。
- **残余风险与外部写边界**：Docker PostgreSQL 投资契约已执行并通过；完整回归首轮的两个 finding 均已单独验证处理。默认浏览器偏好键为 `finance-tracker:investment-holdings-display`，移除页面代码即可回滚展示层，不影响账本事实。未提交、未推送、未创建 PR、未部署，也未归档 change；这些动作仍等待用户明确授权。
