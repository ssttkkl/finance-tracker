# 实施任务：收支账本管理与账单导入

## 1. 思考

- [x] 阅读 `project-context.md`、收支账本主规格、关系主规格、活跃变更、领域词表、Web 组件、手工写入服务和现金导入服务。
- [x] 确认收支账本展示收支投影，实际现金流水才是可写入事实，前端不得直接编辑投影。
- [x] 识别 A 类风险：财务写入、来源溯源、幂等、实际删除、关系当前状态、投影刷新、SQLite / PostgreSQL 等价和 UI 状态。
- [x] 确认产品以导入校准为主：手工和导入流水直接编辑当前值，不创建来源实体、版本实体或用户可见操作历史。
- [x] 确认首期现金导入渠道：支付宝、微信、工行信用卡、工行借记卡、建行借记卡和工银亚洲。
- [x] 确认关系操作：新增、编辑、解除、驳回；人工添加直接确认，待确认只由独立审查入口处理，关系不级联删除实际流水。
- [x] 确认用户界面使用「流水记录」「收支详情」和「关联流水」，不直接暴露投影、幂等、真删除、展示基准和校准覆盖等内部术语。
- [x] 确认金额只决定汇总方向：负数为支出、正数为收入、零金额不进入两者；任何金额都保留使用者选择或来源确定的流水类型，“个人转账”只作为关联类型。
- [x] 确认删除有关联流水时必须在“删除全部流水”和“只删除当前流水并解散关联”之间选择，不要求使用者预先逐条取消关联。
- [x] 确认取消关联复用当前 `rejected(user_unlinked)` 决定阻止自动重新匹配，不新增关系版本或抑制表。
- [x] 确认关系重建只写派生结果，不能反向覆盖实际流水当前字段；收支详情内部稳定使用展示基准流水，但不向用户暴露主次。
- [x] 确认账户支持币种保存为 `accounts.currencies` JSON 数组；不新增账户币种表，不设置主币种或默认币种；本期只读取数据库配置，不提供币种管理入口。
- [x] 按 `$openspec-explore` 的探索方式重新检查已实现页面和 active change：确认用户可见文案仍有「证据详情」「经济类型」「组成方式」等内部味道后，回流到 design、prototype、实现和测试。
- [x] 第二轮语义复核：发现原型和设计仍把自动扫描候选写成用户可见的「可能相关」，而生产详情并不展示它；已收敛为用户主动添加关联，并同步移除原型候选区。
- [x] 复核查看态到编辑态的加载路径：查看详情已经包含编辑所需的当前流水字段，重复读取会造成“新建流水”闪现和不必要等待，需求回流到同一抽屉复用数据。

## 2. 计划

- [x] 更新 proposal、delta 规格、design 和任务拆分，使“实际流水 + 来源字段 + `manual_overrides`”成为唯一当前数据模型。
- [x] 明确不在本变更新增 `cash_source_records`、`source_values`、流水版本表、关系版本表或统一操作日志。
- [x] 明确删除后再次导入同一业务行可以重新发布；删除关系只解除关系，不删除关系两端流水。
- [x] 基于已确认规格设计校准编辑、导入结果和关系审查 UI 原型；原型完成前不得进入生产实现任务。
- [x] 确认导入字段保留规则只在后台生效；原型不展示字段修改标记、账单原值或恢复操作。
- [x] 将方案 C 收敛为唯一原型，补齐流水类型、零金额、关联流水查看 / 编辑 / 添加 / 取消和删除结果选择。
- [x] 将数据库账户支持币种、手工流水币种下拉和导入未支持币种处理补入同一原型，并保持既有信息抽屉风格；不增加账户设置页。
- [x] 将用户可见术语收敛为「收支详情」「流水类型」「关联流水」「待确认」，其中「待确认」只用于独立审查结果；错误状态不再提示 API、数据库或工作区配置；原型和生产 UI 保持同一套词汇。
- [x] 将人工关联流程收敛为「关联类型 → 搜索已有流水 → 添加关联」，人工添加固定直接确认，不再给用户展示保存方式或「稍后确认」；新建对侧流水通过独立的「新建流水」入口完成，系统扫描候选及其处理说明不进入本入口。
- [x] 将查看态到编辑态的交互补入规格和设计：保持同一抽屉，当前流水立即显示，编辑候选和选项后台加载。
- [x] 复核可图标化操作边界：仅压缩筛选展开、查看、编辑、返回、关闭和关联区添加，保存、删除、取消关联、更改类型、导入和新建流水保留文字。
- [x] 根据生产 UI 复核回流关联添加流程：收口为单一「添加关联」入口，直接搜索已有流水；人工添加固定确认，移除保存方式。
- [x] 按 Hallmark 组件级重设计复核既有 Workbench / Cobalt 视觉，确定已有流水改为服务端搜索、结果列表和分页器，不增加说明面板。
- [x] 本轮产品回流：收支账单主列表恢复瀑布流追加；仅关联检索使用分页器；关联检索增加可调整时间范围，默认覆盖当前流水日期前后三天；候选行用左侧竖线表示选中态并移除 radio。

## 3. 任务拆分与一致性

- [x] 为正 / 负 / 零金额手工新增、流水类型合法组合、导入编辑、校准覆盖合并、字段改回来源值后自动清理和物理删除分别补失败回归测试。
- [x] 为 `source_type + record_id` 行级幂等、`source_fingerprint` 变化和 `manual_overrides` 合并补 SQLite / PostgreSQL 契约测试；PostgreSQL 无 `FT_TEST_POSTGRES_URL` 时由 fixture 跳过。
- [x] 为删除包含关系的流水、自动处理关系、物理删除关系与流水、重新维护投影和删除后再次导入补失败与成功场景测试。
- [x] 为关联类型手选、选择已有对侧流水、待审、确认、取消后拒绝占位、自动扫描抑制、主动重新关联和关键字段影响确认补关系测试。
- [x] 为普通字段编辑后重建不改写 `cash_transactions`、稳定展示基准流水和关联流水详情保留补投影回归测试。
- [x] 明确 API 的事实 ID、投影 ID、业务行标识、来源行快照和校准覆盖边界，避免用 `record_id` 代替事实 ID。
- [x] 为账户 `currencies` 的格式、系统币种目录、数据库初始化、空账户和历史流水可读性补失败回归测试；本期不测试运行时添加 / 移除 UI。
- [x] 为 `metadata_json.base_currencies` 到 `accounts.currencies` 的迁移、未知历史币种报告和 SQLite / PostgreSQL 结果一致性补迁移测试；PostgreSQL 矩阵由环境变量控制。
- [x] 在实现前后检查 proposal、delta spec、design、prototype、tasks、实现和测试的一致性；用户文案回流后重新核对了核心场景和验收口径。
- [x] 为待确认关联的删除影响补回归覆盖：删除确认统一显示当前关联数量，不区分用户不可见的内部状态。
- [x] 为查看态点击编辑不重复请求当前流水补 Web 回归覆盖；同时保留无当前详情时的异常读取和重试路径。
- [x] 为纯图标操作补可访问名称、真实 SVG、44 px 命中区域和非 Emoji 回归断言，并同步原型、design 和 delta spec。
- [x] 为候选流水服务端搜索、20 条上限、稳定分页、当前流水排除和 SQLite / PostgreSQL 等价补失败回归测试。
- [x] 为单一添加入口、无候选预取、无保存方式、已有流水搜索、搜索空 / 错误 / 加载 / 分页状态补 Web 回归测试。
- [x] 为收支账单主列表首批加载、滚动追加、追加失败重试和筛选取消旧追加补 Web 回归测试；为关联候选保留分页器的上一页、下一页和失败重试覆盖。
- [x] 为关联检索日期范围、默认前后三天、服务端日期过滤和无 radio 的键盘单选语义补 SQLite / Web 回归测试。

## 4. 构建

