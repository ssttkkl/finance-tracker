# AI 工作 CSV 驱动的可暂停账单整理流程设计

## 目标

为 `finance-tracker` 的账单整理流程建立一套“程序生成中间 CSV、外部 AI 编辑 CSV、程序继续执行”的机制，用于处理以下场景：

1. 退款核销
2. 跨来源镜像去重
3. 转账识别

本设计的目标不是在 `finance-tracker` 内部直接调用 AI，也不是让程序独自完成所有模糊判断，而是拆成三层：

- 程序负责解析、初步标准化、生成可编辑工作底稿
- 外部调用方 AI 通过 Skill 提示词读取并修改该底稿
- 程序再根据 AI 修改后的底稿继续执行并正式落地

---

## 设计原则

### 不在程序内部调用 AI

`finance-tracker` 不直接依赖模型 API，也不在 `src/` 内部发起 AI 请求。

AI 判断由调用方完成，调用方通过 Skill 提示词把命令行、中间 CSV 和决策逻辑串成完整自动流程。

### 不让候选筛选阻断 AI 视野

程序不应只把“挑出来的少量候选”给 AI，而应尽量给 AI 一份全量、接近最终结构的工作 CSV。

这样可以避免：

- 候选召回漏掉
- AI 根本看不到关键记录
- 候选分组策略过早限制上下文

### 待决策时不污染现有正式数据

当 `convert` / `reconcile` 需要 AI 介入时：

- 不修改正式 `.ft/records`
- 不修改正式 `snapshot.yaml`
- 不输出正式最终结果
- 所有准备修改内容隔离存放
- 可随时 `--abort`

### 程序输出的是 AI 工作底稿，不是最终结果

AI 编辑的是一份 `working CSV`，不是正式账本文件。

程序在 `--continue-with-decisions` 时才将这份工作底稿解释成最终结果并落地。

### AI 主要修改状态列与少量规范化列

为了保证可控性，AI 不应随意改原始事实字段。

默认约束如下：

#### 原始事实字段默认只读

- `date`
- `amount`
- `currency`
- `bill_source`
- `raw_counterparty`
- `raw_description`
- `raw_payment_method`
- `record_id`
- `session_id`

#### AI 允许修改的主要字段

- `counterparty`
- `description`
- `category`
- `transfer_account`
- `row_status`
- `ai_action`
- `ai_group`
- `ai_reason`

如后续确有需要，可扩展允许修改的字段，但默认应以保守为准。

---

## 用户可见命令流程

## convert

### 首次执行

```bash
ft convert <bill> -s <source> -o <output.csv>
```

### 如果无需 AI 介入

程序直接生成正式 `output.csv`，以及必要的退款追踪文件。

### 如果需要 AI 介入

程序进入 pending 状态：

1. 创建 pending convert session
2. 生成 AI 工作 CSV
3. 隔离保存中间文件
4. 输出提示：
   - `ft convert --continue-with-decisions <edited.csv>`
   - `ft convert --abort`

### 继续执行

```bash
ft convert --continue-with-decisions <edited.csv>
```

### 中止执行

```bash
ft convert --abort
```

## reconcile

### 首次执行

```bash
ft reconcile --month 2026-06
```

### 如果无需 AI 介入

程序直接完成：

- 去重
- 转账识别
- records 改写
- snapshot 重建
- 审计输出

### 如果需要 AI 介入

程序进入 pending 状态：

1. 创建 pending reconcile session
2. 生成 AI 工作 CSV
3. 隔离保存准备修改的 records
4. 不修改正式 records / snapshot
5. 输出提示：
   - `ft reconcile --continue-with-decisions <edited.csv>`
   - `ft reconcile --abort`

### 继续执行

```bash
ft reconcile --continue-with-decisions <edited.csv>
```

### 中止执行

```bash
ft reconcile --abort
```

---

## Pending 事务模型

### 目录结构

统一使用：

```text
~/.ft/pending/
```

建议结构：

```text
~/.ft/pending/convert/<session_id>/
~/.ft/pending/reconcile/<session_id>/
```

### convert pending session

建议内容：

```text
pending/convert/<session_id>/
├── manifest.json
├── status.json
├── ai_working.csv
├── proposed_output.csv
└── proposed_refunds.csv
```

#### 文件说明

- `manifest.json`：记录 `session_id`、`command`、`created_at`、`source_file`、`output_path`、`status`、`mode`
- `status.json`：记录当前状态，如 `waiting_for_decisions`、`continued`、`aborted`
- `ai_working.csv`：AI 需要读取和编辑的工作底稿
- `proposed_output.csv`：规则层初步生成的中间结果，用于继续执行时参考
- `proposed_refunds.csv`：退款追踪中间文件

