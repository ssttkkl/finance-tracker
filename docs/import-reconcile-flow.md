# 账单导入与 Reconcile 全流程

本文描述当前 `finance-tracker` 将原始账单导入 `.ft` 账本、识别重复与转账、生成审计并完成 Git 提交的实际流程。它覆盖现金类账单；证券账单有独立的导入路径。

## 1. 全景

账本采用双层存储：`records/<type>/<YYYY-MM>.csv` 保存可审计事实，`snapshot.yaml` 是由 records 重建的查询快照。原始账单永远不直接覆盖 records，所有变化都经过统一 CSV、校验和 reconcile。

```mermaid
flowchart LR
    A[原始账单] -->|ft convert| B[统一 CSV]
    B -->|ft append| C[按月 records]
    C -->|ft reconcile| D[自动整理]
    D -->|低置信候选| E[reconcile pending]
    E -->|明确决策后 continue| F[records 和 snapshot]
    D -->|无待审候选| F
    F -->|ft verify| G[一致性校验]
    G -->|ft commit| H[账本 Git 提交]
```

正常运行时使用项目环境而不是假设全局命令可用：

```bash
cd /path/to/finance-tracker
FT_DIR=/path/to/.ft uv run ft convert ...
FT_DIR=/path/to/.ft uv run ft append ...
FT_DIR=/path/to/.ft uv run ft reconcile
```

`FT_DIR` 指向账本仓库；代码仓库与账本仓库可以独立演进。`convert`、`append`、`reconcile` 的写操作会 stage 账本改动，但只有 `ft commit` 才创建账本提交。

## 2. 核心数据与职责

```mermaid
flowchart TD
    A[bills 原始文件] -->|导入器解析| B[原始交易字段]
    B -->|映射和规范化| C[统一 CSV 行]
    C -->|按账户类型和月份| D[records CSV]
    D -->|重放| E[snapshot.yaml]
    D -->|范围读取| F[reconcile 状态]
    F -->|自动或人工决定| G[audit CSV]
    G -->|追溯删除与配对| H[审计历史]
```

| 位置 | 责任 |
| --- | --- |
| `bills/` | 原始输入，只读存档。 |
| `mapping.yaml` | 将账单支付方式映射到已登记账户。 |
| `accounts.yaml` | 账户名称、类型、币种、启用状态。 |
| `records/cash`、`records/loan` | 按月的正式现金、负债事实记录。 |
| `snapshot.yaml` | records 重放得到的余额/持仓快照。 |
| `pending/` | 可暂停事务的隔离工作区，不是正式账本。 |
| `audit/reconcile` | 去重、转账、人工删除等可追溯审计结果。 |

正式 records 的 `record_id` 是事实主键。reconcile 的工作 CSV 另有会话展示用的 `record_id`（如 `r_000001`），并通过只读的 `source_record_id` 指回正式事实 ID；写回和删除均以 `source_record_id` 为准。

### 产物字段总览

不同阶段使用不同 schema，不能把 convert 的关系建议、reconcile 的审查决定或 audit 的结果字段写回正式 records：

| 产物 | 位置 | 用途 | 写入者 |
| --- | --- | --- | --- |
| 统一 CSV | convert 的 `-o <file>` | 待 append 的标准化导入事实 | convert |
| 正式 records | `records/<type>/<YYYY-MM>.csv` | 账本事实源 | append / reconcile |
| 快照 | `snapshot.yaml` | 从 records 重建的余额视图 | append / reconcile / verify --fix |
| 审查底稿 | `pending/reconcile/<id>/ai_working.csv` | 系统提示与审查决定 | reconcile + 审查者 |
| 审计 | `audit/reconcile/<run_at>.csv` | 去重、转账、人工删除的追溯证据 | reconcile |

## 3. Convert：账单变为统一 CSV

`ft convert <file> --source <source> --output <out.csv>` 选择对应导入器，例如支付宝、微信、工行信用卡/借记卡、建行借记卡。PDF 账单可额外传入密码。

```mermaid
flowchart TD
    A[原始账单文件] -->|选择 source 导入器| B[解析行与原始字段]
    B -->|支付方式映射| C[账户与币种]
    C -->|商户和平台规范化| D[统一交易行]
    D -->|生成稳定 fact ID| E[转换事实集合]
    E -->|保留退款和冲抵关系元数据| F[输出统一 CSV]
```

### Convert 的主要工作

1. 解析账单格式，保留原始交易对方、描述和支付方式。
2. 依据 `mapping.yaml` 匹配账户，校验账户存在且币种一致。
3. 规范化 `counterparty`、`description`、`source` 与分类，并输出统一字段。
4. 生成稳定事实 ID。ID 的输入必须能区分同日、同额、同商户的多笔真实交易；例如建行借记卡会纳入交易后余额，避免事实碰撞。
5. 对退款、冲抵保留事实，并写入强弱、关联方向和建议动作等元数据；跨来源或低置信判断留给 reconcile。