- [x] 扩展现金流水 Application Service，先让新增、编辑、实际删除和校准合并测试失败。（流程差异：本次改动已在用户共享工作树完成，未能事后安全搬迁到独立 worktree；后续变更从隔离 worktree 开始。）
- [x] 增加 `source_fingerprint`、`manual_overrides`、并发版本或等价字段；不得新增来源实体和版本历史表。
- [x] 增加 `accounts.currencies` JSON 列并迁移现有账户币种；停止读取和写入 `metadata_json.base_currencies`，保留 `metadata_json` 其他遗留内容。
- [x] 实现手工和导入流水使用同一当前值编辑边界；来源字段只读，金额保持精确 `Decimal`。
- [x] 实现账户支持集合校验；新建 / 编辑 / 关联新增只允许选择数据库当前支持币种，本期不实现账户币种管理 API。
- [x] 实现流水类型和按需业务细分编辑；方向只取金额符号，零金额流水按自身流水类型可见但不计入收入或支出。
- [x] 实现实际删除事务：返回关联影响，确认后物理删除端点关系、投影引用和当前流水，不提供用户可见恢复入口。
- [x] 增加关系当前状态管理和待审提交 API，复用现有关系主规格校验，并以 `rejected(user_unlinked)` 阻止自动扫描重新关联同一端点。
- [x] 增加 Web 手工表单、校准编辑抽屉、关联流水查看 / 编辑 / 添加 / 取消、删除影响确认和派生结果刷新契约。
- [x] 增加导入预览与确认 API，覆盖全部现金渠道，复用已有解析器和原子导入服务。
- [x] 在导入预览中聚合账户未支持币种；提示更新数据库账户配置后重新导入，不允许普通导入静默扩展账户配置。
- [x] 以确认后的方案 C Workbench 原型为基线实现生产页面；不保留方案 A、B 入口。
- [x] 实现信息抽屉原位切换编辑态：复用收支详情中的当前流水数据，只有缺少缓存时才读取当前流水，关联候选在后台加载。
- [x] 实现统一的 2 px 描边 UI 图标组件，并用于表格查看、筛选展开、抽屉编辑 / 返回 / 关闭及关联区添加 / 编辑。
- [x] 实现关系候选的数据库侧筛选与稳定分页 API，避免 Application Service 和浏览器加载全量流水。
- [x] 实现抽屉内直接添加已有流水的关联流程；候选使用服务端搜索和分页器，提交固定为 `accepted`，新建对侧流水沿用独立「新建流水」入口。
- [x] 实现收支账单主列表的滚动触底追加和「加载更多」键盘回退；关联候选继续使用分页器切换。
- [x] 为关联候选增加服务端日期范围过滤和当前流水前后三天默认范围。
- [x] 移除关联区域的已有 / 新建切换，改为默认已有流水搜索，并用整行竖线选中态替代 radio。

## 5. 审查

- [x] 产品 / 范围复核：导入校准、实际删除、删除后重导入、现金渠道和关系当前状态均有实现与测试证据。
- [x] 工程复核：事实与投影边界、事务顺序、幂等、精度、外键、并发和回滚均已复核；SQLite 迁移降级补充了外键保护处理。
- [x] 安全复核：上传大小、凭据处理、来源快照输出、路径泄露和工作区隔离均已复核；Web 只返回脱敏记录字段。
- [x] 设计复核：流水类型、零金额、关联流水管理、删除影响、关系待审、空 / 错误 / 成功状态、无障碍和 4 个目标宽度均覆盖上一版基线；本轮二选一删除和字段覆盖需重新复核。
- [x] 设计复核：数据库账户币种下拉、空配置账户、导入未支持币种和移动端抽屉状态均覆盖。
- [x] 运行最终 UI 的手工 Hallmark audit；仓库未提供 `hallmark` 可执行文件，因此按 `references/verbs/audit.md` 完成静态、截图和交互复核：`0 critical · 0 major · 1 minor`。minor 为原生文件选择控件的系统样式，保留以符合平台可用性；主按钮 hover 对比度问题已修复并重新截图。
- [x] 最终 diff 复核：发现并修复账户摘要兼容、空字段导入、SQLite 降级外键、主按钮 hover 和预览文案问题；未授权提交 / 推送 / 部署，工作树保留供用户复核。
- [x] 最终 diff 复核补充检查：发现并修复待确认关联的删除影响文案，以及原型中多余的自动候选区；同步检查用户可见文案未重新引入内部术语。
- [x] 本轮 UI 交互复核：查看态和编辑态共用同一信息抽屉；编辑态沿用既有抽屉布局，无独立页面、技术语义或额外说明面板。
- [x] 图标密度复核：高频低风险操作改用统一 SVG，关键业务动作保留文字；图标按钮具备可访问名称、焦点状态和 44 px 点击区域。
- [x] 本轮 Hallmark audit：复核生产组件、原型和 390 / 768 / 1024 / 1440 px 快照，未发现图标混用、Emoji、歧义关键动作、命中区域或响应式问题；`0 critical · 0 major · 0 minor`。
- [x] 对重做后的关联添加组件运行 Hallmark audit，复核入口关联性、状态完整性、键盘操作和 320 / 375 / 414 / 768 px 单列布局。
- [x] 对本轮分页器、时间范围和整行选中态运行 Hallmark audit，复核自解释性、键盘操作、加载 / 错误状态和 320 / 375 / 414 / 768 px 布局；仓库未提供 `hallmark` 可执行文件，按 `references/verbs/audit.md` 手工复核，`0 critical · 0 major · 0 minor`。
- [x] 独立复核上下文：使用只读 `codex review --base origin/refactor/web` 复核 SQL / 数据安全、幂等、关系约束、API 和测试覆盖；发现待确认开放关联改成同笔支付时会触发双边约束并返回 500（`src/ft/application/cash_ledger.py`），已在 `96c164b` 增加服务层校验、回归测试并重新验证。

## 6. 测试与 QA

- [x] 运行 SQLite 的 Application Service、账户币种迁移、导入、校准、关系和删除契约矩阵；SQLite 已通过。
- [x] 完成开发和 SQLite 回归后，人工准备本地 Docker 或 `psql` 可连接的专用 `_test` PostgreSQL 数据库，配置 `FT_TEST_POSTGRES_URL`，补跑同一 Application Service、账户币种迁移、导入、校准、关系和删除契约矩阵；未配置时不得将跳过项计入完整验证。
- [x] 运行 Web Vitest、构建、Playwright 主流程、错误 / 空状态 / 键盘和响应式检查。
- [x] 运行生产预览；自包含 API 预览通过列表、账户币种、流水编辑入口、六渠道导入入口、移动端双端金额和 API-origin 检查。
- [x] 运行 `openspec validate --all --strict`、`openspec doctor`、`git diff --check` 和相称的 Python 检查。
- [x] 补充 SQLite 范围化性能基线：覆盖写入、读取、关联组规模、导入、查询计划和浏览器网络请求；双后端安静环境矩阵仍由 11.7 保留未完成。
- [x] 在本文件记录比较基线、当前 `HEAD`、实际命令、结果、执行时间和未解决风险。
- [x] 2026-08-08 CST 补跑完整验证：`uv run pytest -q`（1274 passed, 143 skipped, 1 warning）、Web Vitest（43 passed）、构建、E2E（10 passed）、视觉回归（10 passed）、生产预览（3 passed）、`openspec validate --all --strict`（33 passed）、`openspec doctor`、`git diff --check` 和 Python `compileall` 均通过；预览测试通过 `FT_PREVIEW_API_PORT=8776` 避开正在运行的账本 API `8766`。
- [x] 2026-08-08 CST 真实浏览器 QA：`.ft` SQLite 后端 `8766` + Vite `5174`，桌面 / 375 px 移动端打开首条流水并切换编辑；当前流水详情请求为 0、未出现“新建流水”、交易对方立即填入、返回后回到查看态，控制台错误为 0。
- [x] 补跑关联搜索定向 Python / Web 测试、完整 Web 测试与构建、Playwright 主流程 / 视觉 / 生产预览，并使用 `.ft` SQLite 在桌面和移动端完成真实浏览器 QA。
- [x] 补跑主列表瀑布流、时间范围和无 radio 候选选择的定向测试、完整 Web 测试、视觉回归和 `.ft` SQLite 桌面 / 移动端浏览器 QA；本轮 Vitest 46 passed，构建通过，Playwright 主流程 11 passed，视觉回归 12 passed；真实 SQLite QA 覆盖桌面 / 移动端主列表追加、关联默认日期、候选分页、整行选中态和无横向溢出。
- [x] 2026-08-11 CST 回流验证：`npm test -- --run` 为 46 passed，`npm run build` 通过，`FT_E2E_WEB_PORT=5196 npm run test:e2e` 为 11 passed，`npm run test:visual` 为 12 passed，`FT_PREVIEW_API_PORT=8767 npm run test:preview` 为 3 passed；新增断言确认候选流水时间使用浏览器本地格式，视觉快照覆盖主列表追加 / 追加加载 / 追加失败及关联候选分页。

