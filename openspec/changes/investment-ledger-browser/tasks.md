# Tasks

## 0. 需求与流程门禁

- [x] 0.1 在任何实现前显式调用 `grill-me` 技能的 `/grilling` 动作（不是 shell 命令），并把用户、目标、范围、非目标、验收标准、边界和风险写入本 change；本轮已完成对话澄清和 artifact 记录。
- [x] 0.2 先阅读 `$domain-glossary`、`DOMAIN_GLOSSARY.md` 与 `$chinese-documentation`，区分内部术语和用户术语，形成“内部字段保留、用户界面简化”的文案边界。
- [x] 0.3 选择并读取适用的 OpenSpec、Hallmark、测试与验证技能，确认本次为 A 类 UI 变更，并检查当前工作树、基线、active change 和外部写授权边界。
- [x] 0.4 在开始代码前完成 proposal、spec、design、tasks 与原型的相互核对；原始 change 满足了实现前核对，本轮生产页面回流已先将“原型对齐修复”的目标、范围、验收和风险回写 `design.md`，再开始实现。
- [x] 0.5 调用 `grill-me` 技能的 `/grilling` 动作，澄清轮询、手动刷新、均摊成本、负成本、周期表现、买卖与出入金口径、合并规则、总览分母和浏览器偏好，并将结论回写 proposal、spec、design、tasks 与词表。
- [x] 0.6 调用 `grill-me` 技能的 `/grilling` 动作确认删除边界：不单独展示已卖出部分的盈亏，不预留独立“已实现盈亏”界面字段；周期合计仍包含浮盈亏与期间已实现盈亏。
- [x] 0.7 调用 `grill-me` 的 `/grilling` session 确认标的筛选改为大小写不敏感的字面量包含匹配：覆盖付出与换入资产，空值不筛选，`%`、`_` 和反斜杠不是通配符；不新增全文搜索、索引、账本写入或持仓筛选。
- [x] 0.8 调用 `grill-me` 的 `/grilling` session 确认持仓估值改为 SSE：浏览器移除定时轮询，基础持仓仍在 1 秒内读取；服务端常驻行情刷新器按手动刷新优先、活跃订阅次之连续批量刷新并推送最新快照。范围限本机单进程运行时、易失缓存与断线重连；不写入账本或新增多实例基础设施。
- [x] 0.9 调用 `grill-me` 的 `/grilling` session 确认证券行情备用源：首选 yfinance 批量源为空或异常时，按固定优先级补齐当前报价；不修改周期历史行情、成本/收益公式、账本、数据库或用户界面。Yahoo 直连、Cboe 与 Nasdaq 无密钥可用，Finnhub 仅在本机配置 `FT_FINNHUB_API_KEY` 时启用；每个源保留观测时间与来源，未知值不伪造。

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
- [x] 2.13 根据生产页面截图回流：确认 `prototype/events.html` 仍是视觉事实源，生产只需恢复其表格层级，并修复详情内层列表受共享双列 `dl` 规则影响；不改原型、API 或账务语义。
- [x] 2.14 根据第二轮生产截图回流：确认备注逐字换行是共享 `.evidence dl` 在最终样式顺序中覆盖了等权选择器；本轮只提高投资详情覆盖的特异性，将事件表切换为连续边框模型，并为加载计数补充下边距；不改原型、结构、API 或账务语义。
- [x] 2.15 调用 `grill-me` 的 `/grilling` session 确认当前持仓性能契约：基础持仓 1 秒内可见，行情、估值和总览在 2 秒内完成；不改变数量、成本、估值口径或轮询频率；刷新和不完整响应不得让已有行情闪空。根因记录为单一组合接口把本地快照与外部行情置于同一临界路径，且前端用不完整响应覆盖已有结果。
- [x] 2.16 使用 Hallmark 更新 `prototype/index.html` 的分阶段加载状态：基础持仓先出现，行情/总览原位补齐，刷新保持已显示数值；在实现前复核 320、375、414、768 px。
- [x] 2.17 调用 `grill-me` 的 `/grilling` session 确认近 24 小时盈亏为空是完整阶段的既有回归：恢复同一 2 秒预算内的历史行情可用性，不改变周期盈亏公式、展示口径、基础阶段或刷新保留语义。
- [x] 2.18 调用 `grill-me` 的 `/grilling` session 确认投资快照不可倒放：每个账户/标的使用期间最后一条投资快照为记录基准，展示准确时间与“可能无法反映真实盈亏”；不修改原始投资事件、持仓、买卖或出入金事实。
- [x] 2.19 将标的片段筛选的目标、非目标、字面量边界、游标一致性、SQLite/PostgreSQL 等价和回滚方式回写 proposal、delta spec、design、tasks 与词表；同步原型与生产筛选输入的片段示例，不改信息架构或布局。
- [x] 2.20 使用 Hallmark 更新 `prototype/index.html` 的持仓刷新交互：基础持仓先出现，SSE 快照原位补齐，手动刷新只触发服务端优先刷新，不展示内部行情状态或更新时间；移除原型中的定时轮询，并复核 320、375、414、768 px。
- [x] 2.21 通过 `/grilling` session 明确报价时间与交易时段边界：收市后补价按各市场常规收盘边界各执行一次；盘前、盘中、盘后、夜盘不得混用，来源缺失时显示未知；回写 proposal、spec、design 与词表。

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
- [x] 3.14 为原型对齐修复先增加失败回归：断言生产 CSS 恢复原型的桌面表格基础规则，并让投资详情的资产/补充 `dl` 显式保持单列行容器，防止共享抽屉规则再次压缩数值列；`npm test -- --run tests/InvestmentLedgerPage.test.tsx` 先失败，再随修复转绿。
- [x] 3.15 为第二轮截图缺陷先收紧样式契约：断言表格使用 `border-collapse: separate` 和零间距、标题区有下边距，并使用比 `.evidence dl` 更高特异性的详情单列选择器；先让受影响 Vitest 失败，再做最小 CSS 修复。
- [x] 3.16 为两阶段持仓性能契约建立失败回归：后端验证 `phase=holdings` 不触发行情且在 1 秒内返回，完整查询以 2 秒共享行情预算完成；API 验证阶段参数；前端验证两个请求并行、基础行先见、完整字段补齐、同一口径刷新不以空值覆盖已有报价，改变币种/周期不复用旧金额。
- [x] 3.17 为近 24 小时盈亏回归建立失败测试：让当前行情与周期起点行情单独均可在同一 2 秒窗口内完成、串行则超时，断言完整响应仍返回标的和总览的周期盈亏，并保持一个共享截止时间。
- [x] 3.18 为记录基准建立失败回归：快照后的买入与涨跌只能从该快照时间和确认数量计算；断言 API 返回账户、标的和时间，组合周期率为空，Web 在总览和受影响持仓显示准确的提示。
- [x] 3.19 为标的片段筛选建立失败回归：API 在 SQLite 与 PostgreSQL 均验证付出/换入资产的大小写不敏感包含匹配、游标绑定规范化片段，以及 `%`、`_`、反斜杠的字面量边界；前端验证输入提示和查询参数保持一致；固定 20,000 条事件性能门禁使用 `.us` 片段查询。
- [x] 3.20 为 SSE 持仓刷新建立失败回归：后端验证连接先收到最新快照、刷新器将新估值推送到既有订阅、手动刷新优先、慢行情不阻塞订阅、最后成功值不被未知结果清空，以及 lifespan 停止 worker；API 验证事件格式、版本与参数。前端验证不使用定时轮询、基础持仓先见、SSE 增量补齐、断线重连、手动刷新与展示口径切换。
- [x] 3.21 为证券行情备用源建立失败回归：首选批量源缺失时按优先级补齐单标的；首选成功不调用备用源；前一备用源异常时继续下一源；所有备用源失败保持空值；无密钥时不访问 Finnhub；并发和单源超时有界。
- [x] 3.22 为 SSE 首份估值快照建立失败回归：周期起点预取不得先于当前行情批次占用 upstream；当前单价、市值和浮盈亏先推送，周期盈亏在后续完整快照补齐。
- [x] 3.23 为报价元数据建立失败回归：yfinance/备用源保留来源报价时间；盘前、盘中、盘后、夜盘映射正确；缺失时间/时段不猜测；刷新失败同时保留价格、时间和时段；API 与 Web 文案透传。
- [x] 3.24 为 USD 统一仓位建立失败回归：后端为 USD、非 USD 和现金返回 USD 市值，缺少汇率保持未知；API 序列化该字段；前端混合币种合并后以 USD 总分母计算相同百分比，并覆盖展示币种切换。
- [x] 3.25 为本地动态端口与增量快照建立失败回归：CORS 只允许配置来源和完整回环主机动态端口，伪造回环后缀被拒绝；SSE 在当前行情完整但 USD 仓位或周期字段为空时保留上一份有效字段。
- [x] 3.26 为平均成本共列建立失败回归：桌面表头显示“当前单价 / 平均成本”，同一单元格内分别标注当前单价与平均成本；平均成本按总成本除数量，展示币种切换时与当前单价使用同一汇率，移动卡片不产生横向溢出。

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
- [x] 4.19 在失败回归转绿后，仅调整投资事件表和投资详情的作用域 CSS：恢复原型的表格密度、列宽和截断规则，并让详情事实行保持全宽；不改变 React 结构、数据或交互。
- [x] 4.20 在 3.15 的失败回归后，最小化修改事件表/详情 CSS：表格分隔线保持连续，加载计数与表格分开，备注使用抽屉可用宽度并自然换行；不改 React、数据或交互。
- [x] 4.21 先让 3.16 的后端、API 与 Web 回归失败，再实现 `phase=holdings` 快速组合读取、2 秒完整查询预算、并行客户端编排，以及按展示口径保留最后有效行情/估值/总览的合并逻辑。
- [x] 4.22 在 3.17 的失败回归后，以有界守护 worker 并行预取周期起点行情；复用现有周期资产流量公式和单一 2 秒截止时间，不改前端响应合同或把未知值改为 `0`。
- [x] 4.28 在 3.23 的失败回归后，实现 `ProviderTick` → `QuoteResult` → 持仓 DTO/SSE → Web 的报价时间与交易时段透传，并为 yfinance 索引、Yahoo chart 元数据和收市后市场边界增加最小适配；不改变价格、估值或周期盈亏公式。
- [x] 4.23 在 3.18 的失败回归后，实现按账户/标的的最后投资快照记录基准、基准后流量和基准时间 API；总周期率在存在记录基准时保持未知，并按原型显示小字提示。
- [x] 4.24 在 3.19 的失败回归后，以最小查询适配器改动实现安全的标的片段筛选，并更新筛选输入提示；不改变 API 字段、稳定排序、分页、持仓、账本事实或数据库结构。
- [x] 4.25 在 3.20 的失败回归后，实现进程内常驻行情刷新器、同口径最后成功快照合并、SSE 流和优先刷新触发；将现有 2 秒请求总截止时间从生产 Web 临界路径移除，保留单次外部源超时、退避和精确十进制合同。更新前端为 `EventSource` 消费快照并移除 `setInterval` 轮询。
- [x] 4.26 在 3.21 的失败回归后，实现证券当前报价的有界备用源链，并在运行时装配无密钥与可选密钥源；不改变历史报价或前端合同。
- [x] 4.27 在 3.22 的失败回归后，令 SSE 刷新路径在首份当前估值快照后再启动周期起点预取；保留完整 JSON 兼容读取的并行语义和既有周期公式。
- [x] 4.29 在 USD 归一化仓位失败回归后，为每个持仓及现金增加 USD 市值读模型字段；按币种缓存汇率，SSE 局部失败保留上一份有效字段，Web 分开展示和合并展示都以 USD 总市值计算仓位。
- [x] 4.30 在 3.25 的失败回归后，允许严格锚定的本地回环动态端口访问只读 API/SSE，并按 USD 市值、周期盈亏和周期盈亏率独立合并增量快照；不改变非本地来源信任边界或财务计算。
- [x] 4.31 在 3.26 的失败回归后，将平均成本计算和展示加入当前单价单元格；不新增后端字段、不增加表格列数，并同步生产 UI 与持仓原型。
- [x] 4.32 透传导入来源中的可选标的展示名称，并将持仓单元格改为“名称 / 代号 · 账户”两行；现金行使用货币名称作为第一行，去除重复币种文案，不改变账务或估值字段。

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
- [x] 5.18 对原型对齐后的事件表和详情抽屉执行 Hallmark `audit` 技能动作，重点检查表格层级、数值密度、连续事实行、窄屏阅读和共享 token；最终为 0 critical、0 major、0 minor。
- [x] 5.19 对第二轮截图修复执行 Hallmark `audit` 技能动作，复核连续横线、标题到表格的节奏、长备注换行与四个响应式宽度；记录 finding 和结论。
- [x] 5.20 完成产品/工程/性能范围复核：确认 1 秒基础阶段不访问外部行情，2 秒完整预算没有伪造未知值，刷新缓存只在同一币种与时间范围中使用，并记录各 finding、取舍和回写位置。
- [x] 5.21 对分阶段加载后的生产当前持仓页调用 Hallmark `audit` 技能动作，覆盖首屏层级、刷新期间的稳定性、加载/错误状态、键盘与 320、375、414、768 px；修复全部 critical 和 major finding 后复审。
- [x] 5.22 复核周期行情并发修复的范围、共享截止时间、完全卖出标的、局部未知和线程退出行为；确认没有改变周期盈亏口径或 Web 响应契约。
- [x] 5.26 复核备用源优先级、来源/观测时间、延迟报价边界、密钥不出日志和首选报价不可覆盖性。
- [x] 5.23 复核记录基准的快照选择、零数量、快照后资产流量、组合局限提示和 API 兼容性；确认未倒放快照或改变账本事实。
- [x] 5.24 复核标的片段筛选的范围、大小写归一化、通配符转义、游标绑定、双后端 SQL 语义和最小前端文案；对最终筛选控件执行 Hallmark `audit`，记录 finding 和结论。
- [x] 5.25 复核 SSE 事件边界、缓存不写账本、版本/重连、线程生命周期、外部源超时与退避、失败关闭及单进程假设；对最终持仓页执行 Hallmark `audit`，确认没有新增技术状态、更新时间或响应式回归。
- [x] 5.27 复核报价时间与交易时段的 UI 层级、未知状态、盘前/盘后/夜盘标签、现金行边界和 320/375/414/768 px 响应式；Hallmark `audit` 最终结果为 0 critical、0 major、0 minor。
- [x] 5.28 复核 USD 仓位分母覆盖现金、混合币种汇率缺失时不伪造比例、展示币种切换不改变比例，以及原生表格列轨道和窄屏布局；真实浏览器 QA 无 critical/major/minor finding。
- [x] 5.29 复核动态本地 CORS 的完整主机锚定、非回环拒绝、凭据关闭和 SSE/API 共用边界；复核增量快照不会因局部空字段清除仓位或周期表现，未发现阻断性 finding。
- [x] 5.30 对平均成本共列执行 Hallmark `audit`，覆盖标签层次、报价小字、桌面列轨道、移动端卡片和 320/375/414/768 px；结果 **0 critical、0 major、0 minor**。
- [x] 5.31 复核标的名称来源仅来自持久化导入元数据，缺失时使用无误导的代码回退；检查桌面/移动端两行层次、账户识别和币种不重复。

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
- [x] 6.19 运行事件页受影响 Vitest、完整 Web Vitest、生产构建、生产预览 Playwright 和 `git diff --check`；在 320、375、414、768 px 检查表格/抽屉无横向溢出，桌面详情的每条事实行不再被压缩为半宽。
- [x] 6.20 运行第二轮受影响 Vitest、完整 Web Vitest、生产构建、生产预览 Playwright、OpenSpec 严格校验与 `git diff --check`；用真实长英文备注验证桌面详情不逐字断行，并在 320、375、414、768 px 检查无页面级横向溢出。
- [x] 6.21 运行两阶段持仓的失败/成功回归、完整相关 Python/Web 契约、性能夹具与生产预览：记录基础清单和完整估值的实测时间、刷新保持旧值的证据，以及 SQLite/真实 PostgreSQL 执行状态、当前 `HEAD` 与残余外部行情风险。
- [x] 6.22 运行新增的周期行情并发失败/成功回归、受影响 Python 契约与固定负载性能门禁；确认近 24 小时盈亏在预算内返回，记录 SQLite/真实 PostgreSQL 状态与共享截止时间证据。
- [x] 6.26 运行备用源单元/集成回归、SSE 浏览器 QA、完整后端回归、双后端契约矩阵、性能检查、OpenSpec 校验与范围化 diff 复核。
- [x] 6.23 运行记录基准的后端/API/Web 回归、相称构建、生产预览、OpenSpec、`git diff --check` 和适用 SQLite/PostgreSQL 契约矩阵；记录结果与残余风险。
- [x] 6.24 运行新增标的片段筛选的 SQLite/API/Web 回归、相称构建、OpenSpec 严格校验与 `git diff --check`；在配置 `FT_TEST_POSTGRES_URL` 时补跑同一契约矩阵，否则记录准确补跑条件。
- [x] 6.25 运行 SSE 后端/API/生命周期、前端 Vitest、生产构建与 preview Playwright；验证基础行 1 秒内可见、无浏览器定时估值请求、SSE 重连/手动刷新/口径切换、刷新不闪空，以及 SQLite/真实 PostgreSQL 契约矩阵、性能门禁、OpenSpec 严格校验和 `git diff --check`。
- [x] 6.27 运行报价时间/时段回归、完整 Web Vitest、生产构建、OpenSpec 严格校验、`openspec doctor` 与 `git diff --check`；PostgreSQL 未因本轮只读 DTO/行情适配变更而新增矩阵，沿用既有专用 `_test` 证据。
- [x] 6.28 运行 USD 仓位后端/API 回归、完整 Python/Web 测试、生产构建与预览；真实 SQLite 浏览器检查混合币种、现金分母、展示币种切换、桌面列对齐和 375 px；本轮 `FT_TEST_POSTGRES_URL` 未配置，记录专用 `_test` 数据库补跑条件。
- [x] 6.29 运行 CORS 动态端口契约、SSE 增量合并回归、完整 Web 测试、生产构建、真实启动浏览器 QA、OpenSpec 严格校验、`openspec doctor` 与 `git diff --check`；记录 1280/375 px 字段、权重、周期盈亏和控制台错误证据。
- [x] 6.30 运行平均成本 Web 回归、完整 Web 测试、构建、生产预览和真实浏览器 QA；验证第二列仍与表头对齐、当前单价与平均成本值均保留两位展示精度、展示币种切换和 375 px 无横向溢出。
- [x] 6.31 运行标的名称透传的 SQLite/API/Web 回归、完整受影响测试、生产构建和真实浏览器 QA；验证名称/代号/账户两行、现金行置顶、币种只在价格/市值列出现，以及 375/1440 px 无横向溢出。

