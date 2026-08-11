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
- [x] 2.7 根据真实投资事件页面截图回流原型：删除重复标题，以事件数量直接衔接列表；用 18 位小数、长备注和多类事件检验列宽、行高、数值层次和筛选开合状态，并完成 Hallmark 初审与修改。
- [x] 2.8 根据用户反馈改为按经济效果展示资产：交易使用“流出 / 流入”两行，其他事件只显示实际变化的一行，现金/持仓快照使用“余额 / 持有”；同步事件原型并重新执行 Hallmark 审查。
- [x] 2.9 以 PR #42 提交 `24e8f317435a481d83bb280c6e9905ef961c8f95` 的收支账单页为视觉基准，同步两个投资原型的共享 token、品牌、导航当前态、筛选折叠区、表格/手机卡片、焦点与按钮状态，并以同源线性 SVG 替换 Unicode 和文字式伪图标。
- [x] 2.10 按用户确认的方案 A 重做投资详情原型：单一连续面板、事件类型作标题、时间与投资账户只出现一次、资产变化为主体、现金账户和备注按需补充，不显示“资金流向 / 更多信息”或重复事实。
- [x] 2.11 根据用户反馈调整事件列表移动端入口：整张卡片可点击，隐藏眼睛图标；桌面表格保留眼睛图标，并保持键盘与焦点语义。
- [x] 2.12 根据用户反馈调整流入/流出数值：流出使用 `-`、流入使用 `+`，并复用收支账单的字体、字号、字重和语义颜色。

## 3. 任务拆分与一致性

- [x] 3.1 为每条 requirement 和 scenario 建立失败测试、实现、审查与验证任务映射，确认原型、proposal、spec 和 design 无矛盾。
- [x] 3.2 固定 SQLite 与真实 PostgreSQL 契约夹具、工作区隔离样例、精确十进制样例、版本化分页和行情局部失败验收数据。
- [x] 3.3 为用户术语建立回归断言：渲染结果不得出现内部枚举、规则编号或技术标题；详情、空、错误、键盘和响应式状态仍保持可访问。
- [x] 3.4 为负成本、均摊合并、跨币种禁止合并、周期资产流量、买卖/出入金区分、总览分母和 10 秒轮询建立失败测试与契约映射。
- [x] 3.5 为仓位含现金分母、币种自由输入及无效值恢复、刷新图标忙碌/减少动态效果、总览无解释句、用户可见禁用术语和不拆分周期盈亏建立失败测试与 requirement 映射。
- [x] 3.6 为周期总额包含浮盈亏与已实现盈亏、完全卖出标的计入总额、单标的资金流调整、内部账户转移排除和带时间权重的盈亏率建立失败测试与 requirement 映射。
- [x] 3.7 将本轮事件页问题映射为待用户确认后的失败测试：十进制字符串紧凑格式化、极小非零值、币种/标的大写、唯一页面标题、筛选开合文案和真实数据桌面视觉快照。
- [x] 3.8 增加待用户确认后的事件资产展示测试映射：买卖双侧、收入/费用/出入金单侧、快照零值保留、结构占位零隐藏、零手续费显示 `—`，以及列表和详情不出现“付出 / 换入”。
- [x] 3.9 将跨账本视觉一致性映射为原型与待实现检查：同一动作使用同一 SVG 图标和控件状态，共享 token 不漂移，投资页保留分级导航；生产实现前重新读取 PR #42 最新提交并决定直接复用或先同步基准。
- [x] 3.10 为投资详情去重建立测试映射：相同时间、金额、操作、数量和备注各只出现一次；关联现金记录只补充不同账户或其他不同事实；交易双侧、非交易单侧、零手续费、无现金账户和无备注状态均不生成空分区。
- [x] 3.11 为移动端整卡入口建立验证映射：眼睛图标在 320/375/414/768 px 不可见，卡片任意位置可打开详情，键盘可聚焦且关闭后焦点返回卡片入口；桌面操作列仍可见。
- [x] 3.12 为流入/流出语义样式建立验证映射：列表和详情的流出/流入均带正确符号，使用收支账单同源字体、字号、字重和颜色；余额与手续费不误用方向符号。
- [x] 3.13 为本 change 补齐性能一致性映射：固定 20,000 条投资事件、1,000 条资金关系和 128 个持仓，覆盖事件列表、事件详情和持仓查询 p95；浏览器覆盖基线对比、FCP、DOM 完成、首屏资源、请求数和绝对预算。