## 7. 发布

- [x] 记录发布前的数据库迁移、备份、回滚和观察项；未获明确授权不得提交、推送、创建 PR、部署或操作真实账本。
- [ ] 发布后观察重复导入结果、校准保留比例、导入失败代码、关系待审数量、账本刷新失败和实际删除结果；未授权连接真实账本或部署前不执行。
- [ ] 变更完成且主规格同步、独立复核和适用验证项闭环后，运行 `openspec archive cash-ledger-crud-import` 归档 change；归档只整理仓库记录，不代表提交、推送或部署授权。

## 12. 本轮 UI 回流：辅助字段图标与窄屏流水卡片

- [x] 12.1 将用户澄清写入 delta 规格与 design：金额、交易信息（交易对方、对方账号、备注）保持核心文字层级；桌面表格表头保持纯文字；移动端账户、流水类型和分类行首只放图标，日期保持纯文字；图标与字段值按 baseline 对齐；分类、账户和流水类型在正文中各占一行；来源不进入列表，并将复选框固定在左上角。
- [x] 12.2 更新 `prototype/index.html`，表达表单字段对齐、移动端“图标 + 字段值”的卡片正文、分类左侧、选择复选框左上角和点击卡片查看详情；在 320、375、414、768 px 检查无横向溢出。
- [x] 12.3 先补 Web 回归断言：桌面表头不渲染 SVG；移动端辅助字段渲染对应 SVG 且不重复字段名称；金额和交易信息不渲染字段图标；分类控件与其他字段同层；列表无来源字段；选择模式窄屏卡片固定复选框且点击复选框不打开详情。
- [x] 12.4 实现 `UiIcon` 字段图标、`RecordDrawer` 统一字段行和 `CashTable` 窄屏卡片布局；桌面保留可访问查看按钮，窄屏隐藏小眼睛并让卡片本身打开详情，保持键盘和现有保存语义。
- [x] 12.5 2026-08-14 CST 完成最终 UI 回流验证：`npm test -- --run`（91 passed）、`npm run build`（通过）、`FT_E2E_WEB_PORT=5196 npm run test:e2e`（25 passed）、`npm run test:visual`（15 passed，覆盖 320 / 375 / 390 / 414 / 768 / 1024 / 1440 px）、`openspec validate --all --strict`（21 passed）、`openspec doctor`、`git diff --check` 和原型内联脚本检查（均通过）。范围化搜索确认窄屏卡片无来源字段、无 `mobile-field-label`、日期无卡片图标；浏览器断言确认分类、账户、流水类型依次分行且 baseline 对齐，桌面表头无字段图标，金额 / 交易信息无字段图标。
- [x] 12.6 2026-08-14 CST 完成最终 UI Hallmark audit：仓库未提供 `hallmark` 可执行文件，按 `.agents/skills/hallmark/references/verbs/audit.md` 对生产视觉快照、DOM 交互和 320 / 375 / 390 / 414 / 768 / 1440 px 状态复核；检查图标与值的 baseline、分类 / 账户纵向顺序、日期纯文字、选择框左上角、移动端无查看按钮、桌面表头纯文字和无横向溢出。结果 `0 critical · 0 major · 0 minor`。当前 `HEAD` 为 `5e36df61cb63f223e239a488f8b41a4ff8b94187`，与 `origin/refactor/web` 的 merge-base 相同；本轮代码、测试、快照和 OpenSpec 改动均仍在未提交工作树中，未提交、推送或部署。

## 8. 反思

- [ ] 将最终确定的流水校准、实际删除和关系当前状态规则同步到主规格。
- [x] 将未来统一操作日志列为独立变更，不在本变更中按业务类型增加审计表。
- [x] UI 原型确认后，回写本任务中的信息架构、状态覆盖和响应式证据；方案 C 采用卡片流，复用既有信息抽屉切换查看态与编辑态，并在同一抽屉管理关联流水。
- [x] 本轮流程反思：用户可见术语问题属于设计 finding，已回流更新 `design.md`、`prototype/index.html`、生产组件和 Web 断言；后续 UI finding 必须在继续开发前同步这四处。
- [x] 本轮流程反思补充：原型不得保留生产未实现的候选区；用户可见文案复核同时检查生产组件、原型、规格场景、测试断言和截图证据。
- [x] 本轮流程反思：查看态已有可编辑字段时，编辑入口必须复用当前 UI 状态；关联候选等辅助数据可以后台加载，但不应让用户等待或看到错误的“新建流水”状态。
- [x] 本轮流程反思：有限集合选择控件不得承载无界流水集合；存在多种创建来源时先建立统一任务入口，再在流程内分支。
- [x] 本轮流程反思：收支账单主列表的连续浏览使用瀑布流追加并保留已加载内容；只有添加关联记录中的有限候选结果使用分页器表达当前位置，不能用无界下拉框承载全量流水。

## 9. 本轮产品模型回流（合并 `refactor/web` 后）

以下任务对应本轮新确认的产品模型。前面的已完成项保留上一轮实现证据；在本组任务完成前，不得把 change 视为当前产品规则已经实现。

- [x] 9.1 确认当前分支已包含 `refactor/web` 与 `origin/refactor/web`，并以合并后的代码作为本轮复核基线。
- [x] 9.2 回写 `DOMAIN_GLOSSARY.md`、proposal、delta spec 和 design：用户只感知「收支详情」「关联流水」，内部使用展示基准流水但不暴露主次、投影或关系图。
- [x] 9.3 更新 `prototype/index.html`：主详情与关联流水补齐对方账号、流水类型、适用业务细分等可读字段，统一“编辑收支详情”与关联流水文案，并移除技术语义。
- [x] 9.4 为收支详情响应建立字段覆盖合同：展示金额、币种、发生时间、账户、交易对方、对方账号、流水类型、业务细分、分类、备注和来源；隐藏 `record_id`、`created_at`、来源快照、校准覆盖、删除字段和派生 ID。
- [x] 9.5 将查看态到编辑态实现为当前抽屉原位切换“编辑收支详情”，复用已经读取的当前详情和关联流水字段；不跳页、不显示新建流水、不重复读取当前流水。
- [x] 9.6 将普通字段（交易对方、对方账号、分类、备注）和关键字段（金额、币种、账户、发生时间、流水类型、业务细分）的保存影响分层；普通字段不被关系重建覆盖，关键字段失效时先确认“保存并拆开”。
- [x] 9.7 实现三条及以上关联流水的单条取消与独立“解散关联”，取消后保留被移出的流水并阻止自动重新匹配，剩余关联组继续保持。
- [x] 9.8 将有关联流水的删除改为二选一：删除全部流水，或只删除当前流水并解散关联、保留其他流水为独立条目；禁止删除后隐藏切换到另一条替代流水。
- [x] 9.9 将导入更新关键字段接入同一关联影响检查；重复导入不得未经确认静默解除关联或更换收支详情展示对象。
- [x] 9.10 将 `pending_review` 从收支详情抽屉移到独立关系审查入口，人工添加固定为 `accepted`，不再出现保存方式或“稍后确认”。
- [x] 9.11 为字段覆盖、原位编辑、普通 / 关键字段影响、三条以上关联取消、删除二选一、导入影响和 pending 边界补 SQLite / PostgreSQL Application Service 契约测试。
- [x] 9.12 更新 Web Vitest、Playwright、视觉快照和 `.ft` SQLite 桌面 / 移动端浏览器 QA，覆盖 320、375、414、768 px 无溢出、键盘焦点、加载 / 错误 / 成功状态。
- [x] 9.13 完成独立产品 / 工程 / 设计 diff 复核和最终 Hallmark audit；所有 critical / major finding 修复后重新审查，并将命令、HEAD、基线和残余风险回写本文件。
- [x] 9.14 按最新产品回流恢复收支账单主列表瀑布流追加；仅关联候选保留分页器，并将候选流水与关联卡片时间统一按浏览器本地时区格式化；补齐 Web、E2E、视觉和原型回归。