## 7. 发布准备

- [x] 7.1 记录路由与 API 回滚、观察项和未解决风险；未经用户明确授权不提交、不推送、不创建 PR、不部署。
- [x] 7.2 完成交付交接记录：列出改动文件、验证命令、基线、残余风险、未执行项、下一步 archive 条件和外部写授权边界。
- [x] 7.3 发布准备时记录浏览器偏好键兼容与回滚方式、轮询失败观察项，以及移除旧占位字段或文案的兼容检查。
- [x] 7.4 交付性能证据：保留 `.gstack/benchmark-reports/2026-08-11-investment-ledger-browser.json` 与 `.md` 及基线文件，记录实际命令、当前 `HEAD`、比较基线、Docker 阻断条件、残余风险和补跑 PostgreSQL 的准确条件。
- [x] 7.5 记录标的片段筛选的回滚方式、验证证据、残余 PostgreSQL 条件与未经授权的外部写边界。
- [x] 7.6 记录 SSE 交付证据、进程内缓存和单进程部署假设、回滚到 JSON 完整估值读取的方式，以及未经授权的外部写边界。
- [x] 7.7 记录报价时间字段的兼容回滚：移除 `quote_observed_at`/`quote_session` 展示与透传即可恢复旧 UI；不影响账本、数据库、价格或盈亏公式。未提交、未推送、未部署。
- [x] 7.8 记录本轮回滚与交付边界：移除 `display_name` 透传和展示层级即可恢复仅代码显示；该字段为只读派生元数据，不涉及迁移、账本写入或外部发布。
- [x] 7.8 记录本轮回滚与交付边界：移除动态本地 CORS 正则即可恢复严格单来源配置；移除客户端独立字段保留逻辑即可恢复旧合并行为；不触碰账本或行情数据。未提交、未推送、未部署。

