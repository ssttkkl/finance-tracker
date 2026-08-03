# 功能规格：正式事实结构清理（内联溯源 + 去掉冗余表/列）

## Purpose
- 问：导入溯源链在文件 digest 不再作去重后还有何用？ → 答：行级业务标识 + 原始 payload 仍被业务使用；希望与行数据放在一起。 本能力的行为契约由迁移后的需求与场景持续维护。

## Requirements

### Requirement: 账单行溯源与正式事实同在一行
系统 MUST 作为账本所有者，在每条本变更发布后的账单派生正式事实同一行保存导入渠道、业务行键、完整的来源行快照和正式字段。来源行快照 `source_payload` MUST 是来源账单中该业务行全部原始列名和值的 JSON 表示：不得遗漏来源列、不得因值为空而省略列、不得混入解析、账户映射、关系检查、格式规范化或兼容性字段。来源账单的无标题列 MUST 使用空字符串 `""` 作为 JSON 键并保留其原始值；若任一列名重复（包括多个无标题列），系统 MUST 拒绝导入，不能编造列名。正式字段 `counterparty`、`counterparty_account`、`note`、`record_type`、`account_name`、`source_type` 和 `record_id` 可以由来源行派生，但不得反写或补充进 `source_payload`。对无法以唯一列结构表达的 PDF 业务行，系统 MUST 保存解析器可归属该行的全部原始表格单元或原始文本单元，且不得保存推断值。

`counterparty_account` MUST 保存来源直接提供的对方账号、卡号、掩码账号或账户标识；来源未提供或无法可靠识别时 MUST 为空字符串。它不得保存本账户的账号、映射账户名或推断出的对侧账户。该字段与 `source_payload` 中的对应原始值同时存在时，前者用于受控查询，后者用于审计。

历史事实在本变更前已经丢失原始列时，系统 MUST 保留既有来源快照和事实，不得伪造完整来源行；迁移只能从已有可确定值回填 `counterparty_account`。

#### Scenario: 支付宝完整来源行与对方账号
- **WHEN** 用户导入一行同时包含 `对方账号`、备注和空值列的支付宝账单
- **THEN** 新建现金流水的 `source_payload` MUST 与该行全部原始表头和值完全一致，`counterparty_account` MUST 等于原始 `对方账号`，且快照中不得出现 `account_name`、`record_type`、`source_type` 或映射结果

#### Scenario: 微信提现到账卡
- **WHEN** 用户导入一行交易类型为 `零钱提现`、支付方式直接表示到账卡的微信账单
- **THEN** 现金流水 MUST 将该到账卡保存为 `counterparty_account`，同时来源行快照 MUST 保留原始 `支付方式` 值而非被路由后的支付方式

#### Scenario: 缺少对方账号的来源行
- **WHEN** 用户导入不提供对方账号的账单行
- **THEN** 系统 MUST 保存完整来源行快照并将 `counterparty_account` 保存为空字符串，不得以本账户、账户映射或文本猜测填充该列

#### Scenario: 无标题来源列
- **WHEN** 用户导入一行包含唯一无标题来源列且该列有原始值的账单
- **THEN** 系统 MUST 使用 `""` 作为该列在 `source_payload` 中的键并保留原始值，不得删除、重命名或以派生字段替代该列

#### Scenario: 双后端等价
- **WHEN** 同一来源账单分别导入 SQLite 和 PostgreSQL 工作区
- **THEN** 两个后端的来源行快照、`counterparty_account`、幂等结果和正式金额 MUST 等价，允许代理键和时间戳字面差异
### Requirement: 以 record_id × source_type 行级幂等，无文件作业表
系统 MUST 作为用户，重复/重叠导入时仅按 **`record_id` × `source_type`**（再加 workspace；活跃、未删）决定是否新建正式事实： - **`record_id`**：平台流水号、展示号或确定性业务行键； - **`source_type`**：**导入渠道名**（账单/券商解析渠道，如 `alipay`、`wechat`、对应券商 kind），不是支付方式文案、也不是文件路径。 同一 `record_id` 在**不同** `source_type` 下是不同标识（可并存两条活跃事实）；同一 `(source_type, record_id)` 不得双记。不因文件 digest/批次跳过 novel 行；库中无导入批次/文件清单。 **优先级理由**：010 产品价值 + 正确标识模型（渠道内流水号才稳定）。