## 10. 保存性能回流

- [x] 10.1 先补固定 10,000 条现金流水夹具下的普通字段保存 p95 100ms 失败门禁，确认当前实现因全量重建超出预算。
- [x] 10.2 将投影维护改为按已确认关联连通组增量读取和重建；普通展示字段只刷新当前投影行，普通字段不再扫描关联全表，增量替换时按锁定状态和当前替换组成员差量维护计数。
- [ ] 10.3 在 SQLite 和真实 PostgreSQL 上通过普通字段保存 p95 ≤100ms 门禁，并补跑受影响的关系、删除、导入和完整回归。
- [x] 10.4 为关联流水新增、修改、取消关联和解散关联补固定 10,000 条流水夹具下的 100ms p95 门禁；每项执行 3 次预热、20 次样本，测量包含事务提交和操作结果返回；SQLite 曾在独占运行中通过，真实 PostgreSQL 待专用测试库复跑。

## 11. 性能门禁扩展

- [ ] 11.1 将普通字段保存样本提升到至少 20 次，并补充新建、关键字段保存、有关联流水时的关键字段保存和两种删除结果的 100ms p95 门禁；门禁测量事务提交及用户操作结果。SQLite 已有门禁和实现优化，但当前宿主机有 Virtualization / Android Emulator 持续占用约 700% CPU，新增写入门禁本轮观测到约 105–297ms p95，需在安静宿主机复跑后才能判定是否达标。
- [x] 11.2 为 2、10、100 和 1,000 条流水的已确认关联组补关键字段保存、取消关联和解散关联性能夹具，记录受影响关联组规模，并对每成员耗时设置不超过前一规模 5 倍的近似线性增长门禁；禁止只用双成员关联组作为性能结论。SQLite 优化后观测为 2 / 10 / 100 / 1,000 组规模，1,000 条组取消约 651ms、解散约 1.04s，规模门禁通过，不套用双成员 100ms 热路径门禁。
- [x] 11.3 为主账单首批 / 后续游标页 / 组合筛选、关联流水无关键词 / 关键词 / 时间范围 / 后续游标页和收支详情单条 / 有关联读取补固定 10,000 条流水夹具下的查询 p95 门禁；额外记录主账单连续追加 10 页累计耗时。SQLite 通过，单页 p95 约 21–67ms，连续 10 页约 467ms。
- [x] 11.4 为导入预览、首次导入、完全重复导入和来源变化合并补 1,000 行固定夹具；记录耗时、行 / 秒和峰值内存，并覆盖已校准字段保持路径；另补 10,000 行预览基线。SQLite 1,000 行导入四路径与 10,000 行预览通过，记录了总耗时、行 / 秒和峰值内存。
- [x] 11.5 检查性能查询的 SQLite / PostgreSQL `EXPLAIN` 或等价查询计划：主账单游标排序、来源幂等身份、关联端点和关联候选日期排序不得出现无界客户端加载或意外全表排序。SQLite 与 PostgreSQL 均完成计划检查；SQLite 命中 `ix_cash_projection_members_page_lookup` / `ix_cash_projection_relations_page_lookup` / 关系端点复合索引，PostgreSQL 命中分页附属索引或等价的单投影行唯一索引，并命中两个关系端点索引。
- [x] 11.6 补浏览器网络性能回归：编辑已有详情不得重复读取当前流水；关联取消的写入和详情刷新请求数量稳定；主账单连续追加不得重复请求同一游标，且 10 页追加后无明显长任务或重复 DOM。新增编辑抽屉类型选项请求去重断言，Playwright 11 项全部通过。
- [ ] 11.7 在安静宿主机上执行 SQLite 与真实 PostgreSQL 性能矩阵，记录 p50 / p95 / 最大值、后端、夹具摘要、运行环境和当前 `HEAD`；未配置 PostgreSQL 时保留未完成状态，不得宣称双后端门禁通过。

## 探索阶段证据