## 8. 反思

- [ ] 8.1 归档前同步 delta；沉淀投资浏览查询、证据安全、局部失败和 UI 验证中的可复用规则（已完成规则沉淀；主规格同步与 archive 待用户确认）。
- [x] 8.2 在最终 UI 通过后沉淀“用户界面只显示结果与动作、精确口径留在规格/无障碍语义”的文案规则，并记录防止内部术语和解释句回归的测试位置。
- [x] 8.3 沉淀标的片段筛选的字面量转义与稳定分页规则，防止未来把用户输入重新解释为数据库通配符。
- [x] 8.4 沉淀行情刷新规则：SSE 不是行情源，必须由刷新器、最后成功快照、版本化事件和浏览器重连共同保证渐进展示；多实例需要独立 change 的共享发布机制。
- [x] 8.5 沉淀本地开发规则：前端开发/预览端口可动态变化，API 应以完整回环主机匹配适配端口变化，同时保持非回环来源拒绝；增量读模型应按字段保留最后可信值。
- [x] 8.6 沉淀持仓表格规则：平均成本是持仓单价的核对信息，与当前单价共用同一列并由标签区分，不为单个辅助指标扩张桌面表格轨道。

## 执行记录与审查结论

- **标的片段筛选（2026-08-12，Asia/Shanghai）**：范围限定为投资事件页面的“标的”筛选框；空白输入不筛选，非空输入按不区分大小写的包含关系匹配 `from_ticker` 或 `to_ticker`。`%`、`_` 和 `\\` 均按字面量解释，不引入全文检索、索引、schema、数据或迁移改动，也不改变当前持仓筛选。输入示例同步为“如 AAPL 或 .US”。回滚方式为恢复原有等值比较。
- **测试先行、工程审查与 UI 审查**：实现前，`uv run pytest -q tests/test_application_investment_web_queries.py -k ticker` 因精确匹配未返回 `Pl.Us` 而失败；`npm test -- --run tests/InvestmentLedgerPage.test.tsx` 因旧示例文案而失败。实现采用数据库侧 `LOWER(...) LIKE :pattern ESCAPE '\\'`，模式参数化绑定且先转义 LIKE 元字符；现有游标的筛选指纹仍使用归一化后的 `ticker`，不会跨筛选条件复用。工程复核确认没有前端二次过滤、字符串拼接 SQL 或持久化影响。按 Hallmark `audit` 手工审查 `web/src/components/InvestmentFilters.tsx`、相关查询实现、样式和 `prototype/events.html`：0 critical、0 major、0 minor；输入保留可见焦点态、标签与响应式网格。
- **本轮验证（HEAD `d8d618da3fe551be2dbe1dfada69f2ce566833d1`，比较基线相同）**：初始 SQLite 回归 `uv run pytest -q tests/test_application_investment_web_queries.py tests/contract/test_investment_web_api.py` 通过（11 passed、3 skipped）；`uv run pytest -q -s tests/test_investment_web_performance.py` 通过（2 passed、2 skipped，20K 事件片段筛选 `list_p95_ns=2519792`）；`uv run pytest -q` 通过（1327 passed、160 skipped、1 warning，300.03s）；`npm test -- --run` 通过（61 passed）；`npm run build` 通过；`npm run test:preview` 通过（6 passed）；`openspec validate --all --strict` 通过（18 passed），`openspec doctor` 根目录检查通过，`git diff --check` 通过。用户授权后，复用 Docker 专用容器 `finance-tracker-postgres-test` 的 `finance_tracker_test` 库（端口 55432）注入 `FT_TEST_POSTGRES_URL`：查询/API 双后端契约矩阵通过（14 passed、1 warning）；20K 事件片段筛选性能门禁的 SQLite/PostgreSQL 两项通过（2 passed、2 deselected），PostgreSQL 列表 p95 为 120.445ms，低于 750ms 预算。无残余 PostgreSQL 验证项。本轮未提交、未推送、未创建 PR、未部署。

