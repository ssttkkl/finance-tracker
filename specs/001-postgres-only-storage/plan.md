# Implementation Plan: PostgreSQL-Only Runtime Storage

**Branch**: `refactor/web` | **Date**: 2026-07-17 | **Spec**: [spec.md](./spec.md)

**Input**: `specs/001-postgres-only-storage/spec.md`

## Summary

一次性把 PostgreSQL 设为 CLI、未来 Web/Worker/MCP 的唯一运行时事实源，删除 backend 选择、
CSV/YAML/Git 账本、local repository、旧账本迁移、shadow comparison、Git change-set 与文件回退。
现有开发数据不读取、不迁移、不自动删除。账户、现金、投资和原始账单导入改为 workspace 绑定的
数据库事务；CSV/XLSX/PDF 等仅保留为用户提供的输入或显式导出文件。

## Technical Context

**Language/Version**: Python 3.11+

**Primary Dependencies**: SQLAlchemy 2.x, psycopg 3.x, Alembic 1.16+, argparse；现有
`pdfplumber`、`openpyxl`、`xlrd` 仅用于原始输入解析

**Storage**: PostgreSQL 唯一运行时存储；单一 Alembic initial baseline

**Testing**: pytest；SQLite 仅作 repository 快速契约测试，`FT_TEST_POSTGRES_URL` 驱动真实
PostgreSQL 集成测试

**Target Platform**: 本地 CLI 与后续 Linux Web/Worker/MCP 进程

**Project Type**: 单 Python 包，多入口共享 application/domain 层

**Performance Goals**: 单个 statement import 在一个事务中完成；常用 workspace 查询使用索引，
不引入逐行提交；本 feature 不设吞吐扩容目标

**Constraints**: 金额使用 `NUMERIC(38,18)`/`Decimal`；所有 repository 固定 workspace；启动时
数据库、schema、workspace 任一缺失都失败关闭；持久化与中间计算不舍入、不经过 float、scale 超限拒绝；
naive 中国账单时间按 Asia/Shanghai 解释后存 UTC；不创建 `~/.ft`

**Scale/Scope**: 未上线单用户产品；可丢弃开发库；43 个现有测试文件中约 32 个含本地账本耦合，
需要删除 legacy contract 或迁移仍有效的业务不变量

## Constitution Check

### 研究前

| Principle | Gate | Result |
|---|---|---|
| I 财务正确性与可审计性 | Decimal、来源关系、重复导入幂等、失败原子性必须写入设计与测试 | PASS |
| II Spec Kit 规格驱动 | spec 已明确破坏性范围、非目标与成功标准 | PASS |
| III 测试先行 | tasks 必须先安排配置/CLI/schema/repository/import 的失败测试 | PASS |
| IV 单一事实源与零历史包袱 | 不保留 local、迁移、shadow、fallback、双写或 runtime rollback | PASS |
| V 清晰边界与最小复杂度 | 复用现有 application/UoW；只提取仍有产品价值的纯解析与投资规则 | PASS |

### 设计后复核

- PostgreSQL 是唯一 composition root；旧配置被拒绝而不是忽略。
- 账户使用稳定 ID，正式事实和投影不再把账户名当引用；重命名不会改写历史归属，有事实的账户禁止硬删除。
- statement import 的 artifact、raw record、formal fact link 和 batch 状态同事务提交。
- database snapshot 仅是可重建投影，不是第二事实源；不从 CSV/YAML 重建。
- 旧 reconcile 依赖文件型 review session，当前 feature 删除产品入口和实现；数据库关系审查列表
  属于后续独立 feature，未夹带进本 feature。
- 无 constitution 违例，无需 Complexity Tracking 例外。

## Architecture

```text
CLI / future Web / Worker / MCP
              |
       StorageSettings
  (database_url, workspace_id)
              |
        ServiceBundle
              |
   application services / UoW
              |
 workspace-bound PostgreSQL repositories
              |
 PostgreSQL initial baseline
```

### Runtime startup

1. `StorageSettings.load()` 只接受数据库 URL 与 workspace ID。
2. 发现 `backend`、`ledger_root`、`FT_STORAGE_BACKEND` 或 `FT_DIR` 时明确报废弃配置错误。
3. composition root 验证连接、Alembic/schema 基线和 workspace 存在；普通命令不自动建库或建 workspace。
4. CLI 每次解析命令后只构建一次 bundle；纯 `--help` 和版本输出不连接数据库。

### Supported product surface

- 保留并接 PostgreSQL：`acct`、`report`、`list`、`add`、`checkin`、`transfer`、投资手工写入与查询、
  当前已支持 provider/format 的 statement import。
- `convert --output` 可保留为显式导出工具；输出文件不是 runtime fact，也不触发文件账本注册。
- 删除：`commit`、`status`、`reset`、`migrate *`、CSV snapshot `verify --fix`、converted CSV `append`。
- 删除当前文件型 `reconcile` 和 local-backed Connector sync 入口；它们分别等待数据库关系审查列表
  和 PostgreSQL-native Connector feature 再恢复。