- **当前 HEAD**：`ea746855d1ad4d019b9f77543cfcc99a69554429`（已推送的 PR 分支 `codex/cash-ledger-crud-import`；本轮 change 尚有未提交实现、迁移、测试和文档改动）。
- **比较基线**：目标分支 `refactor/web`，基线 HEAD 为 `4953f972fe873c87dad219930e804dd3fd58003e`；本轮已执行 `git merge --no-edit refactor/web`，结果为 `Already up to date`，未产生新的合并提交，当前功能提交未合并或部署。
- **已运行**：`openspec --version`（1.7.0）、`openspec list --json`、`openspec status --change cash-ledger-crud-import --json`、项目上下文和代码 / 规格读取。
- **本轮已验证**：2026-08-08 CST，`openspec validate --all --strict`（33 passed）、`openspec doctor`（Root ok）、`git diff --check`（通过）、Python `compileall`（通过）和原型内联脚本语法检查（通过）。
- **UI 原型验证**：2026-08-07 CST，临时本地静态服务 `127.0.0.1:8765` 上运行 Playwright 内联检查；已确认的 C 工作台复用既有 480 px 信息抽屉，查看态 / 编辑态、金额原位编辑、零金额保存后在 17 种流水类型中改选、数据库账户币种下拉、导入未支持币种处理、来源只读、关联记录查看 / 编辑流水 / 更改类型 / 添加 / 取消、已有流水检索、时间范围、主列表滚动追加、候选分页器、删除影响、六类渠道和导入预览均通过。页面不展示字段修改状态、账单原值、自动扫描候选、内部拒绝决定、内部零金额兼容类型或恢复操作；320、375、414、768、1280、1440 px 的页面宽度分别等于视口宽度，按钮无折行。
- **UI 原型文件**：`openspec/changes/cash-ledger-crud-import/prototype/index.html` 与 `prototype/tokens.css`；Hallmark stamp 为 `Workbench / Cobalt / N9 / Ft2`，self-critique 为 `P5 H5 E5 S5 R5 V5`，58 项 slop test 通过。
- **UI 截图**：`prototype/pc-view-related.png`、`prototype/pc-add-relation.png`、`prototype/mobile-edit-record.png` 和 `prototype/mobile-delete-impact.png`。
- **Hallmark 原型审查**：2026-08-07 CST，对 `prototype/index.html`、`prototype/tokens.css` 和四张目标视口截图执行 audit；重点复核数据库账户币种下拉、导入未支持状态是否暴露技术语义、按钮 / 输入控件状态和小屏溢出；`0 critical · 0 major · 0 minor`。最终生产 UI 形成后仍须重新 audit。
- **领域文案检查**：已按 `$chinese-documentation` 复核中英文空格、全角标点、代码标识符和产品术语；本轮新增术语已合并到 `DOMAIN_GLOSSARY.md`。
- **生产实现验证**：2026-08-07 CST，`uv run pytest -q` 结果为 `1274 passed, 143 skipped, 1 warning`；定向现金管理 / 迁移测试为 `26 passed, 3 skipped`。跳过项均因未设置 `FT_TEST_POSTGRES_URL`，需要连接专用 PostgreSQL 测试库时补跑。
- **Web 验证**：`npm test -- --run` 为 `43 passed`；`npm run build` 通过；`npm run test:e2e` 为 `9 passed`；`npm run test:visual` 为 `10 passed`（更新快照后再次复跑）；`npm run test:preview` 为 `3 passed`。覆盖零金额、流水类型、抽屉编辑、关联维护、删除影响、六渠道导入、键盘焦点、空 / 错误状态和 320 / 375 / 390 / 414 / 768 / 1024 / 1440 px 响应式检查。
- **Web 验证**：`npm test -- --run` 为 `43 passed`；`npm run build` 通过；本轮 `npm run test:e2e` 为 `10 passed`（新增查看态切换编辑且不重复读取当前流水）；`npm run test:visual` 为 `10 passed`；`FT_PREVIEW_API_PORT=8776 npm run test:preview` 为 `3 passed`。覆盖零金额、流水类型、抽屉编辑、关联维护、删除影响、六渠道导入、键盘焦点、空 / 错误状态和 320 / 375 / 390 / 414 / 768 / 1024 / 1440 px 响应式检查。
- **本轮回流验证**：2026-08-08 CST，修改待确认关联删除提示和原型候选区后重新运行 `npm test -- --run`（43 passed）、`npm run build`、`npm run test:e2e`（9 passed）、`npm run test:preview`（3 passed）、`openspec validate --all --strict`（33 passed）和 `git diff --check`；原型静态浏览器检查通过，无候选文案，人工关联无“稍后确认”入口，320 / 375 / 414 / 768 px 无横向溢出。
- **浏览器 QA**：2026-08-08 CST，生产构建以 `VITE_FT_API_ORIGIN=http://127.0.0.1:8766` 启动自包含预览 API，使用 browse 检查 375 px 列表、收支详情、流水编辑抽屉、导入抽屉；截图为 `/tmp/finance-tracker-copy-detail-mobile-stable.png` 和 `/tmp/finance-tracker-copy-import-mobile.png`。控制台清空后无错误；详情、编辑和导入入口的网络请求均成功，六个渠道、CNY / HKD / USD 下拉、流水类型和用户文案可见。为消除详情加载时的 404，补齐了预览 API 的示例流水详情响应。
- **最终 Hallmark audit**：2026-08-08 CST，仓库未安装 `hallmark` 可执行文件，按 audit reference 完成生产构建、375 / 768 / 1280 px 截图、DOM 文案、交互、控制台和响应式复核；`0 critical · 0 major · 1 minor`。唯一 minor 是原生文件选择控件的系统样式；用户可见文案未发现“证据详情”“经济类型”“组成方式”“可能相关”“API / 数据库配置”等内部语义。
- **本轮 Hallmark audit**：2026-08-08 CST，仓库仍未安装 `hallmark` 可执行文件，按 `references/verbs/audit.md` 对同一抽屉查看 / 编辑状态及 `/tmp/finance-tracker-editor-desktop.png`、`/tmp/finance-tracker-editor-mobile.png` 复核；未发现结构漂移、技术语义、重复页面或响应式溢出，结果为 `0 critical · 0 major · 0 minor`。
- **本轮真实浏览器证据**：以 `http://127.0.0.1:5174/` 连接 `http://127.0.0.1:8766/` 的 `.ft` SQLite 后端；首条实际流水编辑字段“交易对方”为“美团”，同一 `.evidence-panel` 的 `aria-label` 从“收支详情”变为“编辑流水”，编辑期间无 `/cash-records/:id` GET，返回后恢复查看态，控制台无错误。
- **图标密度验证**：2026-08-08 CST，`npm test -- --run` 为 45 passed，`npm run build` 通过，`FT_E2E_WEB_PORT=5184 npm run test:e2e` 为 10 passed，视觉基线更新后 `FT_VISUAL_WEB_PORT=5185 npm run test:visual` 为 10 passed，`FT_PREVIEW_API_PORT=8776 npm run test:preview` 为 3 passed。真实 `.ft` SQLite 页面在 1440 × 900 和 390 × 844 下确认筛选、查看、编辑、返回、关闭及关联区添加 / 编辑使用真实 SVG；图标按钮均为 44 × 44 px、可通过原操作名称访问，移动端页面宽度与视口一致，控制台无错误。查看态切换编辑态仍立即显示“美团”，网络只读取选项和关联候选，不重复读取当前流水。
- **图标原型验证**：`prototype/index.html` 内联脚本语法通过；320、375、414 和 768 px 下页面与抽屉宽度等于视口宽度，查看 / 编辑 / 返回 / 关闭 / 添加关联图标与生产 UI 使用相同描边、尺寸和可访问名称。
- **统一关联入口验证**：2026-08-08 CST，关系候选定向 Python 测试为 `3 passed, 3 skipped`（SQLite 通过，PostgreSQL 因未设置 `FT_TEST_POSTGRES_URL` 跳过）；Web Vitest 为 `46 passed`，构建通过；Playwright 主流程为 `11 passed`，视觉回归为 `12 passed`。新增用例覆盖无候选预取、`limit=20`、`exclude_id`、服务端搜索、日期范围、候选分页、无可见 radio 和手工关联固定 `accepted`；新建对侧流水改由独立「新建流水」入口完成。
- **真实 `.ft` SQLite 浏览器 QA**：2026-08-09 CST，以 `127.0.0.1:5174` 前端连接 `127.0.0.1:8766` 后端，在 1440 × 900 和 390 × 844 检查主列表滚动追加、筛选重新读取、关联默认日期 `2026-06-30` 至 `2026-07-06`、候选分页与整行选中态；请求包含 `date_from` / `date_to` / `cursor`，未预取前无候选请求，无保存方式 / 稍后确认 / 新建入口，页面无横向溢出，控制台错误为 0。
- **完整 Python 回归残余**：2026-08-08 CST，`uv run pytest -q` 为 `1275 passed, 144 skipped, 1 failed`；唯一失败为既有 `tests/test_wealth_performance.py::test_fixed_100k_fact_rebuild_and_active_cache_meet_budgets[sqlite]`，SQLite `cold_p95_ns=5791395417` 超过 5 秒预算，`hot_p95_ns=53004125`，不涉及本次收支关系改动；现金管理定向测试通过。该环境基线风险保留，未修改无关性能实现。
- **本轮完整回归**：2026-08-09 CST，`uv run pytest -q` 为 `1278 passed, 146 skipped, 1 warning`；`npm test -- --run` 为 `46 passed`；`npm run build`、`FT_E2E_WEB_PORT=5184 npm run test:e2e`（11 passed）、`npm run test:visual`（12 passed）和 `FT_PREVIEW_API_PORT=8776 npm run test:preview`（3 passed）均通过；`openspec validate --all --strict` 为 33 passed、`openspec doctor` 通过、`git diff --check` 和 `compileall` 通过。
- **本轮 Hallmark audit**：2026-08-09 CST，仓库仍未提供 `hallmark` 可执行文件，按 `references/verbs/audit.md` 复核分页器、时间范围、候选分页、整行选中竖线、无可见 radio、加载 / 错误状态及 320 / 375 / 414 / 768 / 1440 px 快照；发现并修复候选选中态边框被基础 border 覆盖的问题，复核后 `0 critical · 0 major · 0 minor`。
- **本轮真实 `.ft` SQLite 浏览器 QA**：2026-08-09 CST，重启 `FT_DATABASE_URL=sqlite+pysqlite:////Users/huangwenlong/.ft/finance-tracker.db` 的 8766 后端后，以 1440 × 900 和 390 × 844 复核主列表滚动追加、筛选重新读取、关联默认日期按浏览器本地日期前后三天计算、扩大日期后的关联候选分页、整行选中态和无横向溢出；请求正确包含 `date_from` / `date_to` / `timezone` / `cursor`，无 radio 输入，选中行带 `is-selected`，控制台错误为 0。
- **此前时区边界回归**：新增 SQLite / PostgreSQL 共享契约，验证 UTC 时间跨越本地午夜时，关联日期范围按请求的 `Asia/Shanghai` 日历边界筛选；首次执行时 SQLite 通过，PostgreSQL 因尚未配置 `FT_TEST_POSTGRES_URL` 跳过，后续已在本地 Docker PostgreSQL 门禁中补跑。
- **本轮 PostgreSQL 门禁验证**：2026-08-09 CST，使用本地 Docker 容器 `finance-tracker-postgres-test` 的专用数据库 `finance_tracker_test`（`127.0.0.1:55432`），临时配置 `FT_TEST_POSTGRES_URL` 且不写入仓库；收支管理、投影、Web API、记录类型和关系定向矩阵为 `71 passed`，强制要求 PostgreSQL 的完整 `uv run pytest -q` 为 `1441 passed, 10 skipped, 1 warning`。未输出或持久化数据库凭据。
- **本轮修复后完整回归**：2026-08-09 CST，修复开放关联类型变更边界后强制 PostgreSQL 的 `uv run pytest -q` 为 `1442 passed, 10 skipped, 1 warning`；受影响关系测试为 `29 passed, 11 skipped, 1 warning`。独立复核 finding 已关闭。
- **未解决 / 后续**：完整 SQLite / PostgreSQL 性能矩阵、发布后的重复导入比例 / 待确认数量 / 删除观察项尚未执行；本次在共享工作树实施而未建立独立 worktree；主规格同步和 change 归档尚未执行。上述事项不在未授权连接真实账本或部署时擅自执行。未来统一操作日志仍作为独立变更，不在本变更加入审计表。
- **本轮 change 更新验证**：2026-08-10 CST，`git merge --no-edit refactor/web` 为 `Already up to date`；`openspec validate --all --strict` 为 18 passed，`git diff --check` 通过，原型内联脚本解析通过；本地静态 Playwright smoke 覆盖 320 / 375 / 414 / 768 px，确认页面与抽屉无横向溢出、编辑标题为“编辑收支详情”、当前字段无需重新读取、有关联时删除二选一可见。仓库未提供 `hallmark` 可执行文件，本轮只完成范围化原型审查；生产实现回流后的完整 audit、测试和 QA 仍由 9.11–9.13 负责。
- **关联流水性能门禁**：2026-08-11 CST，`uv run pytest -q tests/test_cash_projection_performance.py::test_fixed_10k_cash_relation_mutations_meet_100ms_budget -s` 已加入并在一次独占 SQLite 运行中通过（固定 10,000 条流水、3 次预热、20 次样本；新增约 100ms、修改约 97ms、取消关联约 90ms、解散关联约 85ms；p95 均不超过 100ms）。随后复跑受宿主机 Android Emulator / Virtualization 高 CPU 占用影响出现偶发超时，需在安静环境补跑后再作为稳定门禁结论；同一测试的 PostgreSQL 参数因当前环境没有可完成握手的 `FT_TEST_POSTGRES_URL` 而跳过，不能计入通过。
- **本轮最终验证**：2026-08-10 CST，修复 API 字段合同测试、个人转账单条语义、关闭焦点竞态和局部关系图校验后，强制 PostgreSQL 的 `FT_REQUIRE_TEST_POSTGRES=1 FT_TEST_POSTGRES_URL=… uv run pytest -q` 为 `1447 passed, 10 skipped, 1 warning`（579.47 秒）；收支管理 / Web / 投影定向双后端矩阵为 `68 passed, 1 warning`。Web Vitest 为 `46 passed`，`npm run build` 通过，`FT_E2E_WEB_PORT=5175 npm run test:e2e` 为 `11 passed`，`npm run test:visual` 为 `12 passed`，`FT_PREVIEW_API_PORT=8767 npm run test:preview` 为 `3 passed`；视觉快照已按有意的用户文案和字段变化更新并复跑通过。
- **本轮真实浏览器 QA**：2026-08-10 CST，重启 `FT_DATABASE_URL=sqlite+pysqlite:////Users/huangwenlong/.ft/finance-tracker.db`、`FT_WORKSPACE_ID=default`、`FT_WEB_ORIGIN=http://127.0.0.1:5174` 的 API `8766` 和 Vite `5174`；使用真实 `.ft` SQLite 在 1440 × 900、390 × 844 检查查看 / 编辑原位切换、当前流水无重复读取、关联默认前后三天、候选无可见 radio、六渠道导入和返回 / 关闭，页面宽度与视口一致，控制台错误为 0。截图留在 `/tmp/finance-tracker-live-desktop-detail.png`、`/tmp/finance-tracker-live-desktop-edit.png`、`/tmp/finance-tracker-live-mobile-relation.png` 和 `/tmp/finance-tracker-live-mobile-import.png`。
- **本轮产品 / 工程 / 设计复核**：2026-08-10 CST，以 `origin/refactor/web`（`4953f972fe873c87dad219930e804dd3fd58003e`）为比较基线检查当前 diff、领域词表、delta spec、design、prototype、后端契约、Web 文案和截图；修复了投影级“个人转账”误作为单条流水类型展示、空对方账号占位行和关闭抽屉焦点偶发落到 `body` 的问题。未发现 critical / major finding；最终手工 Hallmark audit 因仓库未提供 `hallmark` 可执行文件，按 audit reference 复核 DOM 文案、SVG 图标、焦点、加载 / 错误 / 成功、320 / 375 / 414 / 768 / 1440 px 截图及真实 SQLite 交互，结论 `0 critical · 0 major · 0 minor`。当前比较 HEAD 为 `ea746855d1ad4d019b9f77543cfcc99a69554429`，改动尚未提交、推送或部署。
- **本轮收尾门禁**：2026-08-10 CST，`openspec validate --all --strict` 为 `18 passed`，`openspec doctor`、`git diff --check` 与 Python `compileall` 通过；业务定向回归为 `47 passed, 10 skipped, 1 warning`，Web Vitest 为 `46 passed`，构建、视觉回归（`12 passed`）和预览回归（`3 passed`）通过，E2E 使用空闲端口 `5195` 为 `11 passed`。此前强制 PostgreSQL 完整回归和真实 `.ft` SQLite 浏览器 QA 的证据仍见上方条目。
- **性能门禁扩展验证**：2026-08-11 CST，新增分页附属查询复合索引迁移 `20260811_26`，SQLite 查询计划命中 `ix_cash_projection_members_page_lookup` / `ix_cash_projection_relations_page_lookup`；1,000 条导入四路径与 10,000 条预览、10,000 条账单读取门禁通过 SQLite。受影响后端回归为 `99 passed, 7 skipped, 1 warning`，Web Vitest 为 `46 passed`，生产构建通过，编辑抽屉去重的 Playwright `11 passed` 证据沿用上方条目。
- **性能门禁残余**：同日新建、关键字段编辑、有关联删除二选一的写入 p95 在当前宿主机 Virtualization / Android Emulator 高负载下观测为约 `105–297ms`，未标记通过；递归 CTE 与批量关系更新优化后，2 / 10 / 100 / 1,000 成员关联组取消 / 解散约 `58/135ms / 90/167ms / 270/374ms / 651ms/1.04s`，近似线性规模门禁通过。尝试使用 Docker 测试库时，`docker` API 返回 500，Docker VM 日志记录 `vda` I/O error 和 `containerd` SIGBUS；重启 Docker Desktop 后仍无法完成 PostgreSQL 握手，未设置 `FT_TEST_POSTGRES_URL`，因此 11.7 双后端性能矩阵和 PostgreSQL `EXPLAIN` 保持未完成。
- **递归连通组优化后验证**：2026-08-11 CST，SQLite 定向回归为 `84 passed, 7 skipped, 1 warning`；递归 CTE 的 2 / 10 / 100 / 1,000 成员规模门禁为 `1 passed, 1 skipped`，SQLite 1,000 成员取消 / 解散约 `651ms / 1.04s`；SQLite `EXPLAIN` 命中分页索引和关系端点复合索引，OpenSpec 校验 `18 passed`、doctor、`git diff --check` 和 `compileall` 通过。
- **Homebrew PostgreSQL 测试库**：2026-08-11 CST，因 Docker Desktop 测试库无法完成握手，使用 Homebrew `postgresql@16` `16.14` 建立本机 PostgreSQL；数据目录为 `/opt/homebrew/var/postgresql@16`，服务固定监听 `127.0.0.1:55433`，专用测试库为 `finance_tracker_test`。`FT_TEST_POSTGRES_URL` 只在测试命令进程内临时设置，未写入仓库、未配置密码、未触碰 Docker 数据。
- **Homebrew PostgreSQL 业务矩阵**：2026-08-11 CST，使用上述专用测试库并强制要求 PostgreSQL，收支管理、现金投影、关系投影、迁移和 Web 查询定向矩阵为 `91 passed, 1 warning`；分页 `EXPLAIN` 双后端为 `2 passed`。为 PostgreSQL 显式插入性能夹具同步自增序列，并修正 SQLAlchemy `EXPLAIN` 行对象截断造成的测试误判。
- **Homebrew PostgreSQL 性能结果**：10,000 条读取样本中 `ledger_first` p95 `75.6ms`、游标页 `74.5ms`、筛选页 `121.1ms`、候选搜索 `47.5ms`、候选后续页 `34.3ms`、详情 `61.2ms`；筛选页仍超过 100ms 门禁。1,000 条导入预览 `46.0ms`，首次导入 `41.0s`、重复导入 `33.8s`、来源变化 `75.4s`，首次导入超过 15s 门禁；10,000 条预览 `596.3ms`。因此 11.7 保持未完成，11.1 / 11.3 / 11.4 的 PostgreSQL 性能门禁不宣称通过，未通过放宽阈值掩盖。