## 4. 构建

- [x] 4.1 先增加失败测试，再实现投资事件筛选、稳定分页和当前页批量关系摘要的 Application Service 与 persistence adapter。
- [x] 4.2 先增加失败测试，再实现工作区隔离的投资事件证据详情与脱敏 Web API。
- [x] 4.3 先增加失败测试，再复用组合查询实现当前持仓与有界估值状态响应，确保局部行情失败不阻塞事件列表。
- [x] 4.4 以已批准原型实现投资事件、当前持仓、筛选、分页和证据详情 Web 交互，保持十进制字符串和浏览器本地时间语义。
- [x] 4.5 根据本轮反馈将当前持仓从逐卡片 `quote_status` 展示改为整体成功/失败状态，并保留内部诊断字段只供查询和日志使用。
- [x] 4.6 按简化文案策略实现用户界面：事件类型使用“买入 / 卖出”等短标签，详情以“资产变动”为主体并按需补充现金账户和备注，隐藏导入渠道、来源字段名和规则编号；不改变 API、查询或账务语义。
- [x] 4.7 先增加失败测试，再实现表现查询：当前/边界资产值、24 小时和可选周期、买卖影响、外部出入金扣除、浮盈亏与已实现盈亏合计、完全卖出标的、单标的资金流调整、精确十进制比率与负成本。
- [x] 4.8 先增加失败测试，再实现持仓分组、账户筛选、标的排序、同标的合并/禁止跨成本币种合并、原始/统一展示币种和总览口径。
- [x] 4.9 先增加失败测试，再实现 10 秒可见性轮询、手动刷新、取消过期请求、首次失败与后续失败恢复，以及浏览器本地偏好还原。
- [x] 4.10 以更新后的双页面原型实现当前持仓页和投资事件页：持仓页包含总览、表格、移动端卡片、周期切换、展示设置、刷新按钮和统一成功/失败状态；事件页包含筛选、分页和详情；不暴露覆盖状态枚举。
- [x] 4.11 以失败测试驱动用户界面精简：将“均摊成本 / 表现周期”映射为“平均成本 / 时间范围”，删除更新时间卡和指标辅助句，实现可输入币种、刷新按钮圆形忙碌反馈及“当前市值 / 仓位”组合字段。
- [x] 4.12 为浏览器持仓偏好增加兼容读取：旧版下拉币种值可迁移为输入值，无效旧值回退到原币种，且不影响账户、排序、合并和时间范围偏好。
- [x] 4.13 以失败测试驱动持仓展示重设计：桌面端使用 9 列持仓表格，浮盈亏四列独立展示且字号一致、通过字重和颜色突出；820 px 及以下将表格行转换为保留全部字段的持仓卡片。
- [x] 4.14 先增加统一 SPA 外壳的失败测试，再将侧边导航和页面框架提升到 `App`，让收支账本、当前持仓和投资事件只渲染主内容区；详情抽屉放在不可交互背景之外。
- [x] 4.15 先增加移动端菜单折叠/展开与选路由后自动收起的失败测试，再让共享 `App` 外壳在移动端折叠导航、桌面端保持常驻。
- [x] 4.16 用户确认本轮原型后，先重新确认 PR #42 最新提交并增加失败测试，再实现投资事件十进制字符串显示格式化、按经济效果选择资产行、流出/流入及快照标签、列宽/行高、唯一标题和可靠的筛选开合；直接复用目标分支已有的 `UiIcon` 与共享视觉 token，不改 API、数据库或账务数值。
- [x] 4.17 用户确认重做后的详情原型后，先增加失败测试，再将生产投资详情改为单一连续事实表并按业务含义去重；不改变详情 API、关系查询、工作区隔离或账务事实。
- [x] 4.18 先增加固定负载性能回归，再实现/保留投资浏览查询门禁：事件列表 p95 ≤ 750ms、事件详情 p95 ≤ 500ms、20,000 条事件历史的持仓查询 p95 ≤ 3s；夹具校验事件与关系数量，避免性能测试因负载漂移而失效。

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
- [x] 5.10 对统一 SPA 外壳和两级侧边导航重新执行 Hallmark `audit` 技能动作，检查三个路由的导航一致性、当前项、移动端布局和详情抽屉背景隔离；结果为 0 critical、0 major、0 minor。
- [x] 5.11 对移动端折叠菜单执行 Hallmark `audit` 技能动作，检查默认状态、展开/收起层次、触摸目标、当前项、键盘焦点、详情抽屉隔离和 320/375/414/768 px 布局；结果为 0 critical、0 major、0 minor。
- [x] 5.12 用户确认并完成生产实现后，对真实数据投资事件页重新执行 Hallmark `audit` 技能动作，覆盖唯一标题、数据密度、筛选开合、数字换行、桌面表格和移动端卡片；修复全部 critical 与 major finding 后复审。
- [x] 5.13 对齐 PR #42 后重新执行原型 Hallmark `audit` 技能动作，覆盖跨账本视觉一致性、SVG 图标语义、两级导航、桌面表格、移动端折叠菜单和四个目标宽度；最终结果为 0 critical、0 major、0 minor。
- [x] 5.14 对重做后的投资详情原型执行 Hallmark `audit` 技能动作，检查连续层次、去重、资产主次、空分区、桌面/手机密度、模态隔离和焦点恢复；最终结果为 0 critical、0 major、0 minor。
- [x] 5.15 对移动端整卡详情入口执行 Hallmark `audit` 技能动作，检查卡片命中区域、图标隐藏后的可发现性、键盘焦点、桌面端图标保留和响应式密度；最终结果为 0 critical、0 major、0 minor。
- [x] 5.16 对流入/流出符号和收支账单视觉对齐执行 Hallmark `audit` 技能动作，检查列表/详情的字体、字号、字重、颜色、符号和余额/手续费边界；最终结果为 0 critical、0 major、0 minor。
- [x] 5.17 完成独立性能复核：比较 `origin/refactor/web` 与当前构建的真实浏览器指标，检查后端固定负载 p95、首屏 FCP/DOM、JS/CSS、请求数和传输量；区分本 PR 增量与基线已有字体传输负担，并将 finding 与取舍回写本 change。

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
- [x] 6.11 验证收支账本、当前持仓和投资事件路由共用同一导航外壳，验证详情打开时背景不可交互、关闭后焦点恢复，并检查 320/375/414/768 px 无横向溢出。
- [x] 6.12 验证移动端菜单默认折叠、展开、路由切换后自动收起、键盘可达、详情打开时不可交互和 320/375/414/768 px 无横向溢出；更新对应视觉快照。
- [x] 6.13 用户确认并完成生产实现后，运行事件页受影响 Vitest、完整 Web Vitest、构建、真实数据 Playwright 视觉快照、生产预览、OpenSpec 严格校验、`openspec doctor` 和 `git diff --check`。
- [x] 6.14 对齐后的双页原型在桌面与 320、375、414、768 px 执行截图和交互检查：共享 token 生效，筛选 chevron 正确旋转，详情 eye 与关闭 x 可访问，移动菜单默认折叠且无页面级横向滚动。
- [x] 6.15 验证重做后的详情在 320、375、414、768 px 无横向溢出或重复文本；标题、时间、账户、资产变化、手续费、现金账户和备注顺序正确，Esc 关闭、背景 `inert` 与焦点返回保持有效。
- [x] 6.16 验证移动端事件卡片整卡点击、眼睛图标隐藏、键盘焦点和桌面眼睛图标保留。
- [x] 6.17 验证流出/流入符号和视觉语义在列表、详情、移动端和桌面端一致，余额与手续费保持无方向符号。
- [x] 6.18 执行性能门禁与回归：`uv run pytest -q -s tests/test_cash_projection_performance.py tests/test_wealth_performance.py tests/test_investment_web_performance.py` → SQLite 3 个性能场景通过，PostgreSQL 5 个参数化场景因 Docker Desktop 不可用而按既有规则跳过；生产构建基线/当前均通过 FCP、DOM、JS、CSS 和请求数绝对预算，当前 PR 相对基线未触发 timing、bundle 或 request regression；总传输量约 2.40 MB 的绝对示例预算在基线同样超出，归因为既有 Noto Sans SC 字体，不作为本 PR 回归。