- **原型对齐修复（2026-08-12，Asia/Shanghai）**：用户反馈生产投资事件页的表格层级弱于已确认的 `prototype/events.html`，且打开详情后资产数值逐字换行。根因是生产事件表没有实现原型的完整表格基础规则，投资详情内层 `dl` 又被共享 `.evidence dl` 的双列网格规则覆盖，导致多条资产事实被排进半宽格。修复仅修改 `web/src/investment.css`：恢复深色表头、7 列宽度、行距、截断与数值对齐，并将详情资产/补充 `dl` 显式设为单列容器。没有修改 React 结构、API、事件事实、金额/时间格式或账务数据。
- **原型对齐审查**：Hallmark `audit` 目标为 `web/src/investment.css`、`web/src/components/InvestmentTable.tsx`、`web/src/components/InvestmentEvidenceDetail.tsx` 与真实生产页面。审查表格层级、连续事实行、数据密度、响应式、token、图标和交互；无渐变、临时颜色、混用图标、重复卡片或结构漂移。结果为 **0 critical、0 major、0 minor**。
- **原型对齐验证**：新增样式契约测试先失败，修复后 `npm test -- --run tests/InvestmentLedgerPage.test.tsx` → **9 passed**；完整 `npm test -- --run` → **58 passed**；`VITE_FT_API_ORIGIN=http://127.0.0.1:5174 npm run build` → **通过**；`FT_PREVIEW_API_PORT=8791 FT_PREVIEW_WEB_PORT=5191 npm run test:preview` → **3 passed**。使用该生产构建和真实事件数据在 320、375、414、768、1440 px 检查：页面和抽屉均无横向溢出，详情内层 `dl` 为 `display:block`，每条资产事实行均为内容区全宽，控制台无错误。`openspec validate --all --strict` → **18 passed、0 failed**；`openspec doctor` → **root ok**；`git diff --check` → **通过**。当前 `HEAD` 为 `4a588e2908f37df0cd32c9516459e181ad9deac1`，比较基线为 `origin/refactor/web` 的合并基点 `04caf0c9c412e1cc72963290a1b34968965d2515`；未提交、未推送、未创建 PR、未部署。

- **第二轮截图修复（2026-08-12，Asia/Shanghai）**：实测表格的资产单元格高度会短于同行的操作按钮单元格，若将 `border-bottom` 画在 `td` 上，资产列的线会提前结束，视觉上像被截断。现将桌面端分隔线移至 `tr`，保留零间距独立表格模型；移动端卡片继续使用自己的完整边框。标题区增加 `var(--space-3)` 下边距，实测“已加载 50 条”到表格为 13 px。备注问题的直接根因是 `.investment-detail-supplement` 与全局 `.evidence dl` 特异性相同且加载顺序更早；改为 `.evidence .investment-detail-supplement` 后，长备注占事实行的完整弹性列。没有修改组件、数据、API、金额、时间或交互。
- **第二轮审查**：Hallmark `audit` 目标为 `web/src/investment.css`、`web/src/components/InvestmentTable.tsx`、`web/src/components/InvestmentEvidenceDetail.tsx` 与真实页面。覆盖整行分隔线、标题节奏、长备注换行、抽屉层级和四个响应式宽度；结果为 **0 critical、0 major、0 minor**。
- **第二轮验证**：样式契约两次先失败，最终 `npm test -- --run tests/InvestmentLedgerPage.test.tsx` → **9 passed**；完整 `npm test -- --run` → **58 passed**；`VITE_FT_API_ORIGIN=http://127.0.0.1:5174 npm run build` → **通过**；`FT_PREVIEW_API_PORT=8791 FT_PREVIEW_WEB_PORT=5191 npm run test:preview` → **3 passed**。使用生产产物（5193）和真实事件数据检查 1440 px：表格行为 `border-bottom: 1px solid`，资产 `td` 不再独自绘制下边线；加载计数到表格为 13 px；详情备注 `TAIWAN SEMICONDUCTOR-SP ADR` 获得 327 px 宽度、`display:block` 的补充事实容器且抽屉无横向溢出。320、375、414、768 px 均无页面/抽屉横向溢出，备注可用宽度分别为 187、242、281、631 px；控制台无错误。`openspec validate --all --strict` → **18 passed、0 failed**；`openspec doctor` → **root ok**；`git diff --check` → **通过**。当前 `HEAD` 为 `4a588e2908f37df0cd32c9516459e181ad9deac1`，比较基线为 `origin/refactor/web` 的合并基点 `04caf0c9c412e1cc72963290a1b34968965d2515`；未提交、未推送、未创建 PR、未部署。

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
- **本轮浏览器性能基线与对比（2026-08-11 17:05 CST，路由拆分最终复测）**：使用 benchmark 技能动作的 `browse` 实测基线 `origin/refactor/web` 与当前生产预览。基线收支页为 FCP **60ms**、DOM 完成 **29.5ms**、7 请求、2,395,307 B 传输、JS 68,907 B、CSS 5,888 B；当前收支页为 FCP **52ms**、DOM 完成 **24.6ms**、7 请求、2,396,660 B、JS 69,991 B、CSS 6,157 B，增幅分别为 JS **1.6%**、CSS **4.6%**、总传输 **0.06%**，未触发回归阈值。当前投资持仓页冷加载为 FCP **44ms**、DOM 完成 **26.8ms**、9 请求、2,409,411 B、JS 总计 79,735 B（主包 69,991 B + 投资 chunk 9,744 B）、CSS 总计 9,164 B（全局 6,157 B + 投资 chunk 3,007 B），均通过 FCP、DOM、JS <500KB、CSS <100KB 和请求数 <50 的绝对预算。投资页资源已通过 SPA 路由懒加载，不再增加收支页首屏负担；两条路由的总传输仍约 2.40 MB，主要来自基线已有 Noto Sans SC 字体，超过 benchmark 示例的 2 MB 提示但不是本 PR 引入。LCP 未由当前 browse Chromium 暴露 buffered entry，已准确记录为未采集而非估算。
- **投资性能夹具最终复跑（2026-08-11 17:05 CST）**：当前 `HEAD` 为 `cfe48cfe84a4432f8d3d25d5f66cf3eceb10c5fd`，在夹具数量断言和 Web 路由拆分完成后，`uv run pytest -q -s tests/test_investment_web_performance.py` → **2 passed、2 skipped**；SQLite 列表 p95 **1.585ms**、详情 p95 **0.554ms**、持仓查询 p95 **360ms**，分别低于 750ms、500ms、3s 门禁。PostgreSQL 仍因 Docker Desktop 无法启动而按可选矩阵规则跳过。