- [x] 11.8 针对 PostgreSQL 导入超预算问题，先补现金流水批量合并回归：新增、完全重复、来源变化、`manual_overrides` 保留、已关联来源变化回滚必须与单条入口产生相同结果；随后将导入路径改为批量预取、批量刷新和一次关系影响校验，不放宽导入门禁。`test_cash_import_batch_preserves_idempotency_and_calibration`、导入幂等和已关联来源变化回滚均通过；实现复用单条 `merge_import` 兼容入口，批量预取活跃身份与账户、单次刷新，并把新增流水传给增量投影维护以跳过不可能存在的旧投影查询。
- [x] 11.9 批量导入优化后复跑 SQLite 与 Homebrew PostgreSQL 的 1,000 行四路径、10,000 行预览和受影响业务矩阵；记录优化前后总耗时、行 / 秒和峰值内存，并确认首次导入、重复导入和来源变化均不超过既定门禁。2026-08-11 CST，SQLite 为预览 `52.9ms`、首次 `763.7ms`、重复 `305.6ms`、来源变化 `1.35s`、峰值约 `161.3MB`；PostgreSQL 为预览 `38.3ms`、首次 `2.00s`、重复 `513.1ms`、来源变化 `2.83s`、峰值约 `171.4MB`；PostgreSQL 10,000 行预览 `632.2ms`、峰值约 `172.8MB`。四条 1,000 行导入路径和 10,000 行预览均低于既定门禁；受影响现金管理、投影、关系、迁移和 Web 查询矩阵为 `92 passed, 1 warning`，分页 `EXPLAIN` 双后端为 `2 passed`。
- [x] 11.10 针对 PostgreSQL 写入和关联热路径做查询剖析后，复用已加载实际流水 / 关系详情，合并来源与资金调拨读取，按成员索引先解析投影 ID，避免高基数成员 JOIN 的异常计划；保留完整关系合法性、端点占用、投影维护和事务提交。最终普通新建 / 关键字段编辑 / 无关联删除、关联新增 / 修改 / 取消 / 解散及有关联删除双后端 p95 均低于 100ms。
- **本轮 PostgreSQL 写入门禁**：2026-08-11 CST，强制 Homebrew PostgreSQL `16.14` 专用 `_test` 库执行固定 10,000 条流水、3 次预热、20 次样本；最新代码新建 / 关键字段编辑 / 无关联删除 p95 为 `52.6 / 65.3 / 71.2ms`，最大值为 `52.8 / 85.4 / 81.6ms`；同一 SQLite 运行 p95 为 `17.9 / 29.3 / 64.2ms`，门禁通过。
- **本轮关联门禁与环境证据**：2026-08-11 CST，最新 PostgreSQL 关联新增 / 修改 / 取消 / 解散 p95 为 `108.1 / 148.4 / 159.9 / 147.2ms`，SQLite 同轮为 `27.9 / 33.3 / 21.7 / 32.7ms`；PostgreSQL 运行期间 `pg_stat_activity` 无锁等待或残留测试连接，但 Android Emulator / Virtualization 相关进程约占 `660%` CPU，存在多次 `100–330ms` 尖峰。未将该轮标记为通过，待安静宿主机重跑完整关联 / 删除矩阵。
- **本轮安静宿主机最终关联 / 删除门禁**：2026-08-11 CST，按用户要求强制终止 `emulator-5554` 对应的 QEMU、crashpad 和遗留 `netsimd` 后，运行 `tests/test_cash_projection_performance.py::test_fixed_10k_cash_relation_mutations_meet_100ms_budget` 与 `test_fixed_10k_cash_related_delete_modes_meet_100ms_budget` 的 SQLite / PostgreSQL 双后端组合，结果 `4 passed`。关联新增 / 修改 / 取消 / 解散 p95：SQLite `4.0 / 3.7 / 3.2 / 4.8ms`，PostgreSQL `27.9 / 34.6 / 34.0 / 38.1ms`；有关联关键编辑 / 当前删除并解散 / 删除全部 p95：SQLite `9.7 / 11.4 / 13.7ms`，PostgreSQL `43.8 / 33.7 / 26.7ms`。
- **本轮完整回归边界**：2026-08-11 CST，尝试运行 `uv run pytest -q`；在宿主机高负载和既有长耗时性能夹具下运行约 19 分钟后主动中止。中止前发现的非导入门禁问题为写入 p95 在当前运行中普通关键字段约 `100.2ms`、有关联删除约 `120.7–133.0ms`，仍保留 11.1 / 11.7 未完成；另发现固定投影重建性能测试误调用 `CashProjectionService.update_record`，已改为使用现金账本命令服务，需在安静宿主机单独补跑。该全量命令未作为通过证据，已使用 92 项真实 PostgreSQL 受影响业务矩阵、SQLite 定向矩阵和导入 / 读取性能门禁替代本轮功能结论。
- **导入契约补跑**：2026-08-11 CST，使用 Homebrew PostgreSQL 专用测试库强制运行 `tests/test_postgres_statement_import.py`、幂等、映射和来源行契约集合，`50 passed`；SQLite 相关导入集合此前通过。