convert 当前不会创建 pending，会直接输出统一 CSV；它不删退款事实，也不对跨来源重复作最终决定。

### Convert 产物：统一 CSV

统一 CSV 固定包含 17 个字段：

- 基础事实：`record_id`、`date`、`amount`、`currency`、`counterparty`、`description`、`category`
- 账户与来源：`account_name`、`source`、`bill_source`
- 退款/冲抵关系：`offset_group`、`offset_role`、`offset_strength`、`offset_source`、`offset_rule_hint`、`offset_match_type`、`proposed_action`

其中 `proposed_action` 是 convert 对退款/冲抵关系的建议，不是 reconcile 审查的最终 `decision_action`。普通消费的关系字段通常为空，`proposed_action` 默认为 `leave_as_is`。

```yaml
# /tmp/wechat.csv 中的一行
record_id: wechat_8f21c0
date: "2026-06-12 12:35:31"
amount: "-55.20"
currency: CNY
counterparty: 麦当劳
description: 扫码付款
category: expense
account_name: 工行借记卡
source: 微信
bill_source: wechat
offset_group: ""
offset_role: ""
offset_strength: ""
offset_source: ""
offset_rule_hint: ""
offset_match_type: ""
proposed_action: leave_as_is
```

## 4. Append：统一 CSV 按月落盘

`ft append <converted-1.csv> <converted-2.csv> ...` 在写入前读取所有输入并校验。它按账户类型与交易月份归档为 `records/<type>/<YYYY-MM>.csv`，将既有行与输入行合并、排序后落盘。

```mermaid
flowchart LR
    A[多个统一 CSV] -->|预读并校验| B[账户和币种有效]
    B -->|按类型分流| C[cash 或 loan]
    C -->|按交易月份分组| D[YYYY-MM.csv]
    D -->|合并并排序后写入| E[正式 records]
    E -->|重建快照| F[snapshot.yaml]
    F -->|stage| G[账本工作区]
```

append 不负责事实去重或跨来源镜像去重。convert 在构建单份账单的事实集合时使用稳定事实 ID 排除同一输入中的重复行；微信/支付宝账单与银行卡账单之间的镜像仍应被保留到 records，交由 reconcile 在拥有完整上下文时处理。

### Append 产物：records 与 snapshot

append 将统一 CSV 按账户类型和月份写成 records。正式 records 固定包含 19 个字段：

- 基础事实：`record_id`、`date`、`amount`、`currency`、`counterparty`、`description`、`category`
- 账户与来源：`account_name`、`source`、`bill_source`、`transfer_account`、`locked`
- 退款/冲抵关系：`offset_group`、`offset_role`、`offset_strength`、`offset_source`、`offset_rule_hint`、`offset_match_type`、`proposed_action`

convert 没有输出的 `transfer_account` 和 `locked` 在 append 时补为空字符串。`locked=1` 表示人工锁定，reconcile 不会修改、去重或转账配对该行。

```yaml
# records/cash/2026-06.csv 中的一行
record_id: wechat_8f21c0
date: "2026-06-12 12:35:31"
amount: "-55.20"
currency: CNY
counterparty: 麦当劳
description: 扫码付款
category: expense
account_name: 工行借记卡
source: 微信
bill_source: wechat
transfer_account: ""
locked: ""
offset_group: ""
offset_role: ""
offset_strength: ""
offset_source: ""
offset_rule_hint: ""
offset_match_type: ""
proposed_action: leave_as_is
```

append 同时更新 `snapshot.yaml`。它的顶层字段是 `updated_at` 和 `accounts`；现金、负债、借贷账户按“账户名 -> 币种 -> 余额”存储：

```yaml
updated_at: "2026-07-14 20:30:00"
accounts:
  cash:
    工行借记卡:
      CNY: 1234.56
  loan: {}
  lend: {}
  security: {}
```

## 5. Reconcile：自动规则与待审门禁

`ft reconcile` 可处理全量、单月或日期范围。它先读取 scope 内 records，保留 `locked=1` 的人工锁定事实，并生成一份 reconciliation state。

```mermaid
flowchart TD
    A[读取 scope 内 records] -->|排除 locked 和零额边界| B[构建候选状态]
    B -->|high mirror 规则| C[自动删弱侧]
    B -->|weak mirror 规则| D[review 候选组]
    B -->|退款和冲抵规则| E[退款关联]
    B -->|转账规则| F[配对或单腿标记]
    C -->|汇聚自动结果| G{存在低置信待审}
    D -->|汇聚 review| G
    E -->|汇聚退款上下文| G
    F -->|汇聚转账结果| G
    G -->|否| H[改写 records 和重建 snapshot]
    G -->|是| I[创建 reconcile pending]
    H -->|记录自动结果| J[写正式 audit]
    I -->|暂停事务| K[等待明确决策]
```

