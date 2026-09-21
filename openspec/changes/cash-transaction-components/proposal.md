## Why

当前支付宝账单的 `收/付款方式` 可能包含多个真实资金账户，但导入模型只有一条带单一账户的现金流水。含 `&` 的组合支付因此只能失败关闭，无法同时表达支付宝零钱、银行卡或其他现金账户的实际金额分配；关系匹配也无法把平台的一部分支付与银行流水关联。该变更在开发期直接重建数据库，建立统一的父流水与支付组成项模型。

## What Changes

- **BREAKING** 新增 `cash_transaction_components`，每条现金流水至少有一个支付组成项；普通流水使用一个组成项，组合支付使用多个组成项。
- **BREAKING** `cash_transactions` 新增 `cash_granularity`，并允许 `aggregate` 父流水没有单一 `account_id`；父金额必须与组成项金额精确守恒。
- 支付组成项成为账户余额、余额快照和账户维度统计的金额来源；父流水只保留业务总额、来源、分类和展示信息。
- 支持支付宝已验证的 `&` 支付方式解析；无法取得真实账户分摊金额时，导入预览必须阻塞并要求补齐，禁止猜测或部分写入。
- `payment_mirror`、`refund_offset`、`transfer_pair` 的现金端点改为支付组成项，并以 `applied_amount` 支持部分退款和一对多关联。
- 现金—投资资金调拨改为支付组成项到投资事件的关联；aggregate 现金父流水不得直接作为资金端点。
- 收支投影仍按父流水展示一笔业务，但构建、账户筛选和关系证据读取组成项；不再把父金额作为第二份余额分录。
- 红包、优惠、立减等不生成账户组成项，继续仅保留在 `source_payload`。
- 不提供历史数据回填、迁移或兼容旧模型；测试数据库和运行数据库按新 schema 直接重建。

## Capabilities

### New Capabilities

- `cash-transaction-components`: 现金流水父记录、支付组成项、账户余额分录和守恒规则。

### Modified Capabilities

- `ledger-records`: 现金流水的父记录、组成项和精确金额语义。
- `statement-import`: 组合支付解析、分摊补齐、幂等写入和来源快照。
- `transaction-relations`: 现金关系改用组成项端点及部分金额约束。
- `cash-investment-funding-relations`: 资金调拨改用组成项端点。
- `cash-ledger-browser`: 父流水展示与组成项账户筛选、详情证据。
- `wealth-attribution`: 现金来源和失效触发包含组成项。

## Impact

- 数据模型、SQLite/PostgreSQL schema 创建、关系仓储和所有现金导入写入路径。
- 支付平台解析与导入预览 API、手工流水新增/编辑 API 及 Web/Native 组成项编辑交互。
- 关系扫描、关系审查、收支投影、余额快照、财富现金流读取和来源 revision 触发器。
- 现有旧数据库不可直接升级；开发环境须删除并重建数据库。不会读取或修改历史真实账单数据。