#### Scenario: 验收场景 1
- GIVEN 迁移前规格所描述的有效业务上下文。
- WHEN 执行以下验收条件：执行该用户故事的独立测试，结果符合迁移前的验收口径。
- THEN 系统满足该条件，并保留可复核的验证证据。
### Requirement: 关系匹配读事实行快照
系统 MUST 作为对账用户，从事实 `source_payload`（或已提升列）读取 hard-key 与日期几何所需的原始字段；种子仅为 `seed_fact_ids`。关系匹配可以读取 `counterparty_account` 作为附加证据，但不得修改来源行快照，也不得依赖曾被禁止写入快照的派生字段。当且仅当用户显式登记当前工作区的本人账户标识时，转账、提现和还款关系可以将 `counterparty_account` 与该标识比较以筛选或排除既有合格候选；比较结论只能以命中种类进入关系证据，不得把账号原文复制到关系、日志或输出中。

#### Scenario: 完整快照支持关系检查
- **WHEN** 已导入的来源行包含关系检查所需的原始支付方式、日期或账号信息
- **THEN** 关系检查 MUST 从完整来源行快照或 `counterparty_account` 获得该信息，且不得重新解析来源文件或依赖独立 raw 表

#### Scenario: 历史快照保持可审计
- **WHEN** 升级前事实缺少原始列，无法重建完整来源行
- **THEN** 迁移 MUST 保留该事实及其既有来源快照，关系检查不得把迁移生成的值描述为原始来源字段

#### Scenario: 对方账号只匹配显式本人标识
- **WHEN** 转账关系读取一条带有 `counterparty_account` 的事实，但当前工作区没有与候选账户绑定的本人账户标识
- **THEN** 系统 MUST 不从账户名称、来源映射、备注或其他事实猜测目标账户，并保持既有候选行为
### Requirement: 一次切换，无垫片
系统 MUST 升级后只认目标模型；删除旧表/旧列；无 dual-write。 **验收场景**： 1. 自 raw 回填 `source_type`/`record_id`/`source_payload` 后删 raw 链。 2. 不存在 `import_batches`/`raw_files`/`raw_records`。 3. 估值等处无 `raw_record_id` 依赖。 ---。

#### Scenario: 验收场景 1
- GIVEN 迁移前规格所描述的有效业务上下文。
- WHEN 执行以下验收条件：执行该用户故事的独立测试，结果符合迁移前的验收口径。
- THEN 系统满足该条件，并保留可复核的验证证据。
### Requirement: 删除现金 legacy 非权威列
系统 MUST 删除 `cash_transactions` 上 006 后非权威的 offset/proposed 列，以及无读者/非权威的 **`locked`、`transfer_account`**，以及与 `source_type`+payload 重复的 **`source`、`bill_source`**。 **删除列清单（现金）**： | 列 | 理由 | |---|---| | `offset_group` | 非权威 | | `offset_role` | 非权威 | | `offset_strength` | 非权威 | | `offset_source` | 非权威 | | `offset_rule_hint` | 非权威 | | `offset_match_type` | 非权威 | | `proposed_action` | 非权威 | | `locked` | 几乎无读路径，假布尔遗留 | | `transfer_account` | 非关系权威；对端展示用 note/关系 | | `source` | 与渠道/payload 重复 | | `bill_source` | 渠道归 `source_type` | **保留**：`record_id`、`category`、`note`、`counterparty`、金额时间账户、软删行字段、`ledger_snapshots` 表等（见非目标/保留清单）。 **验收场景**： 1. 双后端 schema 无上表删除列。 2. 导入/convert/手工写入不要求、不落库这些列。 3. 关系净消费/排除仍只靠活跃事实 + `transaction_relations` + 正式 `category`。 4. 财富 transfer 分类不读已删列；以 `category` ∈ {`transfer`,`transfer_in`,`transfer_out`} 和/或 accepted `transfer_pair` 为准；同输入用户可见结果一致。 5. 无 dual-write/空列 shim。 ---。

#### Scenario: 验收场景 1
- GIVEN 迁移前规格所描述的有效业务上下文。
- WHEN 执行以下验收条件：执行该用户故事的独立测试，结果符合迁移前的验收口径。
- THEN 系统满足该条件，并保留可复核的验证证据。
### Requirement: 去掉作业壳与过度设计表
系统 MUST 作为维护者，schema 不再包含： | 删除表 | 理由 | |---|---| | `import_batches` | 导入作业壳 | | `raw_files` | 文件档案 | | `raw_records` | 已内联 | | `fact_deletion_events` | 与行上 `deleted_*` 双写 | | `record_revisions` | 尚无改账版本追溯需求 | | `relation_check_runs` | 关系检查作业壳；SoT 是 `transaction_relations` | **保留表（本 story 明确）**：`ledger_snapshots`（派生缓存，**必须保留**）；wealth 读模型族不动。 **验收场景**： 1. 升级后上述删除表不存在。 2. `ft fact-delete`（或等价）仍可逻辑删除现金事实，审计字段写在**事实行**上；无强制写入 `fact_deletion_events`。 3. 关系检查可运行并产生/更新 `transaction_relations`，**不**依赖 `relation_check_runs` 持久化。 4. 查询路径仍可 `load`/`save` **`ledger_snapshots`**；行为不因本 feature 删除该缓存。 5. 不存在写入 `record_revisions` 的运行时路径；无「改账前后 JSON」持久化要求。 ---。

