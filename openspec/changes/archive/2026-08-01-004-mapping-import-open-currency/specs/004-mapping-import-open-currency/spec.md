## Purpose
User description: "恢复 master 兼容的账单导入：按 ~/.ft/mapping.yaml 从账单内支付方式/卡号推断每行账户；ft import 不允许 --account。同时移除 CLI/领域层币种白名单，支持任意币种（含 JPY）。 本能力的行为契约由迁移后的需求与场景持续维护。

## ADDED Requirements

### Requirement: 一键导入多支付方式账单
系统 MUST 作为记账用户，我导出一份支付宝或微信账单（同一文件内含多种收/付款方式），运行一次 `ft import FILE --source alipay|wechat`（**不得**传账户参数），系统按 `~/.ft/mapping.yaml` 把每条记录路由到正确账户并写入正式事实。

#### Scenario: 验收场景 1
- GIVEN 迁移前规格所描述的有效业务上下文。
- WHEN 执行以下验收条件：执行该用户故事的独立测试，结果符合迁移前的验收口径。
- THEN 系统满足该条件，并保留可复核的验证证据。
### Requirement: 银行账单也从账单内推断账户
系统 MUST 作为记账用户，我导入工行/建行等银行账单时同样不传账户；系统根据账单内卡号、bill_type 与 mapping 规则（如 `icbc_debit` / `ccb_debit_2820` / `*`）路由到正确账户；多币种行按行币种匹配 `(account_name, currency)`。

#### Scenario: 验收场景 1
- GIVEN 迁移前规格所描述的有效业务上下文。
- WHEN 执行以下验收条件：执行该用户故事的独立测试，结果符合迁移前的验收口径。
- THEN 系统满足该条件，并保留可复核的验证证据。
### Requirement: 任意币种账户与事实
系统 MUST 作为多币种用户，我可以创建 JPY 等非 CNY/USD/HKD 账户，并将账单中的外币行导入对应币种账户。

#### Scenario: 验收场景 1
- GIVEN 迁移前规格所描述的有效业务上下文。
- WHEN 执行以下验收条件：执行该用户故事的独立测试，结果符合迁移前的验收口径。
- THEN 系统满足该条件，并保留可复核的验证证据。
### Requirement: convert 与 import 账户解析一致
系统 MUST 作为用户，`ft convert` 预览时的账户路由与 `ft import` 相同：一律从账单字段 + mapping 推断，**不**用 CLI 账户参数覆盖。

#### Scenario: 验收场景 1
- GIVEN 迁移前规格所描述的有效业务上下文。
- WHEN 执行以下验收条件：执行该用户故事的独立测试，结果符合迁移前的验收口径。
- THEN 系统满足该条件，并保留可复核的验证证据。
### Requirement: 功能需求基线
系统 MUST 保持以下迁移前功能需求；后续行为变更必须通过 OpenSpec change 明确修改。

- - **FR-001**: `ft import` MUST NOT 接受 `--account`（或任何整文件账户覆盖参数）。所有行的目标账户 MUST 仅从账单解析字段（支付方式、卡号、bill_type 等）结合 `~/.ft/mapping.yaml` 推断。
- - **FR-002**: `ft import` MUST 在每次导入时加载 mapping；按 master 等价的 source + fnmatch（更长优先）与卡号复合 source 规则路由每一行。
- - **FR-003**: 未匹配时 MUST 遵守 `default`：`error`/`fail` 整批失败；`skip` 跳过并计数；禁止写入“未知”账户。
- - **FR-004**: 单次原始文件导入 MUST 单事务完成 multi-account raw/facts/revision/projection/batch completed；任一行失败全回滚。
- - **FR-005**: workspace + source_kind + content digest 幂等；多账户批次不得被单目标账户模型拒绝。
- - **FR-006**: Import batch MUST 支持一次导入多账户；`target_account_id` 不再作为强制单账户约束（可空）；事实账户以 fact.account_id 为准。
- - **FR-007**: `ft convert` 的账户解析 MUST 与 import 同一套账单内推断 + mapping 规则；不得依赖 CLI 账户覆盖作为正式路径。
- - **FR-008**: 移除币种白名单 `{CNY,USD,HKD}`；币种为 3 位字母大写；符号表仅展示。
- - **FR-009**: 已解析的 JPY 等币种 MUST 可建账户并导入。
- - **FR-010**: 文档与 CLI help MUST 说明：import 无账户参数、mapping 路径、开放币种、幂等。
- - **FR-011**: PostgreSQL 与 SQLite 等价测试矩阵。
- - **FR-012**: mapping.yaml 仅路由配置，不是运行时账本。

#### Scenario: 验收场景 1
- GIVEN 迁移前规格所描述的有效业务上下文。
- WHEN 执行以下验收条件：对该能力进行修改时，验证结果 MUST 覆盖迁移后的功能需求清单。
- THEN 系统满足该条件，并保留可复核的验证证据。