- **当前持仓分阶段性能门禁（2026-08-12，Asia/Shanghai）**：根因是单一组合接口先等待外部行情/历史行情，浏览器又会以不完整返回覆盖当前可见估值。实现新增只读取本地快照的 `phase=holdings`；页面并行请求基础和完整阶段，完整阶段在同一币种与时间范围内只以新有效字段替换旧行情、估值和总览。基础阶段不会调用行情、历史行情、汇率或周期表现；未知值保持未知，不以 `0` 伪造。完整阶段的 2 秒截止时间从本地快照读取前开始，外部行情、历史行情和汇率共享余量；慢行情在预算外返回时被忽略。
- **本轮失败回归与工程复核**：先运行后端/API/Web 断言，观察到缺少 `get_holdings`、默认完整预算仍为 4 秒、路由仍调用完整估值以及前端未并行发起请求的失败；实现后转绿。工程复核发现截止时间原本在本地快照读取之后才开始，已前移，并增加慢快照回归，保证本地读取也计入完整阶段 2 秒。没有发现金额、币种、持仓数量或账务写入方面的变更；残余外部风险是冷启动行情源在预算内不可用时只能如实显示未知值。
- **本轮 Hallmark audit**：按 `audit` 程序复核 `web/src/pages/InvestmentLedgerPage.tsx`、`web/src/components/InvestmentHoldings.tsx`、`web/src/investment.css` 和 `prototype/index.html` 的首屏层级、刷新稳定性、错误状态、键盘和 320、375、414、768 px。首轮发现 1 个 minor（投资样式补充戳缺少实际宏观结构名），已补为 `Workbench / Ledger Grid` 并复核；最终 **0 critical、0 major、0 minor**。
- **本轮验证**：`uv run pytest -q -s tests/unit/application/test_portfolio_valuation.py tests/contract/test_investment_web_api.py tests/integration/test_portfolio_query_sqlite.py tests/test_investment_web_performance.py` → **23 passed、3 skipped**；固定 SQLite 负载为 20,000 条投资事件、1,000 条资金关系、128 个持仓，基础持仓 p95 **427.492ms**（门禁 1s），完整估值 p95 **443.296ms**（门禁 2s）。`npm test -- --run` → **60 passed**；`npm run build` → 通过；`npm run test:preview` → **5 passed**，分阶段持仓端到端用例 **645ms**，且刷新后旧行情/总览保持可见，320、375、414、768 px 无横向溢出。`uv run pytest -q` → **1323 passed、158 skipped**，耗时 **302.13s**，只有既有 Starlette/httpx deprecation warning。`FT_TEST_POSTGRES_URL` 当前未配置，真实 PostgreSQL 契约矩阵未执行；补跑条件为配置指向专用 `_test` 数据库的该变量后，运行同一契约与性能命令。当前 `HEAD` 为 `4a588e2908f37df0cd32c9516459e181ad9deac1`，比较基线为 `04caf0c9c412e1cc72963290a1b34968965d2515`；未提交、未推送、未创建 PR、未部署。

- **近 24 小时盈亏回归修复（2026-08-12，Asia/Shanghai）**：真实接口复现为当前单价和市值在约 2 秒后可用、所有 `period_profit` 却为 `null`。根因是完整组合查询先等待当前行情批次，再串行读取周期起点报价；当前批次耗尽同一截止时间后，周期计算在发起历史读取前即返回未知。实现将周期边界、期间流水与期初数量的本地推导前移，在读取当前行情的同时以最多 8 个守护 worker 预取周期起点报价；两类请求和结果消费仍共享从完整请求开始计算的 2 秒单调截止时间。完全卖出的标的继续参与总周期盈亏，报价迟到、失败或不支持时继续返回未知值，未改变资产流量公式、精确十进制 API 或前端刷新保留策略。
- **本轮测试先行与范围复核**：新增回归先失败：当前行情等待历史读取信号时，旧串行实现使当前市值和周期盈亏均错过 120ms 测试预算；修复后返回 `1,200` 市值和 `200` 周期盈亏。工程复核确认历史任务在本地快照已耗尽预算时不再启动，worker 有界且为守护线程，计算只在截止时间内消费结果；局部未知不被替换为 `0`，完全卖出标的仍在 `current ∪ net_changes` 中计入总额。Hallmark `audit` 覆盖 `web/src/pages/InvestmentLedgerPage.tsx`、`web/src/components/InvestmentHoldings.tsx`、`web/src/investment.css` 与生产预览的数值层级、刷新和四个响应式宽度，结果为 **0 critical、0 major、0 minor**。
- **本轮验证**：`uv run pytest -q tests/unit/application/test_portfolio_valuation.py tests/contract/test_investment_web_api.py tests/integration/test_portfolio_query_sqlite.py tests/test_application_investment.py tests/test_investment_web_performance.py` → **42 passed、3 skipped**；其中固定 SQLite 负载（20,000 条事件、1,000 条关系、128 个持仓）→ **2 passed、2 deselected**，并断言完整结果的周期盈亏为 `0` 而非未知。`npm test -- --run` → **60 passed**；`npm run build` → 通过；`npm run test:preview` → **5 passed**，生产预览额外断言“近 24 小时浮盈亏”和表格周期盈亏 `+8.04 USD` 可见，完整阶段为 **636ms**。`uv run pytest -q` → **1324 passed、158 skipped、1 个既有 Starlette/httpx deprecation warning**，耗时 **302.13s**。`openspec validate --all --strict` → **18 passed、0 failed**；`openspec doctor` → **root ok**；`git diff --check` → 通过。`FT_TEST_POSTGRES_URL` 未配置，真实 PostgreSQL 契约矩阵未运行；补跑条件是将其设置为专用 `_test` 数据库后重跑同一受影响命令。当前 `HEAD` 为 `4a588e2908f37df0cd32c9516459e181ad9deac1`，比较基线为 `04caf0c9c412e1cc72963290a1b34968965d2515`；未提交、未推送、未创建 PR、未部署。现有本机 API 未启用自动重载，需以原有配置重启后才会载入本次代码。

- **记录基准与不可倒放快照（2026-08-12，Asia/Shanghai）**：用户确认在所选周期内遇到投资快照时，展示必须说明“以什么时候的记录为基准，可能无法反映真实盈亏”。完成 `/grilling` 后，将 `snapshot` 定义为不可倒放的记录基准：每个账户/标的选择期间最后一条快照，使用其确认数量和发生时间读取起点行情，并只累计该记录之后的买卖、投资收入和手续费；快照本身不改写原始事件、持仓、买卖或外部出入金。组合响应与受影响持仓均返回账户、标的和明确发生时间；存在任何记录基准时，组合周期盈亏率返回未知，避免跨资产混合起点生成假精确百分比。总览以小字显示“以 {记录时间} 的记录为基准，可能无法反映真实盈亏”，受影响持仓的周期盈亏单元格显示自己的记录时间；API 时间携带 offset，前端按浏览器本地时区格式化。
- **测试先行、范围复核和 Hallmark 审计**：先新增回归，旧实现将快照后的示例持仓盈亏从正确的 `100` 错算为 `150`，且完全没有提示文案；修复后测试覆盖两条快照选取最后一条、记录后买入、记录时点历史报价、组合周期率为空、API ISO 时间序列化、总览与持仓行的本地时间提示。范围复核发现备注字体引用未定义 token，已改用既有 `--font-body`。Hallmark `audit` 目标为 `web/src/components/InvestmentHoldings.tsx`、`web/src/investment.css` 和 `prototype/index.html`，覆盖信息层级、桌面表格、窄屏持仓卡片、自然换行、可访问语义与 320/375/414/768 px；结论为 **0 critical、0 major、0 minor**。
- **本轮验证（2026-08-12，Asia/Shanghai）**：`uv run pytest -q tests/test_application_investment.py tests/contract/test_investment_web_api.py tests/integration/test_portfolio_query_sqlite.py tests/test_investment_web_performance.py` → **30 passed、3 skipped**（1 个既有 Starlette/httpx warning）；`npm test -- --run` → **61 passed**；`npm run build` → 通过；`npm run test:preview` → **5 passed**，覆盖生产预览与 320、375、414、768 px。`uv run pytest -q` → **1325 passed、158 skipped、1 个既有 Starlette/httpx warning**，耗时 **301.64s**。`openspec validate --all --strict` → **18 passed、0 failed**；`openspec doctor` → **root ok**；`git diff --check` → 通过。`FT_TEST_POSTGRES_URL` 未配置，真实 PostgreSQL 契约/性能矩阵本轮未运行；补跑条件是将该变量设置为专用、名称以 `_test` 结尾的数据库后，重跑本轮受影响 Python 命令。当前 `HEAD` 为 `4a588e2908f37df0cd32c9516459e181ad9deac1`，比较基线为 `04caf0c9c412e1cc72963290a1b34968965d2515`；未提交、未推送、未创建 PR、未部署。

