## Context

投资事件在 `investment_events.action` 中保存业务类型，而现金流水在 `cash_transactions.record_type` 与 `record_subtype` 中保存规范类型。现有关系扫描和收支投影只接受两条现金流水；`transaction_relations` 中的事实类型列不足以安全表示跨表端点。详见 [proposal.md](proposal.md) 与对应 delta 规格。

## Goals / Non-Goals

**Goals:**

- 使投资事件与现金流水以一致的「记录类型 + 记录子类型」术语表达可匹配资金移动。
- 用专用关系保存收支账户与投资账户之间的一对一外部资金调拨，并保持候选、人工决定和投影可审计。
- 在不绑定来源名称的前提下，基于导入时归一的字段提供确定性扫描。
- 保持 SQLite 与 PostgreSQL 的结构、精度、幂等和事务可见行为一致。

**Non-Goals:**

- 不将投资账户内部子账户调拨纳入本次收支账户配对。
- 不把任意现金调整、费用、税费、奖励或外汇净额自动解释为出入金。
- 不把跨表关系塞入既有 `transaction_relations`，也不把投资事件复制成现金流水。
- 不支持拆分、一对多或多对多资金调拨；金额不相等的情形保留人工后续处理。

## Decisions

### `action` 物理改名为 `record_type`

`investment_events.action` 是投资事件的业务记录类型，不额外创建冗余列。迁移将列名改为 `record_type`，并在领域 DTO、导入器、投影重放、查询和 CLI/Web 合同中同步替换。新增 `record_subtype`：

- `funding` 使用 `external` 或 `subaccount`；
- `trade`、`income`、`expense`、`reversal`、`subscription`、`adjustment` 与 `snapshot` 使用固定经济事实子类型；
- 方向只由现金部分在付出资产或换入资产决定，绝不编码进 `record_type`。

这样 `record_type` 仍驱动持仓重放，`record_type` 与 `record_subtype` 同时驱动关系候选资格。相比新增第二个 `record_type` 列，可避免双字段语义漂移。

#### 固定枚举与历史回填

投资事件的 `record_type` 固定为 `funding`、`trade`、`income`、`expense`、`reversal`、`subscription`、`adjustment` 或 `snapshot`。成交、现金股息、利息、佣金和余额校准均使用各自经济事实类型，不保留 `deposit`、`withdraw`、`fee`、`dividend`、`checkin` 等旧一级类型。

`record_subtype` 必须由 `record_type` 决定：

| `record_type` | 允许的 `record_subtype` |
| --- | --- |
| `funding` | `external`、`subaccount` |
| `trade` | `security`、`fx`、`repo` |
| `income` | `dividend_cash`、`dividend_stock`、`interest`、`reward` |
| `expense` | `commission`、`tax`、`interest`、`handling_fee`、`penalty` |
| `reversal` | `expense_tax`、`expense_interest`、`expense_commission`、`funding_withdrawal` |
| `subscription` | `ipo_debit`、`ipo_refund` |
| `adjustment` | `fx_net`、`manual`、`unclassified` |
| `snapshot` | `cash`、`position` |

历史回填先读取 `source_type` 和 `source_payload` 中的结构化原生类型，包括未来导入保存的 `action_raw`。明确的券商入金/出金与同步渠道链上存取款回填为 `funding(external)`；明确的保证金、日内融或子账户划转回填为 `funding(subaccount)`。利息、税费、佣金、外汇净额、奖励和出金退款分别映射到上表的非资金供给语义。历史行无法由现有来源字段安全判断时，迁移将其改为 `adjustment(unclassified)`，保留金额、来源快照和幂等身份，但不得进入资金调拨扫描。旧东方证券来源快照把原始动作折叠为 `DEPOSIT` / `WITHDRAW` 时，迁移必须视为无法安全判断，不能据此设为外部出入金；经用户明确授权的原始账单重解析才可精确修复。

### 专用跨表关系而非泛化现有关系表

新增 `cash_investment_funding_relations`，包含工作区、现金流水 ID、投资事件 ID、方向、状态、规则 ID、受限证据、创建/决定信息和活动槽位。它对两端使用工作区复合外键，并为活动关系建立「现金端点唯一」「投资端点唯一」和「端点对唯一」约束。

现有 `transaction_relations` 继续只描述两条现金流水。泛化它需要把业务键、候选数组、锚点和收支投影都改为多态引用，且会让独立主键冲突成为数据风险；专用表的迁移和回滚范围更小。

### 分层、来源无关的扫描策略

导入器将原生字段转为投资事件规范字段；关系扫描只读取已持久化字段。候选必须同工作区、投资侧为 `funding(external)`、现金部分方向相反、同币种、精确 Decimal 金额相等，且未被确认关系占用。

按 `Asia/Shanghai` 业务日窗口寻找候选。带 `investment_in` / `investment_out` 现金类型的唯一同日候选可自动确认；普通 `transfer_in` / `transfer_out` 或跨日候选只进入待审核。候选不唯一、金额或币种不同一律不得自动确认。

### 投影以已确认关系的单现金端点标记内部转账

收支投影读取已确认资金调拨关系时，为其现金端点生成 `internal_transfer(bank_security_transfer)` 标记，而不把投资事件当作现金投影成员。投影证据持久化关系 ID 与受限摘要；投资事件和完整来源行快照仍通过关系查询取得，避免重复事实和违反现有双现金端点几何约束。

## Risks / Trade-offs

- [历史事件语义已错误归类] → 从来源行快照的结构化字段回填；无法确定的事件失败关闭并列出待处理项，不自动纳入扫描。
- [同金额重复转账导致误确认] → 强语义同日且唯一才自动确认，其余保留待审核；两端确认占用互斥。
- [数据库迁移破坏现有快照] → 迁移前备份，迁移后在同一事务重放并校验投资快照；SQLite 与真实 PostgreSQL 都运行迁移契约。
- [投影与关系状态失步] → 确认、驳回或取代关系时在同一事务维护受影响现金投影；全量重建验证每条现金流水唯一归属。
- [来源字段含敏感数据] → 关系证据只保存规则、时间窗口、金额/币种、候选数和业务行标识，不复制来源行快照或账号。

## Migration Plan

1. 创建迁移前备份，校验数据库文件与 SQLite `-wal` / `-shm` 同步状态。
2. 在两个后端将 `investment_events.action` 改名为 `record_type`，新增受约束的 `record_subtype`，创建资金调拨关系表与索引。
3. 以现有 `source_payload` 重放每条投资事件的规范化分类；保留来源快照与幂等键。无法安全分类的行落为 `adjustment(unclassified)`；它们不能被迁移或扫描提升为外部出入金。
4. 为合格的历史外部出入金生成候选，不自动越过唯一性和端点占用约束；重建并校验收支投影和投资快照。
5. 回滚时恢复经校验的备份；应用回滚只移除新增关系和字段访问，不删除来源行快照或投资事件。

## Open Questions

无。固定枚举、历史降级策略与真实账本重解析授权边界均已确认。