## 13. 重新合并 `refactor/web` 与真实 QA（2026-08-14 CST）

- [x] 13.1 先将本地未提交 UI 改动临时保存，再执行 `git merge --no-edit origin/refactor/web`；`5e36df6..3e68980` 快进合并完成。本地 `refactor/web` 没有额外提交，已核对 `HEAD` 与 `origin/refactor/web` 一致；恢复工作树时仅 `web/src/styles.css` 发生冲突，已保留工作区访问样式与移动端流水卡片样式并标记解决。`stash@{0}` 作为恢复前备份保留；本轮未提交、推送或部署。
- [x] 13.2 合并后基础验证：`npm test -- --run` 为 `97 passed`；`VITE_FT_API_ORIGIN=http://127.0.0.1:8767 npm run build` 通过；后端定向矩阵 `uv run pytest tests/test_user_workspace_access.py tests/contract/test_web_api.py tests/integration/test_web_sqlite.py` 为 `42 passed, 4 skipped, 1 warning`；`openspec validate --all --strict` 为 `23 passed`，`openspec doctor` 通过，`git diff --check` 通过。
- [x] 13.3 合并后的 Web 回归：为现有 Playwright fixture 补齐认证会话与并行渲染等待；修复长交易对方文本在桌面表格中的换行溢出。`FT_E2E_WEB_PORT=5190 npm run test:e2e` 为 `26 passed`；视觉快照按远程分支新增工作区 / 账户导航后的有意变化更新，`npm run test:visual` 为 `15 passed`。
- [x] 13.4 使用真实浏览器连接真实 API 与生产构建预览执行 QA。为避免修改真实账本，使用原 `.ft` SQLite 的一次性副本，API 使用 `8767`，生产预览使用 `5185`；在 `1440 × 900` 与 `390 × 844` 检查账本浏览、整卡查看、桌面查看按钮、移动端无查看按钮、复选框不冒泡、工作区管理、分类管理、桌面无横向溢出，以及移动端日期无图标、分类 / 账户 / 流水类型分行。两种视口最终网络失败为空；首次登录的 `401` 与新工作区尚未构建投影时的 `503` 属于预期过渡状态，切换到已有工作区并刷新后不再出现。
- [x] 13.5 最终 Hallmark audit：仓库未提供 `hallmark` 可执行文件，按 `references/verbs/audit.md` 对生产预览最终页面和 `/tmp/finance-tracker-real-qa-final-1440.png`、`/tmp/finance-tracker-real-qa-final-390.png` 进行人工审查；复核图标与文字基线、分类 / 账户分行、日期纯文字、桌面纯文字表头、移动端整卡查看、选择框行为、工作区导航和响应式溢出，结果为 `0 critical · 0 major · 0 minor`。
- [ ] 13.6 本轮未重新执行 PostgreSQL 矩阵：`FT_TEST_POSTGRES_URL` 未配置；本次合并只涉及 Web / 工作区访问与展示回归，既有 PostgreSQL 业务与性能证据仍保留在前述条目，不能将本条标记为新的双后端通过。

