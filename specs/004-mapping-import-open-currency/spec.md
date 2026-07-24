# Feature Specification: Mapping Import & Open Currency

**Feature Branch**: `codex/mapping-import-open-currency`

**Created**: 2026-07-20

**Status**: Complete

**Input**: User description: "恢复 master 兼容的账单导入：按 ~/.ft/mapping.yaml 从账单内支付方式/卡号推断每行账户；ft import 不允许 --account。同时移除 CLI/领域层币种白名单，支持任意币种（含 JPY）。"

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 一键导入多支付方式账单 (Priority: P1)

作为记账用户，我导出一份支付宝或微信账单（同一文件内含多种收/付款方式），运行一次 `ft import FILE --source alipay|wechat`（**不得**传账户参数），系统按 `~/.ft/mapping.yaml` 把每条记录路由到正确账户并写入正式事实。

**Why this priority**: 真实平台账单天然多账户；强制或允许 CLI 指定账户都会导致错账或绕过账单事实。

**Independent Test**: 含“账户余额 / 信用卡 / 花呗”等支付方式的支付宝样例 + mapping；导入后各账户事实条数与 mapping 一致；PostgreSQL 与 SQLite 可复现。

**Acceptance Scenarios**:

1. **Given** workspace 中已存在 mapping 目标账户且规则覆盖样例支付方式，**When** 执行 `ft import alipay.csv --source alipay`，**Then** 每条解析记录按支付方式落入对应账户，结果可报告各账户写入条数，整批同事务提交。
2. **Given** 同一文件 content digest 已成功导入，**When** 再次 `ft import`，**Then** 不重复发布正式事实，返回 already imported / 幂等成功。
3. **Given** 某支付方式未匹配且 default 为 error/fail，**When** 导入，**Then** 整批回滚，错误含 source、payment_method 与补充 mapping 的提示，库中无部分写入。

---

### User Story 2 - 银行账单也从账单内推断账户 (Priority: P1)

作为记账用户，我导入工行/建行等银行账单时同样不传账户；系统根据账单内卡号、bill_type 与 mapping 规则（如 `icbc_debit` / `ccb_debit_2820` / `*`）路由到正确账户；多币种行按行币种匹配 `(account_name, currency)`。

**Why this priority**: 与平台账单统一“只从账单推断”，避免 import 再暴露 `--account`。

**Independent Test**: 建行 XLS 含卡号 2820/0523、工行借记/信用卡 PDF 含多币种；仅靠 mapping 导入后账户归属正确。

**Acceptance Scenarios**:

1. **Given** mapping 含 `ccb_debit_2820` / `ccb_debit_0523`（或等价卡号规则），**When** 导入建行明细，**Then** 行按卡号进入对应建行账户。
2. **Given** mapping 含 `icbc_debit` / `icbc_credit` 等规则且 JPY/USD 账户已存在，**When** 导入含外币行的工行账单，**Then** 各币种行进入同名账户的对应币种分册。
3. **Given** 用户在 CLI 传入 `--account`，**When** 执行 `ft import`，**Then** 参数被拒绝（未知参数或明确错误），不导入。

---

### User Story 3 - 任意币种账户与事实 (Priority: P1)

作为多币种用户，我可以创建 JPY 等非 CNY/USD/HKD 账户，并将账单中的外币行导入对应币种账户。

**Why this priority**: 白名单阻断真实外币账户与导入。

**Independent Test**: `ft acct add ... --currency JPY` 成功；含 JPY 的账单经 mapping 导入后 JPY 账户有事实；双后端一致。

**Acceptance Scenarios**:

1. **Given** 3 位字母币种码，**When** 创建账户或写入事实，**Then** 接受并归一为大写，不再限制 CNY/USD/HKD。
2. **Given** 行币种 JPY 且 mapping 目标账户的 JPY 分册存在，**When** 导入，**Then** 事实与投影以 JPY 更新。
3. **Given** 非法币种，**When** 创建或导入，**Then** 校验失败不写入。
4. **Given** 无预置符号的币种，**When** 展示，**Then** 使用币种代码，不崩溃。

---

### User Story 4 - convert 与 import 账户解析一致 (Priority: P2)

作为用户，`ft convert` 预览时的账户路由与 `ft import` 相同：一律从账单字段 + mapping 推断，**不**用 CLI 账户参数覆盖。

**Why this priority**: 预览与正式导入必须一致。

**Independent Test**: 同文件 convert 与 import 的 account_name 分布一致。