### reconcile pending session

建议内容：

```text
pending/reconcile/<session_id>/
├── manifest.json
├── status.json
├── ai_working.csv
├── staged_records/
│   ├── cash/...
│   └── loan/...
└── proposed_audit.csv
```

#### 文件说明

- `staged_records/`：准备修改的 records 副本，尚未正式落地
- `ai_working.csv`：本次 scope 内供 AI 编辑的工作底稿
- `proposed_audit.csv`：中间审计底稿，用于 continue 时生成正式审计结果

---

## AI 工作 CSV 设计

### 核心目标

AI 工作 CSV 应满足：

1. 结构接近最终结构
2. 保留足够中间状态
3. 支持 AI 直接逐行编辑
4. 支持程序继续解释并落地

### 基础字段

建议保留与最终记录接近的主字段：

- `record_id`
- `session_id`
- `date`
- `amount`
- `currency`
- `counterparty`
- `description`
- `category`
- `account_name`
- `source`
- `bill_source`
- `transfer_account`
- `locked`

### 原始上下文字段

建议增加：

- `raw_counterparty`
- `raw_description`
- `raw_payment_method`
- `record_file`
- `record_type`

说明：

- `convert` 阶段的 `record_file` 可为空或记录来源文件
- `reconcile` 阶段的 `record_file` 应记录原 records 文件路径

### 中间状态字段

建议增加：

- `row_status`
- `ai_action`
- `ai_group`
- `ai_reason`
- `rule_hint`

### 字段语义

#### `row_status`

表示当前行在工作流中的状态。

建议值：

- `active`
- `pending_ai`
- `candidate_refund`
- `candidate_dedup`
- `candidate_transfer`
- `drop_after_merge`
- `merged_net`
- `transfer_out`
- `transfer_in`

#### `ai_action`

这是 AI 主要需要修改的列。

建议值采用简单字符串协议，例如：

- `keep`
- `drop`
- `merge_refund_into:<record_id>`
- `net_with:<record_id>`
- `mark_transfer_out_to:<record_id>`
- `mark_transfer_in_from:<record_id>`
- `leave_as_is`

如后续发现需要更强约束，可再改成多列结构，但第一版建议保持简单直接。

#### `ai_group`

让 AI 把同一事件相关的记录标成一个组，例如：

- `refund_001`
- `dedup_014`
- `transfer_009`

程序可利用该字段辅助校验多行一致性。

#### `ai_reason`

AI 对本次修改给出的简短说明，用于审计、调试和复盘。

#### `rule_hint`

程序预填的提示信息，用于帮助 AI 理解这行为什么值得关注。

例如：

- `same_amount_same_day`
- `possible_refund_chain`
- `possible_bank_mirror`
- `possible_transfer`
- `weak_signal_only`

`row_status`、`ai_group` 与 `rule_hint` 的组合已经足以表达“哪些行被机械层挑出来、属于哪类候选关系、哪些行需要一起判断”，因此不再需要额外的布尔提示列。

---

## convert 工作流细节

### 首次运行

`ft convert ...` 首先完成：

1. 原始账单解析
2. 基础字段标准化
3. 轻量规则处理
4. 生成全量 AI 工作 CSV

### 轻量规则处理可包含

- 明显合法的基础归一化
- 零歧义退款核销
- 零歧义状态补全

### 但不应包含

- 对多候选退款链的强行判断
- 对模糊情况的强行净额化

### 何时进入 pending

满足任一条件即可进入 pending：

1. 存在模糊退款核销情况
2. 存在跨来源关联但无法高置信度自动处理
3. 存在程序不想直接决定的边界项
4. 用户显式要求始终导出 AI 工作 CSV

### continue 语义

`ft convert --continue-with-decisions <edited.csv>` 时，程序应：

1. 找到唯一 pending convert session
2. 校验 `session_id`
3. 校验 `record_id` 未丢失、未重复
4. 校验只允许修改允许改的列
5. 根据 `ai_action` / `row_status` 解释最终结果
6. 生成正式 `output.csv`
7. 生成正式退款追踪文件
8. 清理 pending session

---

## reconcile 工作流细节

### 首次运行

`ft reconcile ...` 首先完成：

1. 读取 scope 内 records
2. 基础规则分析
3. 生成 AI 工作 CSV
4. 生成 staged records 副本

### 轻量规则处理可包含

- 明显确定的 dedup / transfer 预标注
- 基础 hints
- 候选状态标注

### 但不应包含

- 对模糊多候选去重直接删行
- 对模糊多候选 transfer 直接改 category 并正式落地

### 何时进入 pending

满足任一条件即可进入 pending：