- **SSE 常驻行情刷新器（2026-08-12，Asia/Shanghai）**：浏览器保留 `phase=holdings` 的本地持仓读取，并在页面可见时建立 `GET /api/v1/investment-portfolio/stream`。服务端 `PortfolioRefreshCoordinator` 仅在本机单进程内保存同一展示币种、时间范围和时区的最后成功快照与单调版本号；手动 `POST /api/v1/investment-portfolio/refresh` 优先于活跃订阅的常规刷新。后台使用既有批量行情、历史行情与汇率调用的单源超时；生产 Web 路径不再使用 2 秒整页总截止时间。数据源短暂未知、超时或失败时，刷新器和浏览器都只以可用字段合并最新快照，已显示行情、估值和总览不会闪为 `—`。页面隐藏/卸载关闭流，重新可见时重新连接并提交一次优先刷新；不再使用 `setInterval` 或定时 HTTP 完整估值请求。SSE 不是行情源，也不写账本；多实例部署需另建 change 引入共享刷新与发布机制。
- **SSE 工程与 UI 审查**：复核了事件帧（`id`、事件名、精确十进制 JSON）、`Last-Event-ID` 重连、心跳、worker stop、手动优先级、退避、易失缓存、异常隔离与单进程边界。Hallmark `audit` 覆盖 `web/src/pages/InvestmentLedgerPage.tsx`、`web/src/components/InvestmentHoldings.tsx`、`web/src/investment.css` 和 `prototype/index.html`：首轮发现 1 个 minor——减少动态效果时刷新符号仍旋转；已加入静态忙碌状态并复核。最终 **0 critical、0 major、0 minor**；没有新增技术状态、更新时间或响应式溢出。
- **SSE 验证与交付准备（HEAD `d8d618da3fe551be2dbe1dfada69f2ce566833d1`，比较基线相同）**：新增刷新器/API/前端回归覆盖快照重放、手动优先、慢提供方心跳与 worker 退出、最后成功值合并、SSE 帧、页面可见性重连、手动刷新及无定时轮询。SQLite 受影响矩阵 `uv run pytest -q tests/test_application_investment.py tests/unit/application/test_portfolio_valuation.py tests/integration/test_portfolio_query_sqlite.py tests/test_investment_web_performance.py tests/test_portfolio_refresh.py tests/contract/test_investment_web_api.py -k 'not postgres'` → **47 passed、5 deselected**；SSE 定向矩阵后续为 **12 passed、3 deselected**。专用 Docker `finance-tracker-postgres-test` 的 `finance_tracker_test` 库（端口 55432）设置 `FT_TEST_POSTGRES_URL` 后，`-k postgres` 契约矩阵 → **6 passed、26 deselected**。完整 Python `uv run pytest -q` → **1332 passed、161 skipped、1 个既有 warning**，耗时 **301.02s**。完整 Web Vitest → **62 passed**；`npm run build` 通过；`npm run test:preview` → **6 passed**，其中 SSE 分阶段用例在 2 秒内呈现本地持仓、行情、总览和周期盈亏，且 320/375/414/768 px 无横向溢出；`openspec validate investment-ledger-browser --strict` 与 `git diff --check` 均通过。Docker 测试容器已停止。回滚方式为移除 coordinator/两条 SSE 路由并恢复前端完整 JSON 估值读取；不影响账本、数据库或投资事件。未提交、未推送、未创建 PR、未部署，也未归档 change，仍等待用户明确授权。

- **SSE 最终复核（HEAD `d8d618da3fe551be2dbe1dfada69f2ce566833d1`，比较基线 `04caf0c9c412e1cc72963290a1b34968965d2515`）**：发现生命周期关闭时若只等待 0.5 秒，刷新线程可能仍在使用查询服务而 Web runtime 已释放数据库引擎；已改为先等待当前刷新有序退出再释放引擎。`uv run pytest -q tests/test_portfolio_refresh.py` → **4 passed**；随后完整 `uv run pytest -q` → **1332 passed、161 skipped、1 个既有 warning**（301.19s）。Docker 专用 `_test` 库的 PostgreSQL 矩阵再次通过（**6 passed、26 deselected**）；完整 `npm --prefix web test -- --run` → **62 passed**，`npm --prefix web run build` → 通过，`npm --prefix web run test:preview` → **6 passed**。`openspec validate --all --strict` → **18 passed、0 failed**，`openspec doctor` → root ok，`git diff --check` → 通过；Docker 测试容器已停止。最终范围化 diff 复核无阻断性 finding，未提交、未推送、未创建 PR、未部署。

- **行情备用源与首份 SSE 快照（2026-08-12，Asia/Shanghai）**：浏览器实测发现 `yfinance.download()` 即使收到超时参数，底层重试仍可能阻塞约 4 秒，使备用源无法及时执行。当前报价因此改为：yfinance 批量请求最多等待 250ms，超时或空值后以最多 8 个守护 worker 按标的依次读取 Yahoo Finance chart、Cboe delayed quote、Nasdaq quote，最后在设置 `FT_FINNHUB_API_KEY` 时读取 Finnhub。首选成功值绝不被覆盖；备用 `ProviderTick` 保留源名、币种和源响应提供的观测时间；Cboe、Nasdaq 的延迟属性不被伪装为首选实时源；所有源失败仍为未知。周期起点历史报价不使用备用链。为避免历史读取与当前批量源争用，SSE 路径先推送当前单价、市值和浮盈亏，再发起周期起点预取补齐期间表现；完整 JSON 兼容读取继续并行预取历史报价。
- **本轮测试先行、审查和浏览器 QA**：新增“主源忽略 timeout 时必须立即转备用源”的回归，旧实现耗时约 210ms 而失败，受控 worker 后转绿；新增 SSE 回归，旧并行历史预取会在当前行情前启动而失败，调整后转绿。产品/工程/安全范围复核未发现阻断项：仅新增只读当前报价路径，不改历史报价、盈亏公式、账本、数据库或前端合同；Finnhub 密钥仅从运行环境读取且没有日志路径。Hallmark audit 不适用：本轮没有修改生产页面、样式、信息架构或交互，浏览器只验证既有页面接收的新数据时序。
- **本轮验证（HEAD `ce0d88739391cdd8838a3ce0201b682ec5dd81e8`，尚未提交）**：`uv run pytest -q tests/test_market_data.py tests/unit/application/test_portfolio_valuation.py tests/test_portfolio_refresh.py tests/contract/test_investment_web_api.py tests/integration/test_portfolio_query_sqlite.py tests/test_investment_web_performance.py` → **42 passed、5 skipped**；`npm run build` → 通过；`npm test -- --run` → **62 passed**。隔离的本机 8011/5176 服务上，未缓存展示口径的 `phase=holdings` 为 **47ms**；约 **2.6s** 后浏览器显示全部 6 个美股的单价、市值和浮盈亏，手动刷新 500ms 后旧行情仍可见且控制台无新错误。独立的 6 标的实测从 yfinance 约 4.07s 降为 Yahoo chart 备用链约 **2.35s**。完整 `uv run pytest -q` → **1338 passed、161 skipped、1 个既有 Starlette/httpx warning**，耗时 **301.76s**；`openspec validate --all --strict` → **18 passed、0 failed**；`openspec doctor` → root ok；`git diff --check` → 通过。`ruff` 未安装，无法执行；`FT_TEST_POSTGRES_URL` 未配置，真实 PostgreSQL 契约矩阵未运行，补跑条件为该变量指向名称以 `_test` 结尾的专用数据库。隔离 QA 服务与临时配置已清理；未提交、未推送、未创建 PR、未部署。
- **报价时间与交易时段最终复核（2026-08-12，Asia/Shanghai，HEAD `ce0d88739391cdd8838a3ce0201b682ec5dd81e8`）**：按 `/grilling` 结论将报价时间定义为数据源时间、交易时段定义为独立元数据；yfinance/备用源和 SSE 合并均成对透传 `quote_observed_at` 与 `quote_session`，盘前、盘中、盘后、夜盘及未知状态均有回归。当前常驻 SSE 刷新器不增加全局收市后定时器；若未来启用独立补价阶段，规格已明确按各市场收盘边界各执行一次。手动 Hallmark `audit` 覆盖价格下方小字、现金行边界、未知状态、桌面/窄屏层级与 320/375/414/768 px，结论 **0 critical、0 major、0 minor**。报价适配定向回归 → **44 passed、3 skipped**；全量 Python → **1345 passed、161 skipped、1 个既有 warning**（303.04s）；完整 Web Vitest → **63 passed**；生产构建通过；预览 Playwright → **6 passed**；Playwright 直接加载原型并等待 SSE 示例更新后，320/375/414/768 px 的报价小字仍保留；`openspec validate --all --strict` → **18 passed、0 failed**；`openspec doctor` → **root ok**；`git diff --check` → **通过**。未提交、未推送、未部署。
- **真实启动与真实数据浏览器 QA（2026-08-12，Asia/Shanghai）**：使用真实本机 SQLite `~/.ft/finance-tracker.db`，重启 `uv run ft web --port 8000` 与 Vite `5174` 后，通过浏览器实际访问 `#/investment-holdings`。首轮发现旧后端未重启导致 SSE 404，以及 yfinance 历史边界带微秒 ISO 时间导致近 24 小时盈亏为空；已补日期边界回归并修复，重启后 `phase=holdings` **50ms**、SSE **200**（首份约 **1.94s**），真实 7 个持仓均显示报价时间/时段，近 24 小时总盈亏 **-620.1399093627965300 CNY**；手动刷新 **202** 且旧行情保持；切换 CNY 后总市值和汇率补齐。真实投资事件列表、详情备注与 1280/375 响应式也实测通过，控制台无错误；首轮发现的桌面长数值重叠和移动端逐字竖排已通过表格横向滚动及移动值截断修复。最终定向 Python → **41 passed、3 skipped**；完整 Web → **63 passed**；构建通过；预览 → **6 passed**。QA 期间仅使用本地只读服务，未写账本；服务进程已停止，未提交、未推送、未部署。