### 自动判定

reconcile 的自动结果包括：

- 高置信镜像：同账户、同金额、同币种、时间满足渠道窗口，且一侧是具体商户/服务、另一侧是银行卡通道化文本时，只删除弱侧。
- 明确退款/冲抵：按既有退款规则净额化或关联。
- 明确转账：标记两侧 `transfer_out`/`transfer_in`；无法配对但信号明确时可标记单腿转账。

自动规则不会升级以下高风险场景：退款链、社交流/红包/群收款/转账、建行仅日期记录、多候选、双方文本都泛化或锁定记录。

### Reconcile pending 的内容

当存在 review 候选时，程序创建：

```text
pending/reconcile/<session_id>/
├── manifest.json
├── status.json
├── ai_working.csv
├── staged_records/
└── proposed_audit.csv
```

`ai_working.csv` 提供候选及必要上下文，包含退款关联行和已经自动删除的行。自动删除行以 `processing_status=dropped` 显示，便于审查整条链路，但不会在 continue 时被恢复。

### Reconcile 产物：审查底稿、暂存副本与拟议审计

`manifest.json` 的字段是 `session_id`、`kind`（固定为 `reconcile`）、`created_at`、`scope_from`、`scope_to`；`status.json` 的字段是 `session_id` 和 `status`，新会话状态为 `waiting_for_decisions`。

示例 `manifest.json`：

```json
{
  "session_id": "reconcile_2026-07-14_20-30-00",
  "kind": "reconcile",
  "created_at": "2026-07-14_20-30-00",
  "scope_from": "2026-06-01",
  "scope_to": "2026-06-30"
}
```

示例 `status.json`：

```json
{
  "session_id": "reconcile_2026-07-14_20-30-00",
  "status": "waiting_for_decisions"
}
```

`ai_working.csv` 的字段如下：

- 会话与事实标识：`record_id`、`source_record_id`、`session_id`
- 事实内容：`date`、`amount`、`currency`、`counterparty`、`description`、`category`、`account_name`、`source`、`bill_source`、`transfer_account`、`locked`
- 退款/冲抵关系：`offset_group`、`offset_role`、`offset_strength`、`offset_source`、`offset_rule_hint`、`offset_match_type`、`proposed_action`
- 原始和定位上下文：`raw_counterparty`、`raw_description`、`raw_payment_method`、`record_file`、`record_type`
- 系统提示与审查结果：`rule_hint`、`suggested_action`、`decision_action`、`decision_reason`、`processing_status`、`ai_group`

```yaml
# pending/reconcile/<session_id>/ai_working.csv 中的镜像候选
record_id: r_000042                 # 会话内展示 ID
source_record_id: icbc_debit_a91f   # 正式 records 的事实 ID，只读
session_id: reconcile_2026-07-14_20-30-00
date: "2026-06-12 12:35:32"
amount: "-55.20"
currency: CNY
counterparty: 深圳市财付通支付科技有限公司
description: 消费
category: expense
account_name: 工行借记卡
source: 银行卡
bill_source: icbc_debit
rule_hint: possible_mirror_weak_30s_cross_source
suggested_action: drop
decision_action: drop
decision_reason: 同账户同金额，微信侧为具体商户，银行卡侧为通道扣款
processing_status: active
ai_group: mirror_0001
```

`staged_records/` 是本次 scope 内原始 records 文件的副本，每个 CSV 使用上文的 19 列正式 records schema。`proposed_audit.csv` 使用下文 audit schema，包含已自动判定的结果和等待人工继续时应一并落盘的审计行。

## 6. Reconcile Pending 决策语义

```mermaid
flowchart TD
    A[ai_working.csv] -->|读取 rule hint| B[审查原始账单与上下文]
    B -->|确认同一镜像| C[drop 弱侧]
    B -->|确认同一镜像且保留当前行| D[keep 当前行]
    B -->|确认不同订单| E[leave_as_is]
    B -->|退款或转账关系| F[merge net 或 transfer 动作]
    C -->|记录删除依据| G[填写 decision_reason]
    D -->|记录保留依据| G
    E -->|记录排除依据| G
    F -->|记录关系依据| G
    G -->|continue 校验| H[正式写回]
    G -->|证据不足| I[保留 pending 不继续]
```

字段分工：

- `rule_hint`：程序命中的候选规则，例如 `possible_mirror_weak_30s_cross_source`。
- `suggested_action`：程序建议的动作，例如弱侧 `drop`、强侧 `keep`；它不是审查结论。
- `decision_action`：审查者最终选择的 `keep`、`leave_as_is`、`drop`、`modify`，或退款/转账的引用动作。
- `decision_reason`：审查者写入的证据和结论。带 `ai_group` 的 `processing_status=active` 行选择 `keep` 或 `leave_as_is` 时必须填写；`drop`、`modify`、退款合并和转账动作同样必须填写。
- `processing_status`：系统描述的工作行状态；自动删除行通常是 `dropped`，不可由审查者编辑。