1. 存在潜在镜像去重但证据不足
2. 存在潜在转账识别但证据不足
3. 存在链式事件需要整体判断
4. 用户希望本次 reconcile 由 AI 统一整理

### continue 语义

`ft reconcile --continue-with-decisions <edited.csv>` 时，程序应：

1. 找到唯一 pending reconcile session
2. 校验 `session_id`
3. 校验 `record_id`
4. 校验 AI 未修改只读字段
5. 根据 `row_status` / `ai_action` 解释最终 records 结果
6. 写回正式 `records/`
7. rebuild snapshot
8. 生成正式 audit
9. 清理 pending session

---

## continue 时如何解释 AI 工作 CSV

程序不要求 AI 输出独立 decisions 文件，而是直接解释编辑后的 CSV。

### 退款核销

例如：

- 一条 refund 行 `ai_action=merge_refund_into:e_001`
- 程序找到 `record_id=e_001` 的 expense 行
- 计算：
  - 全额核销：删除两边或只保留合规结果
  - 部分核销：生成净额记录
- 根据 `row_status` / `ai_group` / `ai_reason` 写审计痕迹

### 去重

例如：

- 一条银行卡镜像腿 `ai_action=drop`
- 对应平台腿 `ai_action=keep`

程序据此在最终结果中：

- 删除被 `drop` 的行
- 保留 `keep` 的行

### 转账识别

例如：

- 一条行 `ai_action=mark_transfer_out_to:r_002`
- 另一条行 `ai_action=mark_transfer_in_from:r_001`

程序据此：

- 将两条记录 `category` 改成 `transfer_out` / `transfer_in`
- 设置 `transfer_account`

---

## Abort 语义

### convert

`ft convert --abort` 必须：

1. 删除当前 pending convert session
2. 删除 `ai_working.csv`
3. 删除 `proposed_output.csv`
4. 不生成正式输出
5. 不影响现有 `.ft` 数据

### reconcile

`ft reconcile --abort` 必须：

1. 删除当前 pending reconcile session
2. 删除 staged records 副本
3. 删除中间 audit
4. 不改正式 `records/`
5. 不改 `snapshot.yaml`

---

## 数据污染控制

在 pending 状态下，禁止：

- 修改正式 `records/`
- 修改正式 `snapshot.yaml`
- 生成正式输出 CSV
- stage 到正式 git 工作区
- 写正式 reconcile 审计文件

只有 continue 成功后才允许正式落地。

---

## 需要 AI 重点处理的场景

本设计默认 AI 工作 CSV 是全量底稿，但程序仍应通过 `row_status` / `ai_group` / `rule_hint` 提示高风险场景。

### refund

- 同商户多笔同金额消费对应一笔退款
- 只能靠 description 语义判断
- gross refund 链式情况
- 归一化商户名压平后的歧义
- 跨文件 / 跨来源退款链

### dedup

- 多个可能平台腿
- 品牌级相似但不是唯一
- 文本模板化
- 同分钟多笔相同金额
- 弱渠道腿 vs 具体商户腿

### transfer

- 同额异号但候选不唯一
- 只有弱信号词
- 同日宽时间窗疑似调拨
- single-leg 内部转账歧义
- 跨边界事件

---

## 校验规则

继续执行前，程序必须校验 edited CSV：

1. `session_id` 与当前 session 一致
2. `record_id` 没丢、没重复
3. 只读字段未被修改
4. `ai_action` 符合语法
5. 引用的 `record_id` 必须存在
6. transfer 双边动作应自洽
7. refund 核销动作应可解释
8. 最终结果不产生非法字段状态

校验失败时应阻断 continue，并输出可操作错误信息。

---

## 与 Skill 的关系

程序不负责调 AI。

Skill 负责提示调用方 AI：

1. 先运行 `ft convert` / `ft reconcile`
2. 如果进入 pending，读取 `ai_working.csv`
3. 按规则编辑：
   - `row_status`
   - `ai_action`
   - `ai_group`
   - `ai_reason`
   - 必要的规范化字段
4. 保存编辑结果
5. 运行 `--continue-with-decisions`
6. 如需放弃，运行 `--abort`

---

## 非目标

本设计不做：

1. 程序内部调用模型
2. 复杂的 JSON decision protocol 作为主交互方式
3. 依赖“候选组必须完美召回”作为前提
4. 默认回落到人工审查

---

## 总结

本设计将账单整理流程改造成：

**程序生成 AI 工作 CSV → AI 直接编辑该 CSV → 程序继续执行并正式落地**

它的优势是：

1. AI 能看到全量初步结果，不容易因候选漏召回而失明
2. 交互对象是表格，适合 Skill 和调用方 AI 操作
3. 程序仍然掌控事务状态、校验、落地和回滚
4. 待决策过程中不污染现有 `.ft` 数据
