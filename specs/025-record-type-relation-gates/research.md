# Research: 关系配对正式类型闸门

## 当前实现

- `src/ft/application/relations.py` 从现金仓储构造 `FactView`，但此前没有传递 `record_type`。
- `FactCandidateIndex` 主要按正负号、金额、币种和日期建桶，退款候选正向仍调用文本退款门槛。
- Phase C 的转账种子通过 `has_transfer_out_seed_signal()` 读取 `summary` 和文本；信用还款再通过还款词、商户词和对侧文本排除。
- Phase D 的退款种子通过退款词/来源信号判断；Phase B 的退款镜像通过双方文本退款词判断。

## 真实数据边界

当前重建库中：

- 正向 `refund` 545 条；
- 负向 `refund` 79 条，均为微信“已退款”状态下的原消费行，不能当作退款入账；
- 负向 `transfer_out` 473 条；
- 负向 `repayment` 43 条；
- 负向 `consumption` 或负向原消费 `refund` 共 8,616 条，少于全部负向现金 9,276 条。

因此关系层必须使用“类型 + 金额方向 + 账户类型”构造关系角色，不能直接把 `refund` 或 `income` 当作无方向角色。

## 决策

1. `record_type` 是一级类型唯一来源。
2. 金额方向只用于构造关系角色，例如正向 `refund` 是退款入账，负向 `repayment` 是还款转出。
3. 贷款账户正向 `income` 是工行信用卡还款入账的合法对侧，但只有在还款转出、账户拓扑、金额、时间和币种条件同时满足时才进入信用还款候选。
4. 文本保留为二级证据：商户/订单匹配、P2P 子类、云闪付桥接和提现细节；文本不能改变一级类型。`withdrawal_out` → `withdrawal_in` 只通过显式提现到账路径参与资金移动关系，`reversal` 与 `transfer_reversal` 都不参与消费退款关系。
5. 退款候选索引按账户、币种和日期分桶，在候选生成阶段排除不同账户。

## 重建对比证据（2026-08-02）

在 `/Users/huangwenlong/.ft/finance-tracker.db.record-type-relations-check-20260801-v2`
副本上执行全量 `ft relations check`，正式库未写入。以
`/Users/huangwenlong/.ft/finance-tracker.db.before-record-type-rebuild-20260801-2330`
为旧规则快照，并按 `source_type + record_id` 对齐事实（不使用跨重建变化的 SQLite 自增 ID）：

| 关系 | 旧规则 | 新规则 |
|---|---:|---:|
| `payment_mirror` accepted | 3,099 | 2,963 |
| `payment_mirror` pending | 0 | 1 |
| `refund_offset` accepted | 201 | 214 |
| `refund_offset` pending | 96 | 123 |
| `transfer_pair` accepted | 93 | 84 |
| `transfer_pair` pending | 17 | 4 |
| `credit_repayment` accepted | 14 | 14 |
| 总数 | 3,520 | 3,403 |

关系身份集合有 81 条新增、198 条移除、114 条同端点规则变化。移除的支付镜像中，
135 条的端点包含 `transfer_out`、`repayment` 或投资类型，属于新类型闸门预期；
91 条同端点镜像从普通时间规则变为退款双来源规则。新结果满足退款关系跨账户数为 0、
类型非法镜像数为 0、类型非法转账数为 0。工行信用卡 2026-05-25 19:11 的消费与
19:13 的 `record_type=refund` 以 `refund_offset.merchant_or_order.v1` accepted 配对，
19:16 的 222 元消费没有被错误复用。

## P0 审计与工程评审（2026-08-02）

针对当前业务库进行只读审计，确认以下问题均来自候选池或自动确认门槛过宽，
不是金额、时间或账户数据缺失：

1. 关系 #2262 将支付宝余利宝 `withdrawal_out` 与同一支付宝余额的
   `transfer_in` 自动配对。专用提现路径此前只校验金额和时间，未限制对侧为不同账户的
   银行来源。