## 7. 发布准备

- [x] 7.1 记录路由与 API 回滚、观察项和未解决风险；未经用户明确授权不提交、不推送、不创建 PR、不部署。
- [x] 7.2 完成交付交接记录：列出改动文件、验证命令、基线、残余风险、未执行项、下一步 archive 条件和外部写授权边界。
- [x] 7.3 发布准备时记录浏览器偏好键兼容与回滚方式、轮询失败观察项，以及移除旧占位字段或文案的兼容检查。
- [x] 7.4 交付性能证据：保留 `.gstack/benchmark-reports/2026-08-11-investment-ledger-browser.json` 与 `.md` 及基线文件，记录实际命令、当前 `HEAD`、比较基线、Docker 阻断条件、残余风险和补跑 PostgreSQL 的准确条件。

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
- **本轮统一 SPA 外壳回流**：用户指出收支账本和投资账本不应各自拥有一套页面外壳；已将约束回写 proposal、spec、design 和 tasks。实现边界为 `App` 统一渲染侧边导航与页面框架，路由仅切换主内容区，详情抽屉通过门户放在不可交互背景之外；本轮代码修正前先补充统一导航、模态隔离和响应式验证任务。
- **本轮统一 SPA 外壳实现与审查**：`App` 现在统一渲染收支账本、投资账本、当前持仓和投资事件的共享侧边导航；页面组件只渲染内容，详情抽屉通过门户置于不可交互背景之外。共享导航样式已从投资页面选择器提升为全局账本导航选择器。Hallmark audit 技能复核覆盖结构、当前项、移动端布局和抽屉背景隔离，结果为 **0 critical、0 major、0 minor**。
- **本轮移动端导航反馈回流**：用户指出移动端把桌面导航压缩成一排不够美观；已将目标收敛为移动端默认折叠、点击“菜单”展开同一棵分级导航、选择路由后自动收起，桌面端仍常驻侧栏。已回写 proposal、delta spec、design 和 tasks，并按 TDD 实现；菜单选择后焦点回到菜单按钮，避免落在隐藏链接上。
- **本轮移动端导航实现与审查**：`App` 增加共享菜单状态、动态无障碍名称和 `aria-expanded`，移动端 CSS 默认隐藏导航、展开时以单列分级菜单显示，桌面端保持常驻；真实预览覆盖展开与选路由收起。Hallmark audit 技能复核默认状态、触摸目标、当前项、键盘焦点、详情隔离和四个响应式宽度，结果为 **0 critical、0 major、0 minor**。
- **本轮真实事件表反馈回流**：用户提供的生产截图暴露出此前审查只覆盖导航、响应式和短演示数据，没有覆盖真实桌面数据密度。Hallmark 初审记录 **0 critical、5 major、1 minor**：`major / content-shape failure`（18 位定长小数逐字折行，金额不可读）；`major / hierarchy duplication`（页面标题与列表标题重复“投资事件”）；`major / control wrap`（筛选“展开”竖排）；`major / table rhythm failure`（资产、手续费、备注和详情入口缺少稳定列宽与行内层次）；`major / responsive shell drift`（事件原型手机端仍摊开完整导航，与已确认的折叠菜单不一致）；`minor / unfinished header`（详情列为空表头）。已回写 proposal、delta spec、design、tasks 和 `prototype/events.html`；原型修改后复审为 **0 critical、0 major、0 minor**。按用户既定门禁，生产代码与测试等待用户确认原型后再写。
- **本轮资产文案与行数回流**：用户指出绝大多数非交易事件只有一侧资产，不应固定渲染“付出 / 换入”两行。领域复核后采用“流出 / 流入”作为资产方向的用户文案，避免与经济事实类型“收入 / 支出”混淆；快照使用“余额 / 持有”。原型同时覆盖交易双侧、收入单侧、快照单侧和零手续费。Hallmark 初审发现 `major / semantic repetition`：单侧事件仍保留空方向会制造不存在的第二项；复审又发现 `minor / orphan placeholder`：手机卡片中的零手续费只剩无上下文的 `—`。前者通过按经济效果只渲染一侧修复，后者在手机卡片中隐藏空手续费、桌面表格保留占位；最终复审结果为 **0 critical、0 major、0 minor**。生产代码与测试继续等待用户确认。
- **本轮表格原型与审查**：仅修改 `prototype/index.html`、proposal、delta spec、design 和 tasks，未修改生产代码。当前持仓原型已按“总浮盈亏 → 近 24 小时浮盈亏 → 当前总市值”排列；桌面端使用“标的 / 账户、当前单价、数量、当前市值、仓位、浮盈亏、浮盈亏率、近 24 小时盈亏、近 24 小时盈亏率”9 列表格；四个盈亏字段为独立列且数据字号相同，仅以字重和颜色建立层次；820 px 及以下转为保留全部字段的持仓卡片。Hallmark `audit` 技能动作最终结果为 **0 critical、0 major、0 minor**。原型中的单标的“近 24 小时盈亏”金额因演示数据未提供该项而显示 `—`，避免虚构数值，近 24 小时盈亏率仍展示为演示值。
- **PR #42 视觉基准回流（2026-08-10，Asia/Shanghai）**：只读检查 PR #42 提交 `24e8f317435a481d83bb280c6e9905ef961c8f95` 的收支账单页、`UiIcon`、样式和 1440 × 900 / 390 × 844 视觉快照；未切换分支、未修改生产代码。新增 `prototype/tokens.css` 作为两页共享 Cobalt token，并同步品牌、侧栏当前态、深色表头、表格阴影、折叠区、按钮与焦点状态。筛选、展开、详情和关闭直接使用与 PR #42 相同的 sliders、chevron、eye、x SVG，刷新使用相同 24 px、`currentColor`、2 px 圆角描边参数，不再使用 Unicode 伪图标。投资页继续保留两级导航、移动折叠菜单、持仓 9 列/手机卡片和事件单侧/交易双侧资产语义。
- **PR #42 对齐原型 Hallmark audit**：目标为 `prototype/index.html`、`prototype/events.html` 和 `prototype/tokens.css`。结构戳与实际 Workbench / Ledger Grid 相符；不存在 AI hero、三等分功能卡、emoji、渐变、混用图标集、内联颜色或假浏览器外壳。Cobalt 文本组合 WCAG 对比度最低为 faint / paper **4.57:1**，正文与图标门槛通过；320、375、414、768 px 均无横向滚动和两行按钮。最终结果为 **0 critical · 0 major · 0 minor**。
- **PR #42 对齐原型浏览器证据**：`browse` 本地文件检查在 320、375、414、768 px 对两页均返回 `overflow=false`、菜单可见且导航默认隐藏、`twoLineButtons=false`；桌面 1440 × 900 截图为 `holdings-pr42-desktop.png`、`events-pr42-desktop.png`，手机 390 × 844 截图为 `holdings-pr42-mobile.png`、`events-pr42-mobile.png` 与 `events-pr42-detail-mobile.png`。筛选/显示 chevron 展开后旋转 180°；详情打开后 `drawerVisible=true`、`backgroundInert=true`、关闭按钮获得焦点，Esc 关闭后背景恢复且焦点回到原 eye 按钮；控制台无错误。生产实现仍等待用户确认本轮原型。
- **投资详情方案 A 原型与审查（2026-08-10，Asia/Shanghai）**：用户确认采用单一连续详情面板。`prototype/events.html` 以事件类型作为标题，时间与投资账户合并为一行；资产变化是唯一主信息区，交易显示双侧、快照与收入显示单侧，非零手续费紧随其后；分隔线下仅补充未出现的现金账户与备注。买入示例中的事件类型、时间、账户、资产金额、数量、手续费、现金账户和备注均只出现一次，不再显示“资金流向 / 更多信息 / 操作 / 数量”等重复分组或字段。四类事件动态样例分别得到“买入＝流出/流入/手续费＋现金账户/备注”“记录＝余额＋备注”“卖出＝流出/流入/手续费＋备注”“收入＝流入＋备注”，均无空行或空分区。Hallmark `audit` 技能动作覆盖连续层次、资产主次、语义去重、桌面/手机密度、图标、模态隔离和焦点恢复，最终结果为 **0 critical、0 major、0 minor**。
- **投资详情方案 A 浏览器证据**：320、375、414、768 px 均为 `overflow=false`、`drawerOverflow=false`，关闭按钮可见且没有两行按钮；390 × 844 下 Esc 关闭后抽屉隐藏、共享外壳解除 `inert`，焦点返回原详情按钮；控制台无错误。截图为 `events-detail-redesign-desktop.png`、`events-detail-redesign-panel-desktop.png`、`events-detail-redesign-mobile.png` 和 `events-detail-redesign-panel-mobile.png`。本轮只更新原型与 change artifacts，`4.17` 的生产实现继续等待用户确认。
- **投资详情方案 A 验证记录**：当前 `HEAD` 为 `5b1106027ab3ea78de4ed2ebce22c93ce53962a9`，与 `origin/refactor/web` 的比较基线为 `4953f972fe873c87dad219930e804dd3fd58003e`。`openspec validate --all --strict` → **17 passed、0 failed**；`openspec doctor` → **root ok**；`git diff --check` → **通过**；`git diff -- web` → **空**，确认未修改生产代码。生产实现、生产测试、提交和外部写均未执行。
- **移动端整卡入口回流（2026-08-10，Asia/Shanghai）**：用户要求移动端去掉眼睛图标并让整张事件卡片可点击。原型保留同一个可访问详情按钮作为全卡覆盖入口，移动端隐藏其 SVG 图标，桌面端继续显示眼睛图标。390 px 浏览器检查确认首张卡片的入口覆盖整张卡片（按钮区域 `372 × 138`，与卡片内容区域一致），卡片中心命中元素为详情按钮；详情可打开，Esc 后焦点返回入口，控制台无错误。移动端截图为 `events-card-mobile.png`，桌面截图为 `events-card-desktop.png`。
- **移动端整卡入口 Hallmark audit 与验证**：Hallmark 审查结果为 **0 critical、0 major、0 minor**。320、375、414、768 px 均无页面级横向溢出；四个宽度下移动端眼睛图标均隐藏，入口保持可聚焦；1024 px 桌面端眼睛图标仍可见。`openspec validate --all --strict` → **17 passed、0 failed**；`openspec doctor` → **root ok**；`git diff --check` → **通过**；`git diff -- web` → **空**。生产代码与生产测试仍等待用户确认原型后执行。
- **流入/流出符号与收支视觉对齐（2026-08-10，Asia/Shanghai）**：原型列表和详情均使用 `-` 表示流出、`+` 表示流入；两者统一为 `IBM Plex Mono`、13px、600 字重，并使用收支账单当前的支出色/收入色。余额与手续费不带方向符号，且保留普通灰色样式。浏览器检查在 390 px 和 1024 px 通过，控制台无错误；截图已更新为 `events-card-mobile.png`、`events-card-desktop.png`、`events-detail-redesign-mobile.png`、`events-detail-redesign-panel-mobile.png`、`events-detail-redesign-desktop.png` 和 `events-detail-redesign-panel-desktop.png`。Hallmark `audit` 结果为 **0 critical、0 major、0 minor**。
- **PR #42 对齐验证记录**：执行时间为 2026-08-10 19:55 CST；当前分支 `investment-account-page`，`HEAD` 为 `5b1106027ab3ea78de4ed2ebce22c93ce53962a9`，与 `origin/refactor/web` 的比较基线为 `4953f972fe873c87dad219930e804dd3fd58003e`。`openspec --version` → **1.7.0**；`openspec validate --all --strict` → **17 passed、0 failed**；`openspec doctor` → **root ok**；`git diff --check` → **通过**；`git diff -- web` → **空**，确认本轮未修改生产代码。残余条件为 PR #42 仍可能更新，生产实现前必须重新读取其最新 `headRefOid` 并在用户确认原型后再开始。
- **本轮周期盈亏算法确认**：用户确认采用“总资产快照法 + 单标的资金流调整法”。总周期盈亏为 `期末资产总额 - 期初资产总额 - 外部入金 + 外部出金`，因此同时包含浮盈亏和期间已实现盈亏；单标的周期盈亏为 `期末市值 - 期初市值 - 期间买入支出 + 期间卖出净收入 + 投资收入 - 相关费用`；买卖、分红和费用的精确边界写入 spec/design。完全卖出的标的不进入当前持仓表，但计入总周期盈亏；不单独展示已实现盈亏指标。周期盈亏率使用带时间权重的投入基数。用户已确认该算法，允许进入失败测试与生产实现。
- **原型与设计复核**：`prototype/index.html` 与 `prototype/events.html` 使用 Cobalt / modern-minimal / Workbench-Ledger Grid，分别覆盖持仓总览、展示设置、刷新、加载/失败状态，以及事件筛选、列表、分页、空/错状态和证据抽屉；两页均通过 320、375、414、768 px 无页面级横向滚动检查。生产 UI 复用两级侧边导航和独立页面信息架构。
- **产品/范围复核**：只读 API 与页面覆盖投资事件筛选、稳定分页、批量关系摘要、证据详情和当前持仓估值；未新增写入、导入、迁移或估值规则，未修改主规格。回滚边界为移除投资页面 hash 路由、三个投资 API 路由和对应查询服务装配；无数据回滚动作。
- **工程/安全复核**：当前页关系使用单次批量查询；游标绑定 workspace、筛选、排序位置和 `LedgerSnapshotModel.version`，版本变化返回 `investment.updated`；事件和证据查询双重限制 workspace；来源快照使用业务字段白名单并过滤敏感键；估值复用 `PortfolioQueryService` 的共享预算和局部状态。未发现阻断性 finding。
- **Hallmark audit**：目标为 `web/src/pages/InvestmentLedgerPage.tsx`、`web/src/components/Investment*.tsx`、`web/src/styles.css` 投资账本区段和 `prototype/index.html`。已按 `.agents/skills/hallmark/references/verbs/audit.md` 完成两轮技能审查；结构戳与 Workbench / Ledger Grid 一致。首轮 finding 按 audit tell 记录为：`major / Eyebrow on every section`（原型各分区标题附近，移除重复眉题）；`major / Invented metrics`（原型事件区原有未标注的数量文案，改为“示例数据”）；`major / Mid-render token improvisation`（原型抽屉、遮罩和导航样式的内联颜色，统一回写 token）；另有一项范围一致性 finding（原型与生产文案漂移，已同步用户术语、状态和详情结构）。上述 finding 全部采纳并回写原型与 `design.md`；最终复核结果为 **0 critical、0 major、0 minor**。`audit` 是 Hallmark 技能动作，不是 shell 命令。
- **本轮生产实现与审查（2026-08-10 21:38 CST）**：用户确认原型后，按 TDD 先让旧断言失败，再实现 `web/src/investmentDisplay.ts`、投资事件表的经济效果行、精确十进制紧凑显示、方向符号、筛选 SVG 图标、移动端整卡入口和单一连续详情面板。详情标题改为事件类型；资产变动、手续费、现金账户、现金金额和备注按事实去重，未改 API、数据库或账务数值。Hallmark audit 技能动作目标为 `web/src/components/InvestmentTable.tsx`、`InvestmentEvidenceDetail.tsx`、`InvestmentFilters.tsx`、`web/src/styles.css` 和生产预览事件页，覆盖唯一标题、数据密度、筛选开合、数字换行、桌面表格、移动端卡片、详情抽屉和模态焦点；未发现 critical、major 或 minor finding，最终 **0 critical、0 major、0 minor**。该审查使用 Hallmark 技能动作，不是 shell 命令。
- **用户反馈回补**：投资详情以“资产变动”为主体，按需补充现金账户和备注；收支详情使用“相关记录”，用户界面不再显示关系、投影、来源渠道、规则编号或“已配对”。本轮进一步要求当前持仓只呈现整体读取成功或失败，不再把 `complete / stale / partial / unsupported` 映射成逐项状态；原型与生产 UI 已同步。
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
- **统一 SPA 外壳最终验证（2026-08-10，Asia/Shanghai）**：新增 `web/tests/app-shell.test.tsx`；`npm test -- --run tests/app-shell.test.tsx` → **2 passed**；受影响 Web 测试 → **39 passed**；完整 Web Vitest → **52 passed**；`npm run build` → 通过；`npm run test:visual` → **10 passed**；`FT_PREVIEW_API_PORT=8892 FT_PREVIEW_WEB_PORT=5192 npm run test:preview` → **5 passed**，新增真实路由切换覆盖收支账本、当前持仓、投资事件共享导航；`openspec validate --all --strict` → **17 passed**；`openspec doctor` → **root ok**；`git diff --check` → **通过**。本次提交与现有 PR #41 的更新沿用用户已授权的外部写操作边界。
- **移动端折叠菜单最终验证（2026-08-10，Asia/Shanghai）**：`npm test -- --run tests/app-shell.test.tsx` → **3 passed**；完整 Web Vitest → **53 passed**；`npm run build` → 通过；`npm run test:visual -- --update-snapshots` 更新移动端菜单收起状态快照，随后 `npm run test:visual` → **10 passed**；`FT_PREVIEW_API_PORT=8892 FT_PREVIEW_WEB_PORT=5192 npm run test:preview` → **6 passed**，覆盖移动端展开、选路由自动收起和键盘焦点回收。Hallmark audit 技能动作 → **0 critical、0 major、0 minor**；`openspec validate --all --strict` → **17 passed**；`openspec doctor` → **root ok**；`git diff --check` → **通过**。
- **本轮生产验证（2026-08-10 21:38 CST）**：当前 `HEAD` 为 `5b1106027ab3ea78de4ed2ebce22c93ce53962a9`，比较基线为 `4953f972fe873c87dad219930e804dd3fd58003e9`，工作树未提交。`npm test -- --run tests/InvestmentLedgerPage.test.tsx` → **8 passed**；`npm test -- --run` → **54 passed**；`npm run build` → 通过；`npm run test:visual` → **10 passed**；`FT_PREVIEW_API_PORT=8871 FT_PREVIEW_WEB_PORT=5183 npm run test:preview` → **6 passed**，覆盖桌面/移动详情入口、抽屉进入视口、流出/流入符号、详情去重、共享导航、移动菜单和 320/375/414/768 px 无横向滚动；`openspec validate --all --strict` → **17 passed、0 failed**；`openspec doctor` → **root ok**；`git diff --check` → **通过**。未运行双后端矩阵：本轮只修改 Web 展示、交互和测试，没有改 API、数据库、持久化或账务计算；仍保留既有 Docker PostgreSQL 验证记录。未提交、未推送、未创建 PR、未部署，也未归档 change。
- **本轮性能门禁补全与执行（2026-08-11 16:43 CST）**：新增 `tests/test_investment_web_performance.py`，固定验证 20,000 条投资事件、1,000 条资金关系、4 个投资账户共 128 个持仓；事件列表与详情各取 12 次样本，持仓历史查询各取 12 次样本。`uv run pytest -q -s tests/test_cash_projection_performance.py tests/test_wealth_performance.py tests/test_investment_web_performance.py` → **6 passed、5 skipped**；SQLite 实测：收支投影 p95 **1.583s**（预算 10s）、财富重建 p95 **3.964s**（预算 5s）、财富热读 p95 **42.6ms**（预算 300ms）、投资事件列表 p95 **1.689ms**（预算 750ms）、投资详情 p95 **0.591ms**（预算 500ms）、持仓查询 p95 **374ms**（预算 3s）。5 个 PostgreSQL 参数因 `FT_TEST_POSTGRES_URL` 未设置且 Docker Desktop 返回 “unable to start” 按既有可选后端规则跳过；补跑条件为 Docker Desktop 可用后，以专用 `_test` 数据库设置 `FT_TEST_POSTGRES_URL`，重新运行同一命令。
- **本轮浏览器性能基线与对比（2026-08-11 16:43 CST）**：使用 benchmark 技能动作的 `browse` 实测基线 `origin/refactor/web` 与当前生产预览，基线收支页为 FCP **60ms**、DOM 完成 **29.5ms**、7 请求、2,395,307 B 传输、JS 68,907 B、CSS 5,888 B；当前收支页为 FCP **56ms**、DOM 完成 **26.5ms**、7 请求、2,405,537 B、JS 76,558 B、CSS 8,467 B；当前投资持仓页同样为 7 请求、2,405,537 B、FCP **68ms**、DOM 完成 **29.3ms**。相对基线未触发 timing（>50% 或 >500ms）、bundle（>25%）或 request（>30%）回归；JS 增长约 **11.1%**、CSS 增长约 **43.8%**，按规则属于 warning 但均低于绝对预算（JS <500KB、CSS <100KB、请求 <50）。两边均含约 2.30 MB 的既有 Noto Sans SC 字体，超过 benchmark 示例的 2 MB 总传输提示；该项不由本 PR 引入，未为保持 PR #42 视觉基准而改动全局字体，作为后续独立优化风险记录。LCP 未由当前 browse Chromium 暴露 buffered entry，已准确记录为未采集而非估算。