- **持仓数值展示精度（2026-08-12，Asia/Shanghai）**：用户要求解决当前单价、数量、市值、浮盈亏和总览金额的小数位过长问题。通过 `/grilling` 结论将范围限定为展示层：使用现有精确十进制字符串算法在渲染时按第 3 位四舍五入，最多保留 2 位小数并保留千分位；API、前端状态、排序、周期表现和账务计算仍使用原始精度，整数不强制补 `.00`。先加入高精度持仓页面失败回归，再让 `displayValue` 统一应用展示四舍五入；不修改投资事件金额格式或任何后端数据。
- **本轮 Hallmark audit**：目标为 `web/src/components/InvestmentHoldings.tsx`、`web/src/investment.css` 与真实生产预览的持仓表格，检查数值层级、负号/颜色语义、桌面长值、移动卡片截断、320/375/414/768 px 响应式与 token 使用。仅为展示精度收敛，没有发现 anti-pattern；结论 **0 critical、0 major、0 minor**。
- **本轮验证（2026-08-12，Asia/Shanghai）**：`npm --prefix web test -- --run` → **64 passed**；`npm --prefix web run build` → 通过；`npm --prefix web run test:preview` → **6 passed**；生产构建通过后以真实 SQLite `~/.ft/finance-tracker.db` 启动后端和 Vite preview，浏览器访问 `#/investment-holdings`，真实页面显示 `0.60 CNY`、`422.06 USD`、`56,739.20 CNY`、`-620.14 CNY` 等最多两位小数，控制台无错误。预览服务已停止；`openspec validate --all --strict` → **18 passed、0 failed**；`openspec doctor` → **root ok**；`git diff --check` → **通过**。未提交、未推送、未部署。

- **相对报价时间（2026-08-12，Asia/Shanghai）**：用户要求将当前单价下的绝对报价时间改为前端相对时间。实现使用浏览器 `Date.now()` 减数据源 `quote_observed_at`，按实际非零单位显示 `报价于30秒前`、`报价于2分5秒前` 或 `报价于3小时4分5秒前`，同时保留独立的交易时段标签；缺失或无效时间继续显示“报价时间未知”，未改后端字段、行情时段或财务计算。新增秒、分钟/秒、小时/分钟/秒边界回归及 SSE 手动刷新回归；Hallmark 复核 `InvestmentHoldings` 价格小字和响应式层级为 **0 critical、0 major、0 minor**。`npm --prefix web test -- --run` → **66 passed**；`npm --prefix web run build` → 通过；`npm --prefix web run test:preview` → **6 passed**；OpenSpec 全量校验 → **18 passed、0 failed**；`openspec doctor` → **root ok**；`git diff --check` → 通过。真实本机 SQLite 浏览器 QA 显示 7 个持仓均为相对时间（例如 `报价于13小时18分41秒前 · 盘中`），清空历史控制台后无错误；本地服务已停止。当前改动未提交、未推送、未部署。

- **持仓字段错位与原币种汇总（2026-08-12，Asia/Shanghai）**：真实截图根因是 `.holding-symbol` 和 `.holding-price` 直接把 `<td>` 改成 `display:grid`，脱离原生表格单元格布局，造成当前单价占用第一列、后续列整体左移；已恢复两者为 `display:table-cell`，仅让内部值与报价小字块级排列。另发现原币种模式同时存在 CNY/USD 时，后端按合同返回标的市值但不会跨币种相加，前端却用空总市值作为总览值；现按币种分别汇总总览，未知值不伪造为 0。仓位分母不在此处按原币种拆分，改由后续 USD 归一化字段统一计算。
- **本轮 Hallmark 与真实浏览器复核**：复核桌面表格 9 个表头与 9 个单元格的 `x/width` 轨道完全一致，原币种总览显示 `CNY 79,788.92 · USD 29,279.68`，持仓行显示 `+71.11%`、`+28.83%` 等按币种仓位；375 px 下 `body.scrollWidth === 375`、7 张持仓卡片保留全部字段，清空控制台后无错误。视觉/响应式审查结果 **0 critical、0 major、0 minor**。
- **本轮验证**：失败优先的布局契约先失败后转绿；`npm --prefix web test -- --run` → **66 passed**；`VITE_FT_API_ORIGIN=http://127.0.0.1:8000 npm --prefix web run build` → 通过；`npm --prefix web run test:preview` → **6 passed**；`openspec validate --all --strict` → **18 passed、0 failed**；`openspec doctor` → **root ok**；`git diff --check` → 通过。真实 SQLite 后端 `8000` 与生产预览 `4173` 已停止，当前改动未提交、未推送、未部署。

- **USD 统一仓位（2026-08-12，Asia/Shanghai）**：用户明确仓位不得按原币种分别计算，必须先把持仓和现金市值换算为 USD，再以 USD 总市值为分母。先加入后端 USD 归一化字段失败回归，旧 DTO 缺少字段而失败；实现后由组合查询按币种缓存汇率并填充 usd_market_value，SSE 合并在局部行情失败时保留上一份有效 USD 值，前端分开展示和合并展示均使用 USD 字段计算仓位；原币种/统一展示金额仍遵循既有总览口径，缺少汇率时仓位保持未知。
- **本轮验证**：`uv run pytest -q tests/unit/application/test_portfolio_valuation.py tests/contract/test_investment_web_api.py tests/test_portfolio_refresh.py` → **29 passed、3 skipped**；`npm --prefix web test -- --run` → **66 passed**；`npm --prefix web run build` → 通过。真实 SQLite 服务重启后执行浏览器 QA，原币种与 CNY 展示下仓位百分比保持一致，现金已计入 USD 分母；桌面 9 列表头/单元格轨道一致，375 px 无横向溢出，控制台无错误。`npm --prefix web run test:preview` → **6 passed**；`uv run pytest -q` → **1347 passed、161 skipped、1 个既有 Starlette/httpx deprecation warning**（307.11s）；`openspec validate --all --strict` → **18 passed、0 failed**；`openspec doctor` → **root ok**；`git diff --check` → 通过。`FT_TEST_POSTGRES_URL` 未配置，真实 PostgreSQL 契约矩阵未运行；补跑条件为设置指向名称以 `_test` 结尾的专用数据库后重跑受影响契约。服务进程已停止，未提交、未推送、未部署。
- **本轮 UI audit**：Hallmark 复核 `InvestmentHoldings`、`investment.css` 与真实预览，覆盖 USD 仓位数值层级、桌面列对齐、现金/混合币种状态、320/375/414/768 px 响应式和无横向滚动；结果 **0 critical、0 major、0 minor**。