2. 关系 #2271 将建行借记卡 `transfer_out` 与工行信用卡贷款账户的正向
   `income` 列为普通转账候选。原因是普通转账与信用账户还款共用宽泛的正向候选池。
3. 关系 #1827 在建行日期型账单中仅凭同账户、同金额和同业务日自动确认支付镜像；
   双方交易对方不同，缺少同笔证据。
4. 建行借记卡共有 40 条 `summary=无卡自助交易` 被误分为 `withdrawal_out`；
   其中 39 条具有京东、微信、PayPal 或 Nintendo 等商户型交易对方，应为消费。
5. 初步审计中有 7 条 P2P 退回被分类为 `refund`，覆盖微信转账、红包、群收款和
   QQ 红包。全量重导入后实际识别到 8 条；它们应为 `transfer_reversal`，不应作为消费退款种子或对侧。

工程评审结论已采纳：按路线在候选索引的输出处执行类型和来源硬闸门，而不是仅在
评分函数末尾排除；银行日期型候选在缺少交易对方、订单、卡尾号或可信时间时统一降为
待审核。此方案不新增存储字段、不改变金额语义，也不需要兼容旧库。验证在新的临时
SQLite 数据库中完成，正式业务库保持不变。

## P0 临时全量重建证据（2026-08-02）

在独立临时 SQLite 库中创建 schema 和工作区后，使用 `/Users/huangwenlong/.ft/accounts.yaml`
初始化账户，并导入 `.ft/bills` 下全部 12 份账单。导入结果为 11,394 条现金流水和 497 条证券流水；
现金来源数量分别为支付宝 3,059、微信 3,331、工行信用卡 2,847、工行借记卡 1,205、建行借记卡 952。
全量关系扫描连续执行两次，第二次的活动关系统计不变：

| 关系 | 自动确认 | 待审核 | 合计 |
|---|---:|---:|---:|
| `payment_mirror` | 2,799 | 161 | 2,960 |
| `refund_offset` | 225 | 123 | 348 |
| `transfer_pair` | 42 | 2 | 44 |
| 合计 | 3,066 | 286 | 3,352 |

作为只读参照，未替换的业务库仍有 2,600 条活动关系。两库之间包含早期导入和既有记录类型
重建的累积差异，不能把 752 条总数差异全部归因于本轮 P0；因此验收以类型闸门和端点不变量为准：

- 8 条微信 P2P 退回均为 `transfer_reversal`，参与 `transaction_relations` 的数量为 0。
- `refund_offset` 的双边端点均是消费/原消费退款与正向 `refund`；类型越界为 0。
- `payment_mirror` 的双边端点均是消费或原消费退款；类型越界为 0。
- 44 条 `transfer_pair` 均属于三条互斥路线：普通转账 20 条（18 自动确认、2 待审核）、
  支付平台提现到账 10 条、信用账户还款 14 条；类型越界为 0。
- 建行 `summary=无卡自助交易` 的 40 条记录均为 `consumption`。
- 收支投影全量重建完成，状态为 `ready`，包含 8,328 个投影和 11,394 个成员。

## 最终审查与收敛证据（2026-08-02）

范围化 gstack 代码审查发现 4 处早期实现仍可由文本跨越正式类型闸门的路径，均已用最小改动修复并补充回归测试：

1. 支付平台提现入账必须同时是平台来源和 `withdrawal_in`，不能仅凭提现文本进入专用路线。
2. 提现到账的银行对侧必须是银行来源的 `withdrawal_in` 或 `transfer_in`，不能由文本回退放行。
3. 确定性支付镜像分组先按消费/消费退款角色过滤，避免其他类型占用同笔分组。
4. 平台退款硬键在提议配对前验证“消费对侧 + 正向消费退款”角色，避免 `transfer_reversal` 进入 Phase A。