## 14. 本轮快捷操作与多选删除

- [x] 14.1 需求澄清门禁：仓库内 `grill-me` 只声明运行时 `/grilling` session，当前运行时没有可调用入口；因此沿用本轮对话中已确认的范围并写入本 change——已有单条编辑 / 删除、关联管理和批量分类保持不变；本轮新增行级快捷菜单和投影级多选删除；不做批量改账户 / 金额 / 类型、复制、撤销或全量筛选选择；批量删除整笔收支详情的语义和风险已记录。
- [x] 14.2 已更新 proposal、delta spec、design、prototype 与本任务的验收映射；“已有能力 / 本轮新增能力”分开列明，原型覆盖行菜单、选择工具栏、影响确认、版本冲突、空选择、加载和错误状态。
- [x] 14.3 已先补 Application Service / Web 失败回归：批量影响读取、无关联删除、含关联整组删除、混合选择、空集合、成员重叠、跨工作区 / 隐藏投影、版本冲突、确认缺失、响应脱敏和失败后完全回滚；SQLite 通过，PostgreSQL 因 `FT_TEST_POSTGRES_URL` 未配置而保留未完成项。
- [x] 14.4 已实现投影级批量删除 Application Service：按 `projection_ids + projection_version` 解析活动成员，集合读取关系 / 账户 / 余额，在单事务内清理派生投影、关系和现金流水；返回影响摘要、删除数量和新投影版本，未逐条开启独立事务。
- [x] 14.5 已增加批量删除影响与提交 Web API，覆盖工作区隔离、版本锁、确认字段、错误代码和响应脱敏；浏览器只提交投影选择边界，不提交事实 / 关系 ID，也不展示任何内部 ID；成功响应已移除 `deleted_fact_ids`。
- [x] 14.6 已补 Web Vitest / Playwright 断言：行级菜单键盘可达、菜单操作不冒泡、已有能力入口映射、选择工具栏删除、影响摘要计数、确认提交、版本冲突刷新并重新选择，以及 320 / 375 / 390 / 414 / 768 / 1440 px 响应式检查。
- [x] 14.7 已实现桌面与窄屏行级快捷菜单和多选删除工具栏；复用已有查看 / 编辑 / 单条分类 / 单条删除 / 批量分类入口；删除确认只显示收支记录数、流水数和关联组数，并明确关联组整体删除语义。
- [x] 14.8 已更新原型与 design 的 UI 状态覆盖，并按 UI 规则完成可见文字、焦点、危险操作二次确认、操作栏遮挡和响应式手工检查；批量确认层补充背景 `inert` 与初始焦点。运行时没有可调用 Hallmark `audit` 入口，已按 audit reference 进行人工替代复核并记录 `0 critical · 0 major · 0 minor`。
- [x] 14.9 已完成独立产品 / 工程 / 设计 / 安全与最终 diff 复核：确认删除边界为投影完整成员集合、跨工作区和版本均在服务端校验、关系先于外键事实清理、余额快照在同一事务处理、无批量部分成功路径；未发现 critical / major finding。
- [ ] 14.10 已执行受影响 SQLite 回归、Web Vitest、生产构建、Playwright 主流程 / 视觉 QA、OpenSpec 校验、`openspec doctor`、`git diff --check` 和原型脚本检查；真实 PostgreSQL 契约矩阵因 `FT_TEST_POSTGRES_URL` 未配置未完成，故本项保留未勾选。全量 Python 回归为 `1441 passed, 179 skipped, 13 failed`（失败集中在既有测试包导入冲突、投资 / 关系测试和一条既有数据断言）；完整 e2e 为 `27 passed, 1 failed`，唯一失败是既有深色侧栏文案数量断言，详见本节验证记录。

### 14.10 验证记录

- 当前 `HEAD`：`921e18f0899663e6a8187e6a2e3f214aec725ab3`；与 `origin/refactor/web` 的 merge-base 相同；所有本轮代码、测试、原型和 OpenSpec 改动仍未提交、推送或部署。
- SQLite / Application Service：`PYTHONPATH=src pytest -q tests/test_cash_ledger_management.py tests/test_application_web_queries.py` → `59 passed, 7 skipped, 1 warning`；批量删除定向矩阵 → `6 passed, 1 skipped`。全量 `PYTHONPATH=src pytest -q` → `1441 passed, 179 skipped, 13 failed`，失败均不在本轮现金账单定向文件；`FT_TEST_POSTGRES_URL` 未配置，PostgreSQL 契约矩阵未执行。
- Web：`npm test -- --run` → `112 passed`；`npm run build` → 通过；`FT_E2E_WEB_PORT=5299 npx playwright test -c playwright.config.ts -g '行级菜单和多选删除|详情切换编辑、维护关联'` → `2 passed`（含确认层焦点断言）；`npm run test:visual` → `15 passed`（快照已按有意的操作菜单与窄屏网格变化更新）。完整 `FT_E2E_WEB_PORT=5299 npm run test:e2e` → `27 passed, 1 failed`，唯一失败为既有 `cash-category-management.e2e.ts` 深色侧栏文案数量断言（期望 7、当前页面 8），本轮未改动该导航或断言。
- 真实浏览器 QA：生产预览静态检查覆盖 320 / 375 / 414 / 768 / 1440 / 390 px；菜单、键盘 Escape、选择工具栏、影响确认、空状态和无横向溢出通过。新增截图：`web/test-results/cash-ledger.e2e.ts-行级菜单和多选删除在桌面与窄屏保持可操作/cash-row-menu-390.png`、`web/test-results/cash-ledger.e2e.ts-行级菜单和多选删除在桌面与窄屏保持可操作/cash-batch-delete-impact-390.png`。控制台和网络错误为空。
- 规格 / 静态检查：`openspec validate --all --strict` → `26 passed, 0 failed`；`openspec doctor` → Root ok；`git diff --check`、Python `compileall`、原型内联脚本语法检查均通过。