`leave_as_is` 的含义是“已明确确认它不是需要处理的同一笔订单”，不能用来表示“暂时不确定”。证据不足时必须保持 pending，而不是调用 continue。

continue 时会校验行数、会话 ID、只读事实字段、双边转账关系和每个决策理由。它只替换 pending 涉及的真实 `source_record_id`，保留同一月文件中未触及的其他记录，随后重建 snapshot，并将自动审计与人工审计合并写入正式 audit。

### Reconcile 产物：正式 audit

无 pending 的 reconcile，或 pending continue 成功后，都会写出 `audit/reconcile/<run_at>.csv`。其字段分为：

- 运行范围：`run_at`、`scope_from`、`scope_to`
- 被处理的正式事实：`record_id`、`date`、`amount`、`currency`、`counterparty`、`description`、`category`、`account_name`、`source`、`bill_source`、`transfer_account`、`locked`、`offset_group`、`offset_role`、`offset_strength`、`offset_source`、`offset_rule_hint`、`offset_match_type`、`proposed_action`
- 审计定位与结论：`record_file`、`dedup_status`、`reconcile_status`
- 转账对手信息：`transfer_side`、`match_rule`、`match_confidence`、`counterpart_file`、`counterpart_account`、`counterpart_currency`、`counterpart_amount`

```yaml
# audit/reconcile/2026-07-14_20-31-00.csv 中的一行
run_at: "2026-07-14_20-31-00"
scope_from: "2026-06-01"
scope_to: "2026-06-30"
record_id: icbc_debit_a91f
date: "2026-06-12 12:35:32"
amount: "-55.20"
currency: CNY
counterparty: 深圳市财付通支付科技有限公司
category: expense
account_name: 工行借记卡
source: 银行卡
bill_source: icbc_debit
record_file: "/path/to/.ft/records/cash/2026-06.csv"
dedup_status: 去除
reconcile_status: ai_drop
transfer_side: ""
match_rule: ai_dedup_decision
match_confidence: ai
counterpart_file: ""
counterpart_account: ""
counterpart_currency: ""
counterpart_amount: ""
```

## 7. 完成、校验与提交

```mermaid
flowchart LR
    A[continue reconcile] -->|写入决定后的 records| B[重建 snapshot]
    B -->|合并自动和人工结果| C[audit/reconcile CSV]
    C -->|再次 ft reconcile| D{仍有 pending}
    D -->|是| E[继续审查或 abort]
    D -->|否| F[ft verify]
    F -->|一致| G[ft commit]
    F -->|不一致| H[定位 records 或账户问题]
```

完成条件：

1. 再次运行同一 scope 的 reconcile 不产生 pending。
2. `ft verify` 通过：cash/loan/lend 的账户引用有效，security 记录可重放且与快照一致。
3. audit 同时包含自动去重、转账处理和人工决策，能够解释每次删除或配对。
4. 确认无误后再执行 `ft commit`，将本次账本导入作为一个 Git 提交固定下来。

### Verify 与 Commit 产物

`ft verify` 不写新的账本文件（仅 `--fix` 会重建 `snapshot.yaml`），产物是进程退出码和校验输出：

```text
🔍 Security 校验
  ...
🔍 Cash/Loan/Lend 校验
  ...
✅ 校验通过
```

`ft commit -m "导入 2026-07 账单"` 的产物是 `.ft` 仓库的 Git commit。其可追溯字段由 Git 提供：commit hash、父提交、author、committer、提交时间和 message；命令不额外创建业务 CSV 或 YAML。

## 8. 操作检查表

```bash
# 1. 转换每份原始账单
FT_DIR=/path/to/.ft uv run ft convert bill.xlsx --source wechat --output /tmp/wechat.csv

# 2. 统一追加
FT_DIR=/path/to/.ft uv run ft append /tmp/wechat.csv /tmp/alipay.csv /tmp/bank.csv

# 3. 自动整理；若进入 pending，逐行明确填写 decision_reason
FT_DIR=/path/to/.ft uv run ft reconcile
FT_DIR=/path/to/.ft uv run ft reconcile --continue-with-decisions \
  /path/to/.ft/pending/reconcile/<session_id>/edited.csv

# 4. 验证幂等性和一致性
FT_DIR=/path/to/.ft uv run ft reconcile
FT_DIR=/path/to/.ft uv run ft verify

# 5. 确认后提交账本
FT_DIR=/path/to/.ft uv run ft commit -m "导入 2026-07 账单"
```

对于临时验证环境，最后一步可以省略；不要提交只用于验证的账本变更。