#### Scenario: 验收场景 1
- GIVEN 迁移前规格所描述的有效业务上下文。
- WHEN 执行以下验收条件：执行该用户故事的独立测试，结果符合迁移前的验收口径。
- THEN 系统满足该条件，并保留可复核的验证证据。
### Requirement: 投资事件不持久化可推导的 price
系统 MUST 作为维护者，`investment_events` **不再**持久化 `price` 列。成交单价可由 **leg 数量与金额**（及既有 `commission` 规则，若适用）推导，不应与 legs 双写。 **优先级理由**：减少冗余列与双源不一致。相对 014「price 升列」的窄化。

#### Scenario: 验收场景 1
- GIVEN 迁移前规格所描述的有效业务上下文。
- WHEN 执行以下验收条件：执行该用户故事的独立测试，结果符合迁移前的验收口径。
- THEN 系统满足该条件，并保留可复核的验证证据。
### Requirement: 一次性升级 ~/.ft 下的本机 SQLite 账本
系统 MUST 作为本机账本所有者/开发者，在 015 代码与 schema 迁移就绪后，**一次性**把 **`~/.ft` 下正在使用的 SQLite 数据库文件**（默认/当前：`~/.ft/finance-tracker.db`）升级到本 feature 目标结构，使日常 CLI 在指向该库时无需手工拼装迁移步骤即可继续记账。 **优先级理由**：真实数据在 `~/.ft`（约含大量现金/投资事实与 raw 链）；仅绿测试库不等于可用。

#### Scenario: 验收场景 1
- GIVEN 迁移前规格所描述的有效业务上下文。
- WHEN 执行以下验收条件：执行该用户故事的独立测试，结果符合迁移前的验收口径。
- THEN 系统满足该条件，并保留可复核的验证证据。
### Requirement: 功能需求基线
系统 MUST 保持以下迁移前功能需求；后续行为变更必须通过 OpenSpec change 明确修改。

