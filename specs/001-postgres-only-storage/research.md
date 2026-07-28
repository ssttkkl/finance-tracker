# Research: PostgreSQL-Only Runtime Storage

## Decision 1: 一次性替换，不做过渡架构

**Decision**: 删除 local/postgres 选择、旧账本迁移、shadow comparison、双写和 runtime rollback。

**Rationale**: 产品未上线，现有数据可丢弃。保留任何切换面都会继续要求双实现、双测试和双文档，
与用户“不留历史包袱”的目标直接冲突。

**Rejected alternatives**:

- PostgreSQL 默认但保留 local：仍会被 CLI/测试误用。
- 先导入旧 `~/.ft` 再切换：为无价值开发数据增加迁移代码。
- feature flag/cutover：制造长期不可验证分支。

## Decision 2: 运行时启动必须失败关闭

**Decision**: 普通入口启动时验证数据库连接、必需 schema/Alembic head 和 workspace；不自动建 schema、
不自动建 workspace，也不回退本地。

**Rationale**: 当前 query repository 对未知 workspace 返回空数据，会把配置错误伪装成“没有数据”。
显式失败能阻止错误 workspace 和 silent data split。

## Decision 3: 重建单一 schema baseline

**Decision**: 删除现有 Phase 2 两段开发 migration，创建一个 initial baseline。

**Rationale**: 没有生产数据库需要升级；当前是修正 account-name 引用、字符串时间和 lineage 缺口的
最佳窗口。保留过渡 revision 只会把未发布历史永久化。

## Decision 4: 稳定 account ID 与来源关系

**Decision**: `cash_transactions`、`investment_events` 使用 `account_id` FK，并直接持有可空 `raw_record_id` FK；
manual facts 没有 raw record，statement-derived facts 必须有。所有关联由组合 workspace 约束保证同 workspace。

**Rationale**: 账户名可重命名，不能充当事实引用。只有 explicit link 才能回答“某条正式事实来自哪个
文件的哪条原始记录”。

账户投影同样以 account ID 为 key；有事实引用的账户由数据库 `RESTRICT` 删除，产品引导用户停用。

## Decision 5: DB snapshot 是投影，不是事实源

**Decision**: 可保留 PostgreSQL 内的 portfolio/balance projection 以复用现有查询，但 authoritative facts
是账户、现金交易、投资事件和 revisions；投影可从数据库事件重建，禁止从 CSV/YAML replay。

**Rationale**: 删除文件 snapshot 不代表必须在本 feature 重写全部查询；明确投影语义即可避免第二事实源。

## Decision 6: 原始输入直接导入，不保留 converted CSV staging

**Decision**: 统一 import use case 直接接收当前已支持的原始 statement provider/format；内容摘要、raw rows、
formal facts、lineage、revision、projection 和 batch completion 在同一事务。`convert --output` 仅保留为用户
显式导出工具，不再是正式导入必经步骤。

**Rationale**: `convert -> CSV -> append` 会丢失原始文件标识，并让中间 CSV 重新成为事实源。

**Supported matrix at feature start**: 支付宝、微信、工行信用卡、工行借记卡、建行借记卡和东方证券；
具体扩展名为现有 parser 已覆盖的 CSV、XLS/XLSX 与 PDF。本 feature 不新增 JSON/YAML statement provider。

## Decision 7: 文件型 reconcile 暂时删除

**Decision**: 删除 `pending/ai_working.csv` 状态机、相关 CLI 入口与 local repository；纯匹配算法只有在不含
文件持久化时才可保留。数据库关系审查列表和 reconciliation 另建 feature。

**Rationale**: 旧 reconcile 把 staged CSV、人工决策文件和 Git 提交当状态；直接保留会违反唯一事实源。
本 feature 又明确不引入关系审查列表能力。

## Decision 8: 测试分层

**Decision**: application 纯规则使用 fakes；repository 快速契约可使用 SQLite，但 Alembic/schema/类型、
事务和 workspace 约束必须由 gated live PostgreSQL 测试验证。

**Rationale**: `Base.metadata.create_all()` + SQLite 会掩盖 Alembic 漂移和 PostgreSQL 类型/约束差异。

## Decision 9: Connector 延后，不在清理 feature 中重建

**Decision**: 删除当前依赖 `credentials.yaml`、`mapping.yaml` 和 security CSV 的 Connector sync 产品入口。

**Rationale**: Connector 不是 PostgreSQL-only 核心闭环的必要条件。把 secret provider、provider mapping、
数据库幂等事件和第三方失败策略同时塞入本 feature 会扩大范围；产品未上线且无需兼容，可以先删除，
后续用独立 Spec Kit feature 重新加入。

## Current-state evidence

- `src/ft/cli.py` 普通命令仍硬编码 `build_local_services`/`LocalCsvUnitOfWork`。
- PostgreSQL bundle 当前只提供 queries、portfolio、accounts、cashflow、transfers 和 UoW。
- `PostgresMigrationTarget.export()` 仍可重建 accounts YAML、snapshot YAML 和 records CSV。
- 43 个 test 文件中约 32 个包含 local/`.ft`/ledger path 耦合。
- 现有 PostgreSQL account/cashflow/UoW/config/provenance 快速合同：17 tests passed；这不覆盖 CLI、
  投资写入、统一导入、connector 或启动验证。