**Acceptance Scenarios**:

1. **Given** 同一 mapping 与文件，**When** convert 与 import，**Then** 账户归属一致。
2. **Given** convert/import CLI，**When** 查看 help，**Then** import 不提供 `--account`；convert 若保留 `--account` 则本 feature 规定其不得影响正式 import 合同——**推荐 convert 也不提供账户覆盖**，与 import 完全一致。

---

### Edge Cases

- mapping 缺失：可创建默认模板或失败提示路径；不得静默猜账户。
- `default: skip`：跳过未匹配行并计数；全跳过则失败。
- `default: error`：任一未匹配整批失败。
- digest 幂等：重复导入不增 facts。
- 行币种与现金/贷款账户币种不一致：失败回滚。
- 目标账户不存在：失败回滚。
- 证券账单（dfzq）：也通过 mapping（如 `source: dfzq, match: "*"`）推断账户，不设 import `--account`。
- PDF 密码仅 `--password-file`。
- 双后端业务结果等价；禁止回退/双写。

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: `ft import` MUST NOT 接受 `--account`（或任何整文件账户覆盖参数）。所有行的目标账户 MUST 仅从账单解析字段（支付方式、卡号、bill_type 等）结合 `~/.ft/mapping.yaml` 推断。
- **FR-002**: `ft import` MUST 在每次导入时加载 mapping；按 master 等价的 source + fnmatch（更长优先）与卡号复合 source 规则路由每一行。
- **FR-003**: 未匹配时 MUST 遵守 `default`：`error`/`fail` 整批失败；`skip` 跳过并计数；禁止写入“未知”账户。
- **FR-004**: 单次原始文件导入 MUST 单事务完成 multi-account raw/facts/revision/projection/batch completed；任一行失败全回滚。
- **FR-005**: workspace + source_kind + content digest 幂等；多账户批次不得被单目标账户模型拒绝。
- **FR-006**: Import batch MUST 支持一次导入多账户；`target_account_id` 不再作为强制单账户约束（可空）；事实账户以 fact.account_id 为准。
- **FR-007**: `ft convert` 的账户解析 MUST 与 import 同一套账单内推断 + mapping 规则；不得依赖 CLI 账户覆盖作为正式路径。
- **FR-008**: 移除币种白名单 `{CNY,USD,HKD}`；币种为 3 位字母大写；符号表仅展示。
- **FR-009**: 已解析的 JPY 等币种 MUST 可建账户并导入。
- **FR-010**: 文档与 CLI help MUST 说明：import 无账户参数、mapping 路径、开放币种、幂等。
- **FR-011**: PostgreSQL 与 SQLite 等价测试矩阵。
- **FR-012**: mapping.yaml 仅路由配置，不是运行时账本。

### Key Entities

- **PaymentMappingRule** / **MappingConfig**
- **ImportBatch**（可空 target_account_id，digest 幂等）
- **RawRecord / Formal Fact**（每条单一 account_id）
- **Account**（开放 currency）

### Non-Goals

- import 整文件账户覆盖
- 跨源自动去重 UI
- mapping 入库
- Connector sync 恢复

## Success Criteria *(mandatory)*

- **SC-001**: 无账户参数即可正确导入多支付方式支付宝/微信账单（对照 mapping ≥99% 已映射行）。
- **SC-002**: 重复导入事实总数不变。
- **SC-003**: JPY 建户与导入测试通过。
- **SC-004**: 未映射支付方式失败关闭且无部分写入。
- **SC-005**: SQLite 与 PostgreSQL 结论一致。
- **SC-006**: convert 与 import 账户归属一致。
- **SC-007**: `ft import ... --account X` 被拒绝，不产生导入副作用。

## Assumptions

- mapping 在 `~/.ft/mapping.yaml`，语义兼容 master。
- 账户须预先存在。
- 银行/证券账单通过 mapping 的 bill_type 与卡号规则覆盖，不再需要 CLI 账户。
- 本 feature 修订 001「强制显式账户」与「禁止 mapping」；以本 spec 为准。

## Dual-Database Behavior

| 行为 | PostgreSQL | SQLite | 等价 |
|---|---|---|---|
| mapping 多账户 import | 单事务 | 同左 | 条数/归属/金额 |
| digest 幂等 | unique | 同左 | 不重复事实 |
| 开放币种 | String(3) | 同左 | 接受 JPY |
| 回退/双写 | 禁止 | 禁止 | 禁止 |