### Transaction and provenance flow

```text
source file
  -> SHA-256 + ImportBatch(pending, target_account_id)
  -> RawFile + immutable RawRecords
  -> validate/map with Decimal semantics
  -> CashTransaction / InvestmentEvent
  -> formal facts with raw_record_id + RecordRevision
  -> projection update
  -> ImportBatch(completed) + COMMIT

any failure -> database ROLLBACK, no partial raw/formal/projection state
same workspace + provider + digest -> return existing completed batch
```

## Project Structure

### Documentation (this feature)

```text
specs/001-postgres-only-storage/
├── spec.md
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
│   ├── cli.md
│   └── runtime.md
├── checklists/requirements.md
└── tasks.md
```

### Source Code (repository root)

```text
src/ft/
├── cli.py
├── config.py
├── runtime.py
├── application/                 # storage-independent use cases
├── domain/                      # Decimal rules and DTOs
├── importers/                   # raw statement parsers only
├── repositories/                # workspace-safe ports
└── adapters/
    ├── postgres/                # only runtime persistence adapter
    ├── market_data.py
    └── export_csv.py            # explicit user export only

migrations/
└── versions/
    └── 20260717_01_initial.py    # single clean baseline

tests/
├── unit/application and parser tests
├── PostgreSQL repository/runtime contract tests
├── CLI contract tests
└── gated live PostgreSQL tests
```

**Structure Decision**: 保持单包和现有 application/domain/adapter 分层；删除 local adapter 族，不创建
第二套目录或临时 compatibility package。纯 parser 从混合 legacy 文件中提取到既有 `importers/` 或
无 I/O 的领域模块。

## Delivery Phases

1. 先以失败测试锁定 PostgreSQL-only 配置、CLI help、启动失败关闭与单 baseline。
2. 修正 schema 的稳定 account ID、时间类型、workspace 约束和 source-to-fact lineage。
3. 接通账户、现金、转账、查询和投资写入，验证跨入口可见与事务原子性。
4. 建立直接 statement import，覆盖当前支持的支付宝、微信、工行、建行与东方证券输入矩阵。
5. 删除 local/migration/change-set/reconcile/Connector/CSV snapshot 实现及 legacy tests。
6. 同步 README、顶层产品计划、wealth design、历史 Phase 2 文档、CLI help 与项目 Skill/reference 指引。

## Test Strategy

```text
configuration / CLI contract
  ├─ old keys and removed commands fail
  ├─ help does not connect to DB
  └─ runtime commands require DB + schema + workspace
                    |
                    v
schema / repository contract
  ├─ one Alembic baseline
  ├─ Decimal + timestamptz + account FK
  ├─ workspace isolation
  └─ raw_record_id lineage
                    |
                    v
application contract
  ├─ account/cash/transfer/investment invariants
  ├─ import idempotency and atomic rollback
  └─ query sees writes from the same workspace
                    |
                    v
live PostgreSQL + filesystem guard
  ├─ fresh DB -> head -> smoke flow
  ├─ injected failure leaves no partial facts
  └─ HOME contains no newly created .ft
                    |
                    v
full pytest + legacy rg audit + docs links + diff check
```

Every behavior-changing implementation task follows RED test → minimal code → focused green. Parser unit tests remain
storage independent; deleted local persistence tests are not mechanically ported unless they encode a still-valid
financial invariant.

## gstack Architecture Review

**Status**: CLEAR after incorporating review findings on 2026-07-17.

- Scope challenge: the 8+ file blast radius is inherent to physically removing a cross-cutting backend. A smaller
  “PostgreSQL default” patch was rejected because it leaves executable local storage. Connector sync and file reconcile
  were removed from this feature instead of being rebuilt inside the storage cleanup.
- Architecture: use one PostgreSQL composition root and one explicit startup validator. No hidden `create_all`, workspace
  provisioning, local fallback or compatibility alias.
- Data integrity: replace account-name references with stable account IDs; formal facts use nullable `raw_record_id` FKs
  rather than a polymorphic link that PostgreSQL cannot enforce.
- Audit integrity: revisions use nullable fact-specific foreign keys plus an exactly-one-target check; projections are keyed
  by account ID, and account deletion uses `RESTRICT` when facts exist.
- Code quality: extract only pure parsers/projectors from mixed legacy modules, then delete the original modules. Do not
  wrap legacy file functions behind new adapters.
- Tests: preserve financial invariants, not filesystem contracts. Use live PostgreSQL for Alembic/type/constraint evidence
  and fast repository/application tests for iteration.
- Performance: preload account/source identities per import batch and commit once; avoid per-row transaction commits and
  N+1 identity lookups. No caching, queue or new infrastructure is justified at current scale.