对最终代码运行的受影响关系回归组为 `117 passed, 2 skipped`；完整 `uv run pytest -q` 为
`1113 passed, 109 skipped, 1 warning`；迁移与双后端合同组为 `34 passed, 6 skipped`；
`uv run python -m compileall -q src tests` 和 `git diff --check` 通过。真实 PostgreSQL 因
`FT_TEST_POSTGRES_URL` 未配置而跳过，补跑命令为
`FT_TEST_POSTGRES_URL='<PostgreSQL URL>' uv run pytest -q tests/contract/test_dual_backend_record_type.py tests/test_postgres_statement_import.py`。
`web/` 的 `npm run build` 被既有 `web/tests/CashTable.test.tsx:37` 中 `onEvidence` 的参数数量错误阻断，
该文件不属于本 Feature 的变更范围。Web QA 与 Hallmark 审计不适用：本轮未改 Web 行为、页面或样式。

`$speckit-analyze` 覆盖 US1–US3、路线合同、迁移和 A 类验证门禁，未发现 CRITICAL/HIGH 缺口；
`$speckit-converge` 对实现、规格、方案和任务进行对照后未追加任务。

## 多候选部分退款实施证据（2026-08-02）

此前退款匹配器在多个强候选时直接生成待审核关系，没有实现“部分退款选择最近候选”的规则。实现分为两层：

1. `src/ft/domain/relations/refund/match.py` 只从正式强匹配候选中筛选，要求退款金额同时严格小于消费原额和当前剩余可退款金额；随后按退款时间减消费时间的最小值选择，时间差并列时继续待审核。自动关系的证据增加 `partial_nearest_unique`、候选数量和候选事实 ID。
2. `src/ft/domain/relations/pipeline.py` 在同一扫描中保留已接受的部分退款消费候选，逐笔扣减本地剩余金额；退款事实仍立即占用，消费剩余金额耗尽后才占用消费事实。`src/ft/application/relations.py` 在加载历史关系时也只占用退款事实和已耗尽的消费事实，保留有剩余金额的消费候选。这样一笔消费可以跨扫描或在同一扫描中接收多笔不超过剩余金额的部分退款。

验证先以新增回归测试确认 3 条预期失败，再实现并转绿；跨扫描回归随后确认第二笔新增退款能够复用仍有余额的原消费；最后补充的唯一标题精确全额退款边界测试也先失败后转绿。受影响关系测试为 `134 passed, 7 skipped`，完整测试为
`1118 passed, 110 skipped, 1 warning`；`compileall` 和 `git diff --check` 通过。

在独立临时 SQLite 副本中清理关系和投影表后重建，连续执行三次关系扫描，结果稳定为：

| 关系 | 自动确认 | 待审核 |
|---|---:|---:|
| `payment_mirror` | 2,799 | 161 |
| `refund_offset` | 310 | 46 |
| `transfer_pair` | 42 | 2 |

其中 120 条已确认退款关系带有 `partial_nearest_unique` 信号；`transfer_reversal` 没有进入任何活动关系。随后收支投影重建为 `ready`，包含 8,240 个投影和 11,394 个成员。验证只写入临时副本，没有改动正式业务库。

收紧“唯一标题精确”规则后重新清空关系表验证：多候选全额退款不再因唯一标题精确而自动确认，临时库连续三轮扫描稳定为 `refund_offset` 310 条自动确认、46 条待审核；其中 120 条仍带 `partial_nearest_unique`。收支投影状态为 `ready`，包含 8,243 个投影和 11,394 个成员。

## 全额退款最近匹配与镜像事件分析（2026-08-02）

当前重建库中 `自助侠` 有 13 条退款待审核。候选中微信负向原消费行与工行信用卡负向消费行已经由 `payment_mirror` 证明为同一经济流水，但退款候选仍把两条镜像流水分别计数，导致最近候选出现伪并列。折叠镜像事件后，12 条为金额小于消费剩余金额的部分退款，1 条为金额等于消费剩余金额的全额退款；13 条的最近经济事件均唯一且在普通自动窗口内。