- **动态端口与增量快照修复（2026-08-12，Asia/Shanghai）**：真实 QA 复现的持仓空字段根因是后端固定 `FT_WEB_ORIGIN=http://127.0.0.1:4173`，浏览器实际运行在 `127.0.0.1:5181`，导致账户、基础持仓和 SSE 请求全部被浏览器 CORS 拦截；增加完整锚定的 `localhost`/`127.0.0.1` 动态端口允许规则，并拒绝 `127.0.0.1.evil`。另一条回归是 SSE 新快照在当前行情完整、`usd_market_value`/周期字段暂缺时清除了上一份有效仓位和 24 小时盈亏；客户端现按字段独立保留最后有效值。
- **本轮验证证据（HEAD `eb161344c61418c4689f8b11fa84934c85cfe14b`，基线 `origin/investment-account-page` 同值，工作树未提交）**：`uv run pytest -q tests/contract/test_web_api.py tests/contract/test_investment_web_api.py tests/unit/application/test_portfolio_valuation.py tests/test_portfolio_refresh.py` → **53 passed、7 skipped、1 个既有 Starlette/httpx warning**；`npm --prefix web test -- --run` → **67 passed**；`VITE_FT_API_ORIGIN=http://127.0.0.1:8000 npm --prefix web run build` → 通过；`npm --prefix web run test:preview` → **6 passed**；`openspec validate --all --strict` → **18 passed、0 failed**；`openspec doctor` → **root ok**；`git diff --check` → 通过。
- **真实启动浏览器 QA**：本机 SQLite 后端 `8000` 与 Vite 预览 `5181` 使用真实数据访问 `#/investment-holdings`，账户、SSE 和基础持仓请求均返回 200；桌面 1280 px 的 9 个表头与首行 9 个单元格 `x/width` 轨道完全一致，7 个持仓均显示当前单价、当前市值、USD 仓位、浮盈亏和近 24 小时盈亏；375 px 下 `body.scrollWidth=375`、无页面级横向溢出，清空控制台后无错误。网络证据包含 `GET /api/v1/investment-portfolio/stream?...` 200；未写入账本，未提交、未推送、未创建 PR、未部署。
- **平均成本共列修复（2026-08-12，Asia/Shanghai）**：用户反馈当前单价后缺少平均成本；前端现以总成本 ÷ 数量计算平均成本，将“当前单价”和“平均成本”放在同一列的两行标签值中，展示币种切换时同步折算；未新增 API 字段或桌面列轨道。原型同步为同一结构，报价时间小字仍位于两行数值之后。
- **平均成本验证证据**：先加入失败回归后旧实现按预期失败；实现后 `npm --prefix web test -- --run tests/InvestmentLedgerPage.test.tsx` → **19 passed**。新增规格、设计、任务与原型已同步，生产构建和真实浏览器 QA 已在下方完成记录。
- **平均成本最终 QA（2026-08-12，Asia/Shanghai）**：完整 Web Vitest → **68 passed**；生产构建 → 通过；预览 Playwright → **6 passed**；真实 SQLite + Vite `5181` 浏览器检查显示 7 个持仓的第二列表头为“当前单价 / 平均成本”，首行同时显示当前单价、平均成本与报价小字，1280 px 表格仍为 9 列且第二列轨道对齐，375 px `body.scrollWidth=375` 且无控制台错误；`openspec validate --all --strict` → **18 passed、0 failed**；`openspec doctor` → **root ok**；`git diff --check` → 通过。Hallmark UI audit 复核标签层次、数值密度、报价小字和 320/375/414/768 px 响应式，结果 **0 critical、0 major、0 minor**。
- **本轮持仓展示回流（2026-08-12，Asia/Shanghai）**：用户反馈仓位为空、当前单价 / 平均成本列层次不清，并要求现金货币行置顶且只显示标的 / 账户和当前市值。先加入 Web 回归：缺少 `usd_market_value` 但报价币种明确为 USD 时可用 USD 市值兼容计算仓位；现金行固定置顶、仅保留两项字段；证券价格单元格按“当前单价 + 时段 / 平均成本 / 相对报价时间”三行渲染，当前单价与平均成本比较后分别使用红色、绿色或正常颜色。实现同时修正 820 px 以下价格列逐字竖排，并同步 `prototype/index.html`、proposal、design 和 delta spec。
- **本轮真实浏览器 QA 与验证（2026-08-12，Asia/Shanghai）**：真实 SQLite `~/.ft/finance-tracker.db`、后端 `8000`、Vite `5181` 下访问 `#/investment-holdings`；现金 `cny` / `usd` 行位于证券行之前，证券 7 行均显示 USD 仓位（约 `+5.06%` 至 `+20.53%`），真实 SSE/估值接口返回 200，当前价、平均成本、盘中时段和相对报价时间均可见。当前单价高于平均成本的 `ko.us` 为红色，低于平均成本的 `tsm.us` / `nvda.us` 等为绿色；1440 px 与 375 px 均无页面级横向溢出，控制台错误为 0。截图为 `/tmp/finance-holdings-1440-fixed.png` 与 `/tmp/finance-holdings-375-fixed.png`。`npm --prefix web test -- --run` → **72 passed**；生产构建通过；`openspec validate --all --strict` → **18 passed、0 failed**；`git diff --check` → 通过。Hallmark audit 技能动作不可用（当前环境未提供可执行入口），已以真实截图、响应式断言和样式检查完成等价审查；残余风险为需在恢复入口后补跑同一 audit 目标。
- **移动端价格标签回流（2026-08-12，Asia/Shanghai）**：用户指出移动端缺少现价和成本价标注。新增移动端专用“现价 / 成本价”小标签；PC 端隐藏标签，继续由表头承担列语义；三行价格结构、时段和相对报价时间保持不变。同步生产组件、样式、持仓原型和回归断言。真实 SQLite 浏览器检查在 375 px 下标签均可见、1440 px 下均隐藏，两个宽度 `body.scrollWidth === viewportWidth` 且控制台错误为 0；定向 Web 回归 **23 passed**，生产构建通过，`git diff --check` 通过。
- **标的名称层级验证（2026-08-12，Asia/Shanghai）**：先加入名称/代号/账户两行回归，旧实现按预期找不到来源名称；实现后受影响 Python 回归 **28 passed、3 skipped**，Web 持仓回归 **24 passed**，完整 Web 回归此前 **73 passed**，生产构建通过，20K 事件持仓性能门禁 **holdings_p95 419.9ms**。`openspec validate --all --strict` → **18 passed、0 failed**；`git diff --check` → 通过。真实 SQLite + FastAPI `8000` + Vite `5181` 浏览器 QA：1440/375 px 名称、代号/账户和现金置顶均正确，375 px `body.scrollWidth=375`，控制台错误 0；名称字段也在 `phase=holdings` 和 SSE 完整快照中透传。Hallmark 可执行入口当前不可用，已用真实截图、响应式断言、API 响应和样式检查完成等价审查；未修改账本数据，提交/推送前 HEAD 待记录。
- **标的名称层级回流（2026-08-12，Asia/Shanghai）**：用户要求持仓第一行展示标的名称，第二行展示代号/账户，并去掉重复币种。核查真实 SQLite 的 `investment_events.source_payload` 后确认 DFZQ/IBKR 导入均保存可用名称；查询层按标的代码提取该只读元数据并写入 `PortfolioPositionDTO.display_name`，缺失时前端仅回退代码，不猜测名称。生产表格和现金行统一为“名称 / 代号 · 账户”两行，币种继续只在价格、市值和盈亏值中出现；未改变持仓数量、成本、行情或任何账本事实。