- Production failure cases covered: unavailable DB, stale schema, unknown workspace, duplicate source, invalid Decimal,
  missing account, cross-workspace reference and mid-import exception all fail closed.

### What already exists

- `PostgresUnitOfWork`、workspace-scoped account/cashflow/investment/snapshot repositories：复用并修正 schema，
  不另建 repository framework。
- `FinanceQueryService`、`AccountService`、`CashflowService`、`TransferService`：保留 application contract，
  CLI 改为统一注入 bundle。
- `PostgresImportRepository`：复用 batch/raw/revision 基础，删除 migration 命名和多余 export target，补直接
  statement import 与正式事实 lineage。
- `ft.importers` 与现有 statement parser：保留纯解析与财务规则；从 `local_*`/`stock.py` 中仅提取仍需要的
  无持久化逻辑。
- `PortfolioQueryService` 与现有投资投影规则：复用领域行为，把文件写入换成 PostgreSQL UoW。

### NOT in scope

- 旧 `~/.ft` 数据迁移、自动清理或导出：开发数据可丢弃，应用完全忽略。
- schema 兼容链与 runtime rollback：未上线数据库直接重建 initial baseline。
- 关系审查列表、`reconcile` UI 与人工审批：需要独立 PostgreSQL 状态模型，后续 feature 恢复。
- Connector sync：需要 secret、mapping、provider 失败与 DB 幂等合同，后续独立 feature 恢复。
- 财富变化报告、Web、认证、Worker、AI 和 MCP：保持当前 feature 单一目标。
- JSON/YAML statement provider：当前 CLI 没有这类 parser，本 feature 只覆盖开始时已支持的 provider/format。

### Failure modes

| Path | Production failure | Test | Handling / user result |
|---|---|---|---|
| Settings | 缺 URL/workspace 或出现旧 key | config contract | 启动前明确报错，不访问 HOME |
| Startup | DB unreachable / schema not at baseline | runtime + live PG | 非零退出，提示连接或 `alembic upgrade head` |
| Startup | workspace 不存在 | runtime + live PG | 非零退出，不伪装成空账本 |
| Manual write | 金额非有限 Decimal / account 不存在 | application contract | UoW rollback，字段级错误 |
| Account rename | 事实仍按旧名称关联 | FK/rename integration | stable account ID 保持历史归属，查询显示新名称 |
| Account delete | 活跃账户或有事实账户被硬删除 | FK/delete integration | application active guard + `RESTRICT`，只允许删除已停用空账户 |
| Statement import | 重复文件 | provenance contract | 返回 completed batch，不重复发布 facts |
| Statement import | 同文件重复 provider ID / 全重叠批次丢失目标账户 | provenance contract | 每个 raw identity 只投影一次；batch 直接持有 target account ID |
| Statement import | 中途 parser/repository exception | failure injection | 整个事务回滚，无 pending/raw/formal 残留 |
| Statement import | 大批量逐行查 identity | query-count/batch test | 预载 identity/account，单事务批量处理 |
| Workspace boundary | 输入试图引用其他 workspace account/raw row | repository contract | not found + rollback |
| Explicit export | 输出路径不可写 | CLI contract | 明确 I/O 错误，不影响数据库事实 |
| Investment CLI | application service 拒绝写入但进程返回 0 | CLI contract | 检查 `OperationResult.ok` 并以非零状态退出 |
| Portfolio query | 投资账户默认币种被当成证券 ticker | application/query contract | account currency 至少进入该账户的 cash currency 集合 |

审查后没有“无测试、无错误处理且静默失败”的 critical gap。

### Parallelization

顺序实施，不建议多 worktree 并行。schema、PostgreSQL repositories、application services、CLI 和 legacy 删除
共享同一依赖链；提前并行会在模型与 composition root 产生高冲突。文档清理可在核心合同转绿后独立执行，
但不应先于最终 CLI 行为完成。

## Complexity Tracking

无 constitution 违例。

## GSTACK REVIEW REPORT

| Review | Trigger | Why | Runs | Status | Findings |
|--------|---------|-----|------|--------|----------|
| CEO Review | `/plan-ceo-review` | Scope & strategy | 0 | — | 本轮沿用已批准 office-hours 产品方向与用户最新 PostgreSQL-only 决策 |
| Codex Review | `/codex review` | Independent 2nd opinion | 2 | CLEAR | 2 runtime findings fixed; untracked-file warning documented as local pre-commit state |
| Eng Review | `/plan-eng-review` | Architecture & tests (required) | 1 | CLEAR | 4 issues incorporated, 0 critical gaps |
| Design Review | `/plan-design-review` | UI/UX gaps | 0 | N/A | 本 feature 无 UI 变更 |
| DX Review | `/plan-devex-review` | Developer experience gaps | 0 | N/A | 非发布门禁 |

- **UNRESOLVED:** 0
- **VERDICT:** ENG CLEARED + CODE REVIEW CLEARED + SPECKIT CONVERGED — implementation complete.