因此本轮采用以下决策：普通退款候选和自动确认窗口统一为 15 天（含边界），订单/交易号锁定证据仍可扩展到 30 天；退款候选先按正式类型、同账户、同币种、方向、时间和剩余金额过滤，再按订单/交易号、标题、标准化对手方和同账户金额证据分级；已接受 `payment_mirror` 关系折叠为经济事件后，部分退款和全额退款都允许在最高证据等级中选择时间差最小且唯一的候选。时间差并列、金额超额或超出自动窗口继续 `pending_review`。

替代方案“全额退款永远不使用最近候选”被拒绝：它会保留当前 `自助侠` 这类同商户、同金额、无订单号但最近时间唯一的真实配对为 pending。替代方案“直接在原始镜像流水上取最近事实”也被拒绝：同一经济事件的微信行和工行行会制造假并列，且可能导致关系端点重复占用。

## 最终实现验证（2026-08-02）

在 `/tmp/finance-tracker-nearest-rebuild.Uyk9Fx/finance-tracker.db` 创建全新 SQLite 库，按现有账户和映射导入 `.ft/bills` 中全部现金账单，共 11,394 条现金流水；另外导入可解析的 IBKR 账单 36 条投资流水。关系扫描连续三次后，第二、三次结果一致：

| 关系 | 自动确认 | 待审核 |
|---|---:|---:|
| `payment_mirror` | 2,096 | 161 |
| `refund_offset` | 335 | 12 |
| `transfer_pair` | 11 | 2 |

`refund_offset` 的自动关系中，`partial_nearest_unique` 为 73 条，`full_nearest_unique` 为 19 条；剩余 `自助侠` 待审核关系均因当前消费剩余金额不足，未再出现镜像行制造的伪并列。退款关系跨账户数为 0，`transfer_reversal` 端点数为 0。收支投影重建为 `ready`，包含 8,952 条投影和 11,394 个成员。验证只写入临时库，正式业务库未修改。

## 超额退款候选硬过滤修复证据（2026-08-02）

前一版实现把 `refund_amount > remaining_before` 只当作“不可自动确认”，仍会把候选写成双边或开放待审核关系。修复前先新增两个回归测试并观察到预期失败：单个超额消费仍返回关系；超额消费与合法消费同时存在时，`candidate_count` 仍包含超额消费。

修复将剩余金额判断前移到 `src/ft/domain/relations/refund/match.py` 的候选生成阶段。退款金额大于当前剩余金额的消费不再生成证据、不参与优先级排序，也不进入 `candidate_count`、候选事实 ID 或待审核关系。

验证结果：超额回归测试 `2 passed`；受影响关系组 `140 passed, 14 skipped`；完整测试 `1124 passed, 110 skipped, 1 warning`；`compileall` 和 `git diff --check` 通过。在临时 SQLite `/tmp/finance-tracker-overfilter.IpBMD1/finance-tracker.db` 清空关系后重建，结果为 `payment_mirror` 2,095/162、`refund_offset` 337/8、`transfer_pair` 11/2（依次为 accepted/pending_review）；全库 `over_refund` 关系为 0，退款 pending 全部为开放待配对；收支投影状态为 `ready`，包含 8,951 条投影和 11,394 个成员。正式业务库未写入，旧库中的历史 pending 需按既定流程重建后消失。

范围化审查未发现本次硬过滤实现的问题。完整 dirty worktree 审查另报告了更早改动中的 `reversal` 收支投影口径和迁移降级兼容问题；前者不属于本次超额候选修复，后者与用户已确认的“不提供旧库兼容、重新建库导入”约束冲突，均未纳入本次变更。

范围化 Codex review 未发现本轮退款匹配实现的可执行问题；迁移审查提出了旧 `record_type` 回填、旧 `withdrawal` 升级映射和降级映射三个兼容建议。它们与本 Feature 明确的“新建库并重导入、不提供旧库兼容或历史回填”约束冲突，因此不采纳，正式重建必须从当前 head 创建新库。