- - **FR-001**：系统 MUST 仅以工作区内 **`record_id` × `source_type`** 判断账单行是否创建新的活跃正式事实（现金；投资等价）。复合标识定义为：
- - **FR-002**：系统 MUST NOT 因文件 digest、路径或批次状态跳过 novel 行 formalize；MUST NOT 持久化文件路径/digest/导入批次。
- - **FR-003**：账单派生正式事实 MUST 同行保存：`source_type`（导入渠道名）、`record_id`、`source_payload`（JSON 快照）。手工事实三者可空。
- - **FR-004**：系统 MUST 移除 `import_batches`、`raw_files`、`raw_records` 及一切 `raw_record_id` 列/FK。
- - **FR-005**：现金：当 `record_id` 与 `source_type` 均非空且 `deleted_at` 为空时，`(workspace_id, source_type, record_id)` 至多一条活跃事实。删后再导同一复合标识允许新活跃行。不同 `source_type` 的相同 `record_id` 字面值 MUST 视为不同标识。
- - **FR-006**：投资：对非空 `(source_type, record_id)` 提供等价不双记保证（无软删则全量唯一；有软删则 partial 对齐现金）。
- - **FR-007**：导入编排：解析 → 得 **`source_type`（导入渠道名）与 `record_id`** → 该复合标识已存在活跃则跳过 → 否则写入事实并更新投影；失败原子回滚。
- - **FR-008**：导入后关系检查 MUST 仅 `seed_fact_ids`；MUST NOT 依赖 batch id 或 `relation_check_runs`。
- - **FR-009**：关系匹配 MUST 能从 `source_payload`（或已提升列）获得原 raw payload 所需字段，能力不回退。
- - **FR-010**：现金与投资导入入口一致遵守 FR-001～FR-009。
- - **FR-011**：PostgreSQL 与 SQLite 用户可见等价；允许代理键/时间戳字面差异。
- - **FR-012**：迁移一次性回填 source 字段并删除旧表/列；无长期 dual-write。
- - **FR-013**：估值等实体清除 `raw_record_id` 依赖，不阻断 wealth 主路径。
- - **FR-014**：提供本目录目标 `database-schema.md`。
- - **FR-015**：MUST 物理删除现金列：`offset_group`、`offset_role`、`offset_strength`、`offset_source`、`offset_rule_hint`、`offset_match_type`、`proposed_action`、`locked`、`transfer_account`、`source`、`bill_source`。
- - **FR-016**：MUST NOT 向正式事实写入 FR-015 字段；MUST NOT 以空串保留列。
- - **FR-017**：关系与净额 MUST 仅依赖活跃事实 + `transaction_relations` + 正式 `category` 等；不得把 FR-015 键写入 `source_payload` 充当关系权威。
- - **FR-018**：财富/事件分类 MUST NOT 读 FR-015 字段；transfer 判定基于 `category`（`transfer`/`transfer_in`/`transfer_out`）和/或 accepted `transfer_pair`；同正式输入下用户可见结果与删列意图一致（不静默改账）。
- - **FR-019**：MUST 保留正式列 **`record_id`** 与 **`source_type`** 作为幂等复合键：`record_id` × `source_type`（导入渠道名）。`source_type` 承接原导入/bill 渠道语义（稳定渠道名），**不是**支付方式文案列；不得保留并行的 `bill_source`/`source` 正式列名。
- - **FR-020**：MUST 删除表 `fact_deletion_events`。逻辑删除审计 MUST 仅使用事实行 `deleted_at`、`deleted_by`、`delete_reason`（现金）。
- - **FR-021**：MUST 删除表 `record_revisions`。系统 MUST NOT 持久化「改账前后快照」修订链。若事实行上 `revision` 仅服务未实现的改账版本语义，MUST 删除该列；wealth 源水印 MUST 改为不依赖递增 revision（例如事实 id + 内容摘要，plan 定）。
- - **FR-022**：MUST 删除表 `relation_check_runs`；关系检查运行时状态可不持久化或仅日志/CLI 输出。
- - **FR-023**：MUST **保留**表 `ledger_snapshots` 及其 load/save 缓存职责（派生、非 SoT，但是产品需要的缓存）。
- - **FR-024**：本 feature MUST NOT 删除或重做 wealth 读模型表族（`wealth_*`、`valuation_observations` 结构以不动为默认，仅去掉 `raw_record_id` 依赖）。
- - **FR-025**：系统 MUST 从 `investment_events` **物理删除** `price` 列（schema、模型、仓库读写、正式/导出契约、目标 schema 文档同步）。
- - **FR-026**：系统 MUST NOT 向正式投资事实写入 `price`；MUST NOT 以空值/零值默认保留该列；MUST NOT 将 `price` 作为 residual `payload` 的核心键长期存放。
- - **FR-027**：投资投影、成本与持仓计算 MUST 以 **`from_ticker`/`from_amount`/`to_ticker`/`to_amount`**（及 `commission`/`commission_asset` 等仍保留的正式列）为权威输入；需要单价展示或中间量时 MUST **派生**，不得依赖已删列。在相同 leg 输入下，用户可见持仓/成本结果 MUST 与删列意图一致（不静默改账）。
- - **FR-028**：本 feature 交付 MUST 包含对开发者/用户本机 **`~/.ft` 目录下 SQLite 账本库**的**一次性升级**任务与可执行步骤。默认目标文件为 **`~/.ft/finance-tracker.db`**；若用户日常 `FT_DATABASE_URL` 指向 `~/.ft` 下其它 `.db` 文件，升级步骤 MUST 允许显式指定该路径。升级内容即本 spec 的 schema/数据迁移（FR-012 及列/表删除），不是第二套脚本语义。
- - **FR-029**：执行 `~/.ft` 库升级前 MUST 创建可恢复备份；升级失败 MUST 失败关闭且不得在无备份时留下不可恢复的唯一损坏副本。
- - **FR-030**：`~/.ft` 库升级成功后，使用该文件的 CLI 主路径（账户/事实列表、导入幂等、投影抽样）MUST 可用；活跃事实计数与升级前相比 MUST 不因迁移丢失正式事实行（非权威列值可丢弃；`price` 可按 FR-025 丢弃列而保留 legs）。
- - **FR-031**：操作文档（本 feature `quickstart.md` 或等价交付说明）MUST 写明备份、升级、验证、从备份回滚的准确命令；MUST NOT 假定调用方记得未写入文档的对话步骤。

#### Scenario: 验收场景 1
- GIVEN 迁移前规格所描述的有效业务上下文。
- WHEN 执行以下验收条件：对该能力进行修改时，验证结果 MUST 覆盖迁移后的功能需求清单。
- THEN 系统满足该条件，并保留可复核的验证证据。

## Source
完整迁移来源与原始验证证据：[015-inline-row-provenance/spec.md](../../changes/archive/2026-08-01-015-inline-row-provenance/legacy/015-inline-row-provenance/spec.md)。
本文件是 OpenSpec 的行为导向投影；实现细节、研究记录和历史任务保留在对应 change 的 `legacy/` 目录。
