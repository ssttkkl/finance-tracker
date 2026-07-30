# 收支投影验证指南

## 前置条件

- Python 3.11+、`uv`。
- Node.js 20 LTS+、`npm`。
- 用户账本：`/Users/huangwenlong/.ft/finance-tracker.db`，工作区 `default`。
- 本机 PostgreSQL 17.10 测试库：`postgresql+psycopg:///finance_tracker_test`。
- 不使用容器，不升级 gstack。

## 1. 静态与迁移测试

```sh
uv run alembic heads
uv run pytest tests/test_cash_projection.py tests/test_cash_projection_migration.py -q
uv run pytest tests/test_application_cash_projections.py -q
git diff --check
```

预期：Alembic 只有一个 head；领域、迁移和应用测试通过；diff 没有空白错误。

## 2. SQLite 安全集成矩阵

自动化测试必须从 `/Users/huangwenlong/.ft/finance-tracker.db` 创建临时副本，所有 schema 升级、投影写入
和故障注入都在副本执行，不得先修改用户账本。

```sh
FT_TEST_SQLITE_SOURCE='/Users/huangwenlong/.ft/finance-tracker.db' \
  uv run pytest tests/integration/test_cash_projection_sqlite.py \
    tests/integration/test_web_sqlite.py -q
```

预期：测试验证单成员、同笔支付、部分/全额退款、内部转账、增量提交、失败回滚、全量发布、版本游标和
证据详情；源文件的大小、mtime 和摘要不变。

## 3. 真实 PostgreSQL 等价矩阵

```sh
FT_TEST_POSTGRES_URL='postgresql+psycopg:///finance_tracker_test' \
  FT_REQUIRE_TEST_POSTGRES=1 \
  uv run pytest tests/integration/test_cash_projection_postgres.py \
    tests/integration/test_web_postgres.py -q
```

再运行同一规范响应矩阵：

```sh
FT_TEST_SQLITE_SOURCE='/Users/huangwenlong/.ft/finance-tracker.db' \
  FT_TEST_POSTGRES_URL='postgresql+psycopg:///finance_tracker_test' \
  FT_REQUIRE_TEST_POSTGRES=1 \
  uv run pytest tests/contract/test_cash_projection_parity.py \
    tests/contract/test_web_api.py -q
```

预期：两个后端的投影 ID、成员、净额、经济类型、隐藏原因、证据、分页和稳定错误码完全一致。

## 4. 用户 SQLite 备份、升级与重建

只有自动化矩阵通过后才操作用户账本。先停止所有写入 Finance Tracker 的进程，再执行。初始备份不存在时
创建它；若已存在，绝不覆盖，应以实际执行时间替换下方 `YYYYMMDD-HHMM` 并创建新的重建前备份：

```sh
if [ ! -e /Users/huangwenlong/.ft/finance-tracker.db.pre-020-projection.bak ]; then
  sqlite3 /Users/huangwenlong/.ft/finance-tracker.db \
    ".backup '/Users/huangwenlong/.ft/finance-tracker.db.pre-020-projection.bak'"
else
  sqlite3 /Users/huangwenlong/.ft/finance-tracker.db \
    ".backup '/Users/huangwenlong/.ft/finance-tracker.db.pre-020-projection-rebuild-YYYYMMDD-HHMM.bak'"
fi

FT_DATABASE_URL='sqlite:////Users/huangwenlong/.ft/finance-tracker.db' \
  uv run alembic upgrade head

FT_DATABASE_URL='sqlite:////Users/huangwenlong/.ft/finance-tracker.db' \
  FT_WORKSPACE_ID='default' \
  uv run ft projections rebuild

FT_DATABASE_URL='sqlite:////Users/huangwenlong/.ft/finance-tracker.db' \
  FT_WORKSPACE_ID='default' \
  uv run ft projections status
```

预期：状态为 `ready`，成员数等于工作区有效现金流水数；输出不包含交易对方、备注、SQL、凭据或完整
来源行快照。如果重建因历史非法关系失败，保留备份和错误码，先修复关系，不启动错误列表，也不回退
展示原始流水。

### 授权的历史关系修复

本步骤只适用于已获授权的 `default` 工作区。停止本机写入后，使用精确的 SQLite 文件路径创建带时间戳的
修复前备份，校验备份完整性和摘要；不得删除账单或关系行。单事务仅更新关系 `1541`、`2643`、`2834` 的
`status` 与 `decision_reason`，保留 `1054`、`3085`、`1339`、`3055`，并保持创建字段、既有决定字段和
`evidence_json` 不变。事务前置条件与完成后的 7 条关系、8 条账单验收以 `spec.md` 的 FR-032 为准。

若修复已提交但后续验收失败，先停止写入并确认没有打开数据库的连接，验证修复前备份后恢复
`/Users/huangwenlong/.ft/finance-tracker.db`，同时处理同目录同文件名的 `finance-tracker.db-wal` 与
`finance-tracker.db-shm`。随后执行 `integrity_check`、备份摘要比对以及关系和账单的只读验收；不得用目录复制、
通配符或删除账单作为恢复手段。

恢复命令中的两个路径必须替换为已验证的精确文件名：

```sh
FT_REPAIR_DB='/Users/huangwenlong/.ft/finance-tracker.db'
FT_REPAIR_BACKUP='/Users/huangwenlong/.ft/finance-tracker.db.pre-020-repair-YYYYMMDD-HHMM.bak'

sqlite3 "$FT_REPAIR_BACKUP" 'PRAGMA integrity_check;'
shasum -a 256 "$FT_REPAIR_BACKUP"
cp "$FT_REPAIR_BACKUP" "$FT_REPAIR_DB"
rm -f "$FT_REPAIR_DB-wal" "$FT_REPAIR_DB-shm"
sqlite3 "$FT_REPAIR_DB" 'PRAGMA integrity_check;'
shasum -a 256 "$FT_REPAIR_DB"
```

## 5. 本地双进程

终端 1：

```sh
FT_DATABASE_URL='sqlite:////Users/huangwenlong/.ft/finance-tracker.db' \
  FT_WORKSPACE_ID='default' \
  FT_WEB_ORIGIN='http://127.0.0.1:5173' \
  uv run ft web
```

终端 2：

```sh
cd web
npm install
VITE_FT_API_ORIGIN='http://127.0.0.1:8000' npm run dev
```

生产预览必须在构建时注入同一来源：

```sh
cd web
VITE_FT_API_ORIGIN='http://127.0.0.1:8000' npm run build
npm run start
```

## 6. 浏览器验收

在 `1440 × 900` 和 `390 × 844` 视口验证：

1. 页面名称为“收支账本”，不出现“消费账本”或原始流水回退开关。
2. 已确认同笔支付只显示一个投影。
3. 部分退款显示在原消费时间并使用冲销后金额；退款时间只在证据详情。
4. 全额退款和内部转账不进入列表。
5. 未确认关系两端仍独立显示，证据详情只把它列为未生效提示。
6. 组成方式筛选、经济类型分段、连续 3 页、证据详情和纯键盘焦点路径正确。
7. 投影版本变化时保留筛选并刷新第一页；投影不可用时不请求旧 `/cash-transactions`。
8. 控制台无错误，页面无横向遮挡、文字重叠或依赖悬停才能发现的关键操作。

```sh
cd web
npm test
npm run test:e2e
npm run test:preview
npm run build
```

完成后运行 gstack `qa`，报告必须注明所用 SQLite 路径、工作区、宽窄视口、空态、错误态和键盘流程。

## 7. 完整回归

```sh
FT_TEST_SQLITE_SOURCE='/Users/huangwenlong/.ft/finance-tracker.db' \
  FT_TEST_POSTGRES_URL='postgresql+psycopg:///finance_tracker_test' \
  FT_REQUIRE_TEST_POSTGRES=1 \
  uv run pytest tests/ -q \
    -k 'not test_fixed_100k_fact_rebuild_and_active_cache_meet_budgets'

uv build
uv run alembic heads
git diff --check
```

该命令按 `plan.md` 的 Complexity Tracking 排除既有财富冷构建性能用例的两个参数实例；测试文件与预算
保持不变。仓库当前没有独立 lint 命令；`npm run build` 必须包含 TypeScript 类型检查。任何其他未通过
或未运行项都要在本文件追加准确命令、原因和风险，不能把局部通过写成完整通过。

## 8. 回滚演练

1. 停止新前端和 API。
2. 验证备份文件存在且摘要可读。
3. 应用代码回滚时保留投影表；旧版 Web 原始流水页面不作为合规回退入口。
4. 只有确认没有新代码依赖后，才在测试副本执行 `uv run alembic downgrade 20260726_10`，验证只删除
   投影派生表且现金流水、关系行数和内容摘要不变。
5. 修复后重新升级、重建和完成浏览器验收，再恢复收支账本。

## 执行记录

### 2026-07-29：T085～T087 关系不变量修正与完整验收

`transfer_pair` 按 subtype 校验端点：所有关系必须金额异号；`currency_exchange` 必须使用不同币种且不比较金额绝对值；其他内部转账必须同币种且金额绝对值相等。该规则保留合法的 `-100 CNY` / `+14 USD` 跨币种换汇，并阻断同币种换汇、同号端点和同币种金额不等的普通转账。

```sh
FT_TEST_POSTGRES_URL='postgresql+psycopg:///finance_tracker_test' \
FT_REQUIRE_TEST_POSTGRES=1 \
  uv run pytest \
    tests/test_cash_projection.py::test_transfer_pair_is_hidden_internal_transfer \
    tests/test_cash_projection.py::test_transfer_pair_endpoint_invariants_fail_closed \
    tests/test_cash_projection.py::test_currency_exchange_requires_distinct_currencies \
    tests/contract/test_cash_projection_parity.py::test_transfer_pair_endpoint_invariants_are_identical_on_both_backends \
    tests/contract/test_cash_projection_parity.py::test_currency_exchange_endpoint_invariants_are_identical_on_both_backends \
    tests/test_multi_currency_accounts.py::test_transfer_by_name_and_operation_currencies_cross_currency_requires_to_amount \
    tests/test_cash_projection_cli.py::test_projection_status_postgres_transaction_rejects_write \
    tests/test_cash_projection_cli.py::test_projection_status_redacts_sqlite_snapshot_cleanup_error -q

FT_TEST_SQLITE_SOURCE='/Users/huangwenlong/.ft/finance-tracker.db' \
FT_TEST_POSTGRES_URL='postgresql+psycopg:///finance_tracker_test' \
FT_REQUIRE_TEST_POSTGRES=1 \
  uv run pytest tests/ -q \
    -k 'not test_fixed_100k_fact_rebuild_and_active_cache_meet_budgets'

uv build
uv run alembic heads
git diff --check

cd web
npm test
npm run test:e2e
npm run test:preview
npm run build
```

结果：目标集 `16 passed`；完整 Python 回归 `1095 passed, 9 skipped, 2 deselected, 1 warning`。跳过和排除范围保持既有批准的财富读模型性能门禁例外。独立 Node 前端的 Vitest 为 `17 passed`，Playwright 端到端测试为 `5 passed`，生产预览测试为 `1 passed`；Python wheel/sdist、TypeScript 检查和 Vite 生产构建均已完成。Alembic 只有 `20260729_11 (head)`，`git diff --check` 通过。

真实 SQLite 只作为测试副本来源，未执行备份、升级、重建或写入；运行前后 SHA-256 均为 `d57ab315f50d7e32149f9c8d39b40551f2bdc87546a43930ab836386bb7b28a1`，文件大小为 `64835584` 字节，修改时间为 `1785228498`。直接 Codex CLI 评审不在本次实施范围内，仍由主 session 执行。

### 2026-07-29：T088 异币种内部转账端点合同

在 T085 验收后澄清关系种类不变量：所有 `transfer_pair` 必须金额异号；仅两端币种相同时要求金额绝对值相等；两端币种不同时不比较金额绝对值。`currency_exchange` 仍必须使用不同币种。普通转账、信用还款、换汇和银证转账均可使用异币种不等额端点，并生成隐藏的内部转账投影。

测试先行时，旧实现错误拒绝普通转账、信用还款和银证转账的异币种不等额端点；换汇仍能通过。调整领域校验后，运行以下矩阵：

```sh
FT_TEST_POSTGRES_URL='postgresql+psycopg:///finance_tracker_test' \
FT_REQUIRE_TEST_POSTGRES=1 \
  uv run pytest \
    tests/test_cash_projection.py::test_transfer_pair_is_hidden_internal_transfer \
    tests/test_cash_projection.py::test_transfer_pair_endpoint_invariants_fail_closed \
    tests/test_cash_projection.py::test_currency_exchange_requires_distinct_currencies \
    tests/contract/test_cash_projection_parity.py::test_transfer_pair_endpoint_invariants_are_identical_on_both_backends \
    tests/contract/test_cash_projection_parity.py::test_cross_currency_transfer_pair_is_hidden_on_both_backends \
    tests/contract/test_cash_projection_parity.py::test_currency_exchange_endpoint_invariants_are_identical_on_both_backends \
    tests/test_multi_currency_accounts.py::test_transfer_by_name_and_operation_currencies_cross_currency_requires_to_amount -q

FT_TEST_SQLITE_SOURCE='/Users/huangwenlong/.ft/finance-tracker.db' \
FT_TEST_POSTGRES_URL='postgresql+psycopg:///finance_tracker_test' \
FT_REQUIRE_TEST_POSTGRES=1 \
  uv run pytest \
    tests/contract/test_cash_projection_parity.py \
    tests/integration/test_cash_projection_sqlite.py \
    tests/integration/test_cash_projection_postgres.py -q
```

结果：目标集 `16 passed`，SQLite 临时副本与本机 PostgreSQL 矩阵 `12 passed`。真实 SQLite 只作为副本来源，运行前后 SHA-256 为 `d57ab315f50d7e32149f9c8d39b40551f2bdc87546a43930ab836386bb7b28a1`，文件大小 `64835584` 字节，修改时间 `1785228498`，没有执行写入。

### 2026-07-29：T089 零金额与隐藏投影持久化合同

新增领域回归覆盖 `transfer_pair` 的任一端金额为零必须返回 `projection.invalid_relation`。SQLite 与 PostgreSQL 合同覆盖零金额对侧流水，并在普通转账、信用还款、换汇和银证转账的异币种不等额场景中逐后端读取持久化投影，确认经济类型为 `internal_transfer`、不展示、隐藏原因为 `internal_transfer`、子类型正确、主记录和对侧记录均为成员且净额为 `0`；两个后端的后端无关投影属性相同。

```sh
FT_TEST_POSTGRES_URL='postgresql+psycopg:///finance_tracker_test' \
FT_REQUIRE_TEST_POSTGRES=1 \
  uv run pytest tests/test_cash_projection.py \
    tests/contract/test_cash_projection_parity.py -q
```

结果：`30 passed`。零金额端点已由既有 `amount * amount >= 0` 校验拒绝，因此本任务未修改产品代码。测试只使用临时 SQLite 数据库与专用 `finance_tracker_test` PostgreSQL；真实 SQLite 未参与写入，SHA-256、文件大小和修改时间仍为 `d57ab315f50d7e32149f9c8d39b40551f2bdc87546a43930ab836386bb7b28a1`、`64835584` 字节和 `1785228498`。

### 2026-07-29：隔离 Codex CLI 复审

使用临时 `HOME`、`--ignore-user-config`、`--ignore-rules` 和只读沙箱执行两轮直接 `codex exec` 复审；未运行 Claude，也未读取 `.agents`、`~/.agents`、`.claude` 或 `~/.claude`。首轮指出零金额端点和异币种成功场景的持久化投影断言缺口，已由 T089 修复；第二轮未发现可操作问题。评审门禁现已通过。

### 2026-07-29：SQLite 临时副本矩阵

```sh
FT_TEST_SQLITE_SOURCE='/Users/huangwenlong/.ft/finance-tracker.db' \
  uv run pytest tests/integration/test_cash_projection_sqlite.py \
    tests/integration/test_web_sqlite.py tests/contract/test_web_api.py -q
```

结果：`20 passed, 2 skipped`。夹具已验证来源文件的大小、修改时间和 SHA-256 摘要均未变化；跳过项仅依赖
未在此命令中设置的 PostgreSQL 夹具。

### 2026-07-29：本机 PostgreSQL 矩阵

```sh
FT_REQUIRE_TEST_POSTGRES=1 \
FT_TEST_POSTGRES_URL='postgresql+psycopg:///finance_tracker_test' \
  uv run pytest tests/integration/test_cash_projection_postgres.py \
    tests/integration/test_web_postgres.py \
    tests/contract/test_cash_projection_parity.py \
    tests/contract/test_web_api.py -q
```

结果：`23 passed`。测试仅使用专用 `finance_tracker_test` 数据库，并在夹具结束后重置 schema。

### 2026-07-29：完整回归的兼容性修复与阻断项

已修复本 feature 引入的两项回归：证据详情中的事实金额保留已存储小数位；确认待配对退款关系时，
持久化关系统一为原消费为主记录、退款为对侧流水。相关收支投影测试结果如下：

```sh
uv run pytest tests/test_relational_cash_projection_evidence.py \
  tests/test_application_cash_projection_evidence.py \
  tests/contract/test_web_api.py -q

FT_REQUIRE_TEST_POSTGRES=1 \
FT_TEST_POSTGRES_URL='postgresql+psycopg:///finance_tracker_test' \
  uv run pytest tests/test_transaction_relations_open_leg.py::test_open_leg_accept_requires_other_and_binds \
    tests/test_transaction_relations_open_leg.py::test_transfer_open_leg_persisted_and_accept -q
```

结果：分别为 `21 passed, 2 skipped` 和 `4 passed`。

完整 pytest 还暴露了一个既有财富读模型性能门禁，已在无并发进程时分别复跑：

```sh
env -u FT_TEST_POSTGRES_URL -u FT_REQUIRE_TEST_POSTGRES \
  uv run pytest 'tests/test_wealth_performance.py::test_fixed_100k_fact_rebuild_and_active_cache_meet_budgets[sqlite]' -q

FT_REQUIRE_TEST_POSTGRES=1 \
FT_TEST_POSTGRES_URL='postgresql+psycopg:///finance_tracker_test' \
  uv run pytest 'tests/test_wealth_performance.py::test_fixed_100k_fact_rebuild_and_active_cache_meet_budgets[postgresql]' -q
```

结果：SQLite 冷重建 p95 为 `5.685 s`，超过 `5.000 s` 门禁；PostgreSQL 冷重建 p95 为 `9.988 s`，
超过 `6.500 s` 门禁。两后端热读均低于 `300 ms`。`cProfile` 显示成本集中在既有财富读模型的来源清单
捕获、清单持久化和来源一致性读取；这三处均不属于 020 的改动范围。未放宽预算，也未运行真实账本
备份、升级或重建。

用户已明确批准：Spec 020 验收暂不纳入这两个参数实例。测试与预算保持原状，不做永久跳过；后续财富
读模型改造 feature 必须恢复默认完整回归并处理该性能门禁。本 feature 改用以下准确命令验证其余测试：

```sh
FT_TEST_SQLITE_SOURCE='/Users/huangwenlong/.ft/finance-tracker.db' \
FT_TEST_POSTGRES_URL='postgresql+psycopg:///finance_tracker_test' \
FT_REQUIRE_TEST_POSTGRES=1 \
  uv run pytest tests/ -q \
    -k 'not test_fixed_100k_fact_rebuild_and_active_cache_meet_budgets'
```

同日，独立 Node 前端完成以下复验：

```sh
cd web
npm test
npm run build
npm run test:e2e
npm run test:preview
```

结果：14 个 Vitest 测试、5 个 Playwright E2E 测试和 1 个生产预览测试全部通过；`npm run build`
已完成 TypeScript 类型检查和 Vite 生产构建。

### 2026-07-29：评审回流合同

```sh
FT_REQUIRE_TEST_POSTGRES=1 \
FT_TEST_POSTGRES_URL='postgresql+psycopg:///finance_tracker_test' \
  uv run pytest tests/test_relational_cash_projection_evidence.py \
    tests/test_transaction_relations_open_leg.py \
    tests/test_application_web_queries.py \
    tests/contract/test_web_api.py -q
```

结果：`43 passed, 1 warning`。警告来自既有 FastAPI 测试客户端对 `httpx` 的弃用提示，不影响测试结果。
该矩阵确认成员与退款时间线金额统一为无指数规范十进制字符串；分页的状态、数据集、游标校验、页面和
下一页游标来自同一读取快照；待配对退款无论人工确认还是系统自动接受，均持久化为原消费主记录、退款
对侧流水。

为确认新增合同能捕获旧缺陷，曾在本地临时恢复两处旧实现并立即还原：先读取投影版本、再在另一会话读取
页面时，SQLite 和 PostgreSQL 都出现旧版本配新页面；待配对退款按旧参数自动绑定时，两端都将退款写为
主记录。两项合同均在这两种变异下失败。

### 2026-07-29：T077 一致读取复审修正

PostgreSQL 默认 `READ COMMITTED` 为每条 SQL 建立新快照，不能把“同一会话”当作状态、页面和关系摘要的
一致读取保证。列表改为单条 SQL：`active_state` CTE 读取活动版本与数据集，`projection_page` CTE 在同一
查询中完成游标版本校验、筛选、排序和限长页面，最终结果同时读取投影关系依据，并在应用层按投影行归组
关系摘要。下一页游标只使用该查询返回的版本和末行排序键。

并发合同在活动状态读取完成时，由另一连接新增退款、写入已确认退款冲销关系并重建投影。SQLite 测试库仅
在本次夹具中启用 WAL，以允许该读写交错；真实账本不参与测试。双后端结果如下：

```sh
FT_REQUIRE_TEST_POSTGRES=1 \
FT_TEST_POSTGRES_URL='postgresql+psycopg:///finance_tracker_test' \
  uv run pytest tests/test_application_web_queries.py::test_projection_page_keeps_version_and_dataset_in_one_read_snapshot -q
```

结果：`2 passed`。合同核对旧版本页面中的投影 ID、金额、组成方式和关系摘要。临时恢复旧的“先读版本、再读
页面”服务路径后，PostgreSQL 复现版本仍为 V1、`cash:1003` 却显示 V2 的 `-9.5`、`refund_offset` 和关系
摘要；当前实现已立即恢复。随后重跑评审回流矩阵，结果为 `43 passed, 1 warning`；警告仍是既有 FastAPI
测试客户端对 `httpx` 的弃用提示。

### 2026-07-29：最终复审与未完成门禁

独立复审确认 T076～T078 的最终实现和证据闭环：金额使用无指数规范十进制字符串；自动与人工待配对退款
均保持“原消费为主记录、退款为对侧流水”；列表以单条查询保证状态、页面和关系摘要一致。复审后的双后端
评审回流矩阵仍为 `43 passed, 1 warning`。

gstack `review` 仍未通过工具门禁。最后一次使用最小明确输入运行 `claude -p '/review ...'` 等待 10 分钟，
日志保持 0 字节且无输出，随后终止；此前同类尝试亦无输出。该结果不能替代代码评审，因此 T069 保持未完成。

既有财富读模型性能门禁的风险保持不变：SQLite 与 PostgreSQL 冷重建 p95 分别为 `5.685 s` 和 `9.988 s`，
超过既定 `5.000 s` 与 `6.500 s` 门禁。本功能不修改财富模块，也不放宽性能预算。经用户批准，T068
仅排除该既有测试的 SQLite 和 PostgreSQL 两个参数实例；T070 和 T071 仍依赖 T069，未执行真实账本备份、
数据库架构升级、投影重建或真实 Web 质量验收。

本次 T072 只读核对结果：`git diff --check` 通过；工作区仍有本 feature 的已修改和未跟踪文件，当前分支
`spec-20-progress` 没有上游，`gh pr view` 返回无 PR，未创建提交或推送。真实账本
`/Users/huangwenlong/.ft/finance-tracker.db` 存在，SHA-256 为
`d57ab315f50d7e32149f9c8d39b40551f2bdc87546a43930ab836386bb7b28a1`；因 T070 未执行，
`finance-tracker.db.pre-020-projection.bak` 不存在。端口 `8000` 与 `5173` 无监听；发现既有
`ft web --port 8765` 进程，本次未启动或操作该进程。上述门禁未全部满足，T072 保持未完成。

### 2026-07-29：T068 已批准范围内的完整回归

用户明确批准在 Spec 020 验收中排除既有财富读模型性能测试
`test_fixed_100k_fact_rebuild_and_active_cache_meet_budgets` 的 SQLite 和 PostgreSQL 参数实例。该例外只适用于
本功能的本地验收；测试代码和性能预算均未修改。风险是财富读模型的冷重建仍未达到既定 p95 门禁，后续
财富读模型改造功能必须恢复默认完整回归并处理该门禁。

```sh
FT_TEST_SQLITE_SOURCE='/Users/huangwenlong/.ft/finance-tracker.db' \
FT_TEST_POSTGRES_URL='postgresql+psycopg:///finance_tracker_test' \
FT_REQUIRE_TEST_POSTGRES=1 \
  uv run pytest tests/ -q \
    -k 'not test_fixed_100k_fact_rebuild_and_active_cache_meet_budgets'

uv build
uv run alembic heads

cd web
npm test
npm run test:e2e
npm run test:preview
npm run build
```

结果：Python 矩阵为 `1067 passed, 9 skipped, 2 deselected, 1 warning`，耗时 `93.84 s`；唯一警告是既有
FastAPI 测试客户端对 `httpx` 的弃用提示。`uv build` 成功构建源码包和 wheel，`uv run alembic heads` 输出
唯一迁移头 `20260729_11 (head)`。独立 Node 前端的 Vitest 为 `14 passed`，Playwright 端到端测试为
`5 passed`，生产预览测试为 `1 passed`，并完成 TypeScript 检查和 Vite 生产构建。

恢复路径：财富读模型发生改动时，移除上述 `-k` 排除条件，使用相同 SQLite 临时副本和专用 PostgreSQL
测试库运行默认完整 `pytest` 回归；在两个参数实例均满足原有 p95 门禁前，不得扩大本例外的适用范围。

### 2026-07-29：T079～T083 回归与 T084 验证

T079 的关系种类不变量要求同笔支付两端同金额、同币种且方向一致。完整回归发现旧用例把不满足该条件的
`payment_mirror` 断言为可接受，已将该用例的本地夹具调整为合法金额，继续验证归并、拆分和逻辑删除。

```sh
FT_TEST_SQLITE_SOURCE='/Users/huangwenlong/.ft/finance-tracker.db' \
FT_TEST_POSTGRES_URL='postgresql+psycopg:///finance_tracker_test' \
FT_REQUIRE_TEST_POSTGRES=1 \
  uv run pytest tests/test_cash_projection.py \
    tests/contract/test_cash_projection_parity.py \
    tests/integration/test_cash_projection_concurrency.py \
    tests/integration/test_cash_projection_sqlite.py \
    tests/integration/test_cash_projection_postgres.py \
    tests/integration/test_web_sqlite.py \
    tests/integration/test_web_postgres.py \
    tests/test_cash_projection_cli.py \
    tests/contract/test_web_api.py -q

cd web
npm test
npm run test:e2e
npm run test:preview
npm run build

cd ..
FT_TEST_SQLITE_SOURCE='/Users/huangwenlong/.ft/finance-tracker.db' \
FT_TEST_POSTGRES_URL='postgresql+psycopg:///finance_tracker_test' \
FT_REQUIRE_TEST_POSTGRES=1 \
  uv run pytest tests/ -q \
    -k 'not test_fixed_100k_fact_rebuild_and_active_cache_meet_budgets'
uv build
uv run alembic heads
git diff --check
```

结果：定向 SQLite 临时副本和本机 PostgreSQL 矩阵为 `60 passed, 1 warning`；前端 Vitest 为 `17 passed`，
Playwright 端到端测试为 `5 passed`，生产预览测试为 `1 passed`，TypeScript 与 Vite 生产构建成功。完整
Python 矩阵为 `1084 passed, 9 skipped, 2 deselected, 1 warning`，耗时 `95.93 s`；唯一警告仍是 FastAPI
测试客户端对 `httpx` 的弃用提示。`uv build` 成功，`uv run alembic heads` 输出唯一迁移头
`20260729_11 (head)`，`git diff --check` 通过。

SQLite 测试仅从 `/Users/huangwenlong/.ft/finance-tracker.db` 创建临时副本；本次没有备份、升级、重建或
写入真实账本。验证结束时该源文件的大小为 `64835584` 字节、修改时间为 `2026-07-28 16:48:18 +0800`、
SHA-256 为 `d57ab315f50d7e32149f9c8d39b40551f2bdc87546a43930ab836386bb7b28a1`，与此前记录一致。

已批准的财富冷构建性能排除范围和风险保持不变。第二次直接 Codex CLI 评审未在本次执行，且未用 Claude
或 gstack 替代；该门禁待主 session 完成，因此 T084 保持未勾选。

### 2026-07-29：T090 零金额退款冲销关系

`refund_offset` 仅在原消费和退款两端金额均为 `0`、且币种相同时接受零金额例外。该关系仍生成一个
经济类型为 `expense`、净额为 `0`、不展示且隐藏原因为 `full_refund` 的收支投影，并保留两个投影成员和
关系依据。任一端金额为零，或两端均为零但币种不同，均返回 `projection.invalid_relation`；普通退款的方向、
币种和累计退款限制不变。

实现前，新增领域和双后端合同均因根记录金额校验返回 `projection.invalid_relation`。实现后执行：

```sh
FT_TEST_POSTGRES_URL='postgresql+psycopg:///finance_tracker_test' \
FT_REQUIRE_TEST_POSTGRES=1 \
  uv run pytest \
    tests/test_cash_projection.py \
    tests/contract/test_cash_projection_parity.py -q
```

结果：首次执行为 `38 passed in 8.54s`，复跑为 `38 passed in 9.70s`。本次命令未设置
`FT_TEST_SQLITE_SOURCE`，SQLite 使用测试运行时创建的临时数据库，PostgreSQL 使用
`finance_tracker_test`。测试结束后观察到真实账本
`/Users/huangwenlong/.ft/finance-tracker.db` 的 SHA-256 为
`5411268eb32f82fc5ea0cb47ecaf923eadd3646570261ca8aaaef83f06647b73`，与此前记录的摘要不同；文件大小仍为
`64835584` 字节、修改时间为 `1785332380`。变化来源未归因，本任务未将该文件作为测试输入，也不将这次
观察作为真实账本未写入的验证结论；没有对该文件执行备份、升级、重建或写入。

### 2026-07-29：T092、T093、T095、T096 关系自动确认修正

自动确认先把候选加入完整已确认关系连通组。`payment_mirror` 可以与退款冲销关系或内部转账关系共存；只有候选
加入后同组同时包含 `refund_offset` 与 `transfer_pair` 时，候选才保留为 `pending_review`，其证据写入脱敏标记
`auto_confirmation_blocker=relation.kind_conflict`。待审核关系不参与收支投影，因此不会触发投影维护并阻断账单
导入；人工确认仍通过完整投影校验拒绝冲突图。

同笔支付对同一渠道对、账户、交易对方、币种、金额、方向和 `Asia/Shanghai` 自然日完全一致的候选分组。两侧
数量相等且字段完整时，双方分别按 `occurred_at ASC, id ASC` 排序后逐项自动确认；数量不等或字段不完整时保留
为 `pending_review`。已确认的同一渠道对会占用两个端点，重扫不会生成交叉配对。

```sh
FT_TEST_POSTGRES_URL='postgresql+psycopg:///finance_tracker_test' \
FT_REQUIRE_TEST_POSTGRES=1 \
  uv run pytest \
    tests/test_transaction_relations_payment_mirror.py \
    tests/test_transaction_relations_projection.py \
    tests/test_transaction_relations_open_leg.py \
    tests/test_relations_pipeline_order.py \
    tests/test_relations_pack_boundaries.py \
    tests/contract/test_cash_projection_parity.py -q
```

结果：`66 passed in 14.48s`。该矩阵覆盖临时 SQLite 和专用 PostgreSQL 测试库；未设置
`FT_TEST_SQLITE_SOURCE`，未读取或写入真实账本。完整 Python 回归尚未取得可验证的退出结果，T094 与 T097
保持未完成。按当前约束未运行 Codex 或 Claude 审查。

### 2026-07-29：T091 零金额退款完整验收与真实账本重建尝试

在 SQLite 临时数据库和本机 `finance_tracker_test` PostgreSQL 上，`tests/test_cash_projection.py` 与
`tests/contract/test_cash_projection_parity.py` 的零金额退款矩阵通过 `38 passed`。获批排除既有财富读模型
性能用例两个参数实例后，完整 Python 回归通过 `1108 passed, 9 skipped, 2 deselected, 1 warning`；唯一警告仍为
FastAPI 测试客户端对 `httpx` 的既有弃用提示。临时 `HOME`、只读沙箱下的直接 Codex CLI 复审为 CLEAR，未
读取 `.agents`、`~/.agents`、`.claude` 或 `~/.claude`，未运行 Claude，未修改文件或运行测试；复审未发现可
操作问题。

真实账本在旧备份之后发生过未归因变化，因此保留
`/Users/huangwenlong/.ft/finance-tracker.db.pre-020-projection.bak`，并通过 SQLite 一致性备份建立
`/Users/huangwenlong/.ft/finance-tracker.db.pre-020-projection-rebuild-20260729-2214.bak`。随后确认迁移为当前
head，并对 `default` 工作区执行 `ft projections rebuild/status`。重建按预期失败为脱敏错误码
`projection.invalid_relation`；状态为 `uninitialized`、投影版本 `0`、投影条目和成员数均为 `0`。源库与新备份
的完整性检查均为 `ok`。

只读领域诊断发现两个历史已确认关系连通组违反现有不变量：关系 `1054`、`1541`、`3085` 同时包含退款冲销
和内部转账；关系 `1339`、`2643`、`2834`、`3055` 的同笔支付归并具有两个主记录。没有修改、删除、驳回或
自动修正任何现金流水和交易关系。人工修正这些关系后，重新执行第 4 节的重建和状态命令；在此之前，真实
收支账本 Web QA 保持阻断。

### 2026-07-29：T098、T099 关系重扫与渠道对占用修正

系统生成的双边待审核候选重扫前，先针对候选加入后的完整已确认关系连通组重新检查种类冲突。若仍同时包含
`refund_offset` 与 `transfer_pair`，候选继续保持 `pending_review`，保留既有
`auto_confirmation_blocker=relation.kind_conflict`，不会被自动确认路径覆盖。

同笔支付候选按规范化渠道对键升序处理。每个确认或待审核候选一经返回即占用两个端点，后续渠道对不能再使用
其中任一端点；数量不等的组最多保留一条稳定的待审核关系。因此返回和持久化的 `payment_mirror` 关系均不共享
端点。

```sh
FT_TEST_POSTGRES_URL='postgresql+psycopg:///finance_tracker_test' \
FT_REQUIRE_TEST_POSTGRES=1 \
  uv run pytest \
    tests/test_transaction_relations_payment_mirror.py \
    tests/test_transaction_relations_projection.py \
    tests/test_transaction_relations_open_leg.py \
    tests/test_relations_pipeline_order.py \
    tests/test_relations_pack_boundaries.py \
    tests/contract/test_cash_projection_parity.py -q
```

结果：`69 passed in 11.40s`。该矩阵覆盖临时 SQLite 和专用 `finance_tracker_test` PostgreSQL；未设置
`FT_TEST_SQLITE_SOURCE`，未读取或写入真实账本。

```sh
FT_TEST_POSTGRES_URL='postgresql+psycopg:///finance_tracker_test' \
FT_REQUIRE_TEST_POSTGRES=1 \
  uv run pytest -q \
    -k 'not test_fixed_100k_fact_rebuild_and_active_cache_meet_budgets'
uv run python -m compileall -q src/ft
git diff --check
```

结果：完整 Python 回归为 `1117 passed, 10 skipped, 2 deselected, 1 warning in 110.98s`；警告为 FastAPI
测试客户端依赖的既有 `httpx` 弃用提示。排除的两项参数实例仅为已批准的财富冷构建性能门禁例外，测试代码和
性能预算均未修改。编译和已跟踪改动的空白检查通过；未跟踪的 Spec 020 文件及
`tests/contract/test_cash_projection_parity.py` 的 `git diff --no-index --check` 亦无空白错误。

独立子代理最终只读复审为 CLEAR，并以以下命令复验关系与双后端投影合同：

```sh
FT_REQUIRE_TEST_POSTGRES=1 \
FT_TEST_POSTGRES_URL='postgresql+psycopg:///finance_tracker_test' \
  uv run pytest \
    tests/test_transaction_relations_projection.py \
    tests/test_transaction_relations_payment_mirror.py \
    tests/contract/test_cash_projection_parity.py -q
```

结果：`47 passed in 13.72s`，退出码为 `0`。复审后通过 `psql` 确认专用测试库的 `public` schema 无表。上述完整
Python 回归仍为 `1117 passed, 10 skipped, 2 deselected, 1 warning`；其中两项财富冷构建性能测试参数实例按用户
已批准豁免，不修改测试代码或性能预算。T094、T097 和 T100 均已完成。按当前约束未运行 Codex 或 Claude 审查。

### 2026-07-30：T101 历史关系修复前置验证

使用 SQLite 只读连接核对了授权的 7 条关系和 8 条受影响账单。三个目标关系
`1541/default/transfer_pair/1771→10919`、`2643/default/payment_mirror/6903→10673` 和
`2834/default/payment_mirror/7053→6523` 均为 `accepted`；四条保留关系的工作区、种类、端点和状态亦符合
计划中的不可变前置条件。

在真实库生成的临时 SQLite 副本及本机 `finance_tracker_test` PostgreSQL 的去标识化临时表中，三条带完整
前置条件的更新均各命中 1 行，目标关系均转换为 `rejected` 和指定审计原因。逐字段快照确认目标及保留关系的
端点、`created_*`、`decided_by`、`decided_at` 和 `evidence_json` 未变，8 条账单无差异。故意使第二条更新的
对侧端点不匹配时，该更新命中 0 行，事务回滚后关系和账单快照均无差异。PostgreSQL 验证仅使用临时表，结束后
`public` schema 仍无表。真实 SQLite 未写入。

### 2026-07-30：T102 临时修复与恢复演练

在临时 SQLite 副本中，以单一 `BEGIN IMMEDIATE` 事务执行三条经 T101 验证的精确更新。仅
`1541`、`2643` 和 `2834` 转为 `rejected`，保留关系和 8 条账单的快照不变。随后以该临时库运行
`ft projections rebuild`，`default` 工作区状态为 `ready`、投影版本为 `1`、投影条目数为 `8029`、成员数为
`11387`；投影关系依据共 `3418` 条，三个被拒绝关系均未被引用。

模拟验收失败后，先移出临时工作库同名的 `-wal`、`-shm`，再恢复精确主文件和对应侧车文件路径。恢复后的
`integrity_check` 为 `ok`，主文件与修复前备份字节一致，7 条关系和 8 条账单的 SHA-256 快照分别恢复为
`eae454efdb83984c598edae40659dab86134ea86f691c83ca12a28ca3ffbbfe9` 与
`4632548fa2fab4295f19384da0dfabb12ffd0fbb27fc94571d56978f6a3be90b`。演练全程只操作临时目录，未写入真实
SQLite。

### 2026-07-30：T103 真实 SQLite 历史关系修复

停止两个本机 `ft web --port 8765` 进程后，确认没有打开
`/Users/huangwenlong/.ft/finance-tracker.db` 及其同名 WAL/SHM 的本机连接。以 `sqlite3 .backup` 创建
`/Users/huangwenlong/.ft/finance-tracker.db.pre-020-legacy-repair-20260730-003339.bak`；备份
`integrity_check` 为 `ok`，SHA-256 为
`1277d08aa06d6d3006349e391cf8269838abf8ff9b111ed5093f1f7b8abcd7bf`。

单一 `BEGIN IMMEDIATE` 事务先验证全部 7 条关系的工作区、状态、种类和端点，随后三条精确更新均命中 1 行：
`1541` 转为 `rejected` 并记录 `legacy_relation_repair:kind_conflict`；`2643`、`2834` 转为 `rejected` 并记录
`legacy_relation_repair:mirror_time_order`。仅更新 `status` 和 `decision_reason`，未修改 `created_*`、
`decided_by`、`decided_at`、端点或 `evidence_json`。

提交后 `integrity_check` 为 `ok`。7 条关系不可变字段与 8 条账单的 SHA-256 快照分别保持
`2b7132eea425973f03e8f2ef443ea05bbbbd098226256e880ac068e48cd33b7d` 和
`4632548fa2fab4295f19384da0dfabb12ffd0fbb27fc94571d56978f6a3be90b`。三条目标关系状态和审计原因符合
计划，四条保留关系仍为 `accepted`；无需执行恢复。

### 2026-07-30：T104 收敛验收

保留既有初始备份 `/Users/huangwenlong/.ft/finance-tracker.db.pre-020-projection.bak`，并保留 T103 创建的
`/Users/huangwenlong/.ft/finance-tracker.db.pre-020-legacy-repair-20260730-003339.bak`，未覆盖或删除任何备份。
真实 SQLite 的 `integrity_check` 为 `ok`。历史关系修复后已重建 `default` 工作区投影；使用真实数据库的只读
状态命令再次确认：可用性为 `ready`、投影版本为 `1`、规则版本为 `cash-projection-v1`、投影条目数为 `8029`、
成员数为 `11387`。

本机 Python API 绑定 `http://127.0.0.1:8000`，独立 Node 前端绑定 `http://127.0.0.1:5173`，前端 API 来源为
`http://127.0.0.1:8000`。真实工作区验收确认投影和收支账户接口分别返回 `200`，已移除的
`/api/v1/cash-transactions` 返回 `404`。浏览器 QA 覆盖列表、证据详情、键盘打开与关闭、空态、API 连接失败后的
重试，以及 `1440 × 900` 和 `390 × 844` 视口；页面无控制台错误，宽窄屏均未发现重叠或横向遮挡。证据保存在
`.gstack/qa-reports/screenshots/`。

`npm run test:e2e` 和 `npm run test:preview` 的标准命令因已有 Vite 进程分别占用固定端口 `5174` 和 `5173`
而按严格端口合同退出，未终止现有进程。以不含 `webServer` 的临时 Playwright 配置复用 `5174` 执行相同端到端
用例，结果为 `5 passed`；以本会话专用的 `8767` API 替身和 `5175` 生产预览执行相同预览用例，结果为
`1 passed`。此前 `web/npm test` 为 `17 passed`，`web/npm run build` 通过。

```sh
FT_TEST_POSTGRES_URL='postgresql+psycopg:///finance_tracker_test' \
FT_REQUIRE_TEST_POSTGRES=1 \
  uv run pytest -q \
    -k 'not test_fixed_100k_fact_rebuild_and_active_cache_meet_budgets'
```

结果：`1117 passed, 10 skipped, 2 deselected, 1 warning in 105.20s`。唯一警告为既有 FastAPI 测试客户端对
`httpx` 的弃用提示；排除的两项参数实例仍仅适用于已批准的财富冷构建性能门禁例外。

最终检查中，`git diff --check` 通过，任务清单已全部勾选。用户授权的本地提交为
`fa6f523 完成收支投影与历史关系修复验证`；分支 `spec-20-progress` 未配置上游，因此没有推送，也没有创建
PR。验收结束后保留本机 API 和独立 Node 前端地址供继续使用；未删除账单，也未自动重扫交易关系。

### 2026-07-30：T105～T107 收支账本列表列调整

先在 `web/tests/CashLedgerPage.test.tsx` 与 `web/tests/accessibility.test.tsx` 写入列表列合同，并运行：

```sh
cd web
npm test -- CashLedgerPage.test.tsx accessibility.test.tsx
```

实现前结果为 `2 failed, 12 passed`。两个失败均显示实际列序仍为“发生时间、账户、交易对方、分类、金额、组成方式、来源、操作”，缺少“备注”且仍包含“组成方式”，符合目标行为尚未实现的预期。

实现后，收支账本列表列依次为发生时间、账户、交易对方、备注、分类、金额、来源和证据入口；备注为空时显示“未提供”。列表不再展示关系摘要；组成方式仍保留为筛选条件，已采用关系仍在证据详情说明。骨架行保持 8 列，窄屏卡片以可换行的“备注”行替换原关系摘要行。

```sh
cd web
npm test -- CashLedgerPage.test.tsx accessibility.test.tsx
npm run build
```

结果：指定 Vitest 为 `14 passed`，TypeScript 检查和 Vite 生产构建通过。

用户禁用 Claude 与 Codex CLI，gstack `qa` 的交互包装器不可执行；因此使用独立 Node 前端和 Playwright 进行等价浏览器验证。以允许
`http://127.0.0.1:5176` 来源的本机 API `http://127.0.0.1:8001` 启动临时前端后，检查 `1440 × 900` 与
`390 × 844` 视口：宽屏列序正确、无关系摘要、备注可见；组成方式筛选发起投影请求；键盘 `Enter` 打开证据详情、
`Escape` 关闭并把焦点返回原证据入口；窄屏备注可见且没有横向溢出。临时浏览器截图保存在
`/tmp/spec020-phase11-wide.png` 和 `/tmp/spec020-phase11-mobile.png`。

首次完整 `npm test` 结果为 `3` 个测试文件通过、`1` 个失败。唯一失败来自
`web/tests/CashTable.test.tsx`：该旧测试仍断言列表应显示“同笔支付关系（1）”等关系摘要，与本次已确认的
列表合同冲突。按 Flow-Back 已将该测试纳入 T105，并撤回 T105、T107 的完成状态；在更新过期断言并使完整
前端测试转绿前，本轮验收不视为完成。

Flow-Back 后，先复用上述失败输出作为旧关系摘要断言的红灯证据，再将 `CashTable.test.tsx` 改为当前列表合同：
备注列紧跟交易对方、没有“组成方式”列或关系摘要、备注值可读且空备注显示“未提供”。修正测试夹具的 `note`
字段传递后，执行：

```sh
cd web
npm test -- CashTable.test.tsx
npm test
npm run build
```

结果：单个 `CashTable` 测试为 `1 passed`；完整 Vitest 为 `4` 个测试文件、`17 passed`；TypeScript 检查与 Vite
生产构建通过。此前记录的独立 Node 浏览器验证继续适用，未运行 gstack `qa`、Claude 或 Codex CLI。

### 2026-07-30：T109～T111 列表语义与隔离运行时验证

先补充窄屏备注、列表无关系摘要、组成方式请求参数、证据详情已采用关系和原生表头语义的断言，再运行：

```sh
cd web
npm test -- CashLedgerPage.test.tsx accessibility.test.tsx
```

实现前 `CashLedgerPage.test.tsx` 通过，`accessibility.test.tsx` 失败：8 个列表列头的 `scope` 均为 `null`。这证明窄屏仅通过 `display: none` 隐藏表头时，尚未满足原生列头作用域要求。

实现为每个列表列头设置 `scope="col"`，并以视觉隐藏方式保留窄屏表头语义；同时删除无 DOM 对应项的关系摘要样式。随后运行：

```sh
cd web
npm test -- CashLedgerPage.test.tsx accessibility.test.tsx
npm test
npm run build
```

结果：受影响 Vitest 为 `14 passed`，完整 Vitest 为 4 个测试文件、`17 passed`，TypeScript 检查与 Vite 生产构建均通过。

固定端口 `5173` 和 `5174` 已由其他本机进程占用，未终止或复用这些进程。使用仅在本次验证期间创建的 Playwright 配置，以 `5176` 运行开发态前端，并以 `8767` 的临时 API 与 `5175` 的 Vite 生产预览运行：

```sh
cd web
npx playwright test -c playwright.spec020.config.ts
npx playwright test -c playwright.spec020.preview.config.ts
```

结果：开发态 E2E 为 `5 passed`，生产预览为 `1 passed`。开发态覆盖 `1440 × 900` 与 `390 × 844` 下的备注、无列表关系摘要、`composition=combined` 请求、证据详情中的同笔支付关系、键盘焦点返回、原生列头语义和无横向溢出。生产预览验证独立 Node 前端可读取临时 API 的账户与收支投影；临时 API 的允许来源通过 `FT_PREVIEW_WEB_ORIGIN` 显式限定为 `http://127.0.0.1:5175`。验证结束后移除了临时 Playwright 配置。

用户禁止 Claude 与 Codex CLI，gstack `qa` 的交互包装器不可执行；本轮继续以独立 Node 前端和 Playwright 提供等价浏览器验证。

### 2026-07-30：020 收敛验证补充

本轮按本指南重新执行的 SQLite 安全集成矩阵仅把
`/Users/huangwenlong/.ft/finance-tracker.db` 作为临时副本来源：

```sh
FT_TEST_SQLITE_SOURCE='/Users/huangwenlong/.ft/finance-tracker.db' \
  uv run pytest tests/integration/test_cash_projection_sqlite.py \
    tests/integration/test_web_sqlite.py -q
```

结果：`5 passed, 1 warning in 18.96s`。夹具校验源文件的大小、修改时间和摘要不变；唯一警告是既有 FastAPI
测试客户端依赖的 `httpx` 弃用提示。

真实 PostgreSQL 矩阵和共享合同矩阵均使用专用 `finance_tracker_test`：

```sh
FT_TEST_POSTGRES_URL='postgresql+psycopg:///finance_tracker_test' \
  FT_REQUIRE_TEST_POSTGRES=1 \
  uv run pytest tests/integration/test_cash_projection_postgres.py \
    tests/integration/test_web_postgres.py -q

FT_TEST_SQLITE_SOURCE='/Users/huangwenlong/.ft/finance-tracker.db' \
  FT_TEST_POSTGRES_URL='postgresql+psycopg:///finance_tracker_test' \
  FT_REQUIRE_TEST_POSTGRES=1 \
  uv run pytest tests/contract/test_cash_projection_parity.py \
    tests/contract/test_web_api.py -q
```

结果分别为 `5 passed, 1 warning in 6.33s` 和 `38 passed, 1 warning in 15.57s`；投影、分页、证据和稳定错误码的
SQLite/PostgreSQL 合同一致。

019 的测试稳定性修复已同步到代码与其 artifacts：

```sh
uv run pytest tests/test_application_investment.py::test_portfolio_query_uses_valuation_and_never_prices_configured_currency -q
```

结果：`1 passed in 0.06s`。该修复只向测试装配注入固定 UTC `clock`，不改变生产报价新鲜度或估值状态合同。

按已批准例外执行完整 Python 回归、构建与迁移检查：

```sh
FT_TEST_SQLITE_SOURCE='/Users/huangwenlong/.ft/finance-tracker.db' \
  FT_TEST_POSTGRES_URL='postgresql+psycopg:///finance_tracker_test' \
  FT_REQUIRE_TEST_POSTGRES=1 \
  uv run pytest tests/ -q \
    -k 'not test_fixed_100k_fact_rebuild_and_active_cache_meet_budgets'

uv build
uv run alembic heads
```

结果：Python 回归为 `1118 passed, 9 skipped, 2 deselected, 1 warning in 122.21s`；`uv build` 成功构建源码包和
wheel；Alembic 输出唯一迁移头 `20260729_11 (head)`。两项被排除的参数实例是已批准的财富冷构建性能门禁例外，
未运行、未修改，也不将本结果表述为默认完整回归全绿。其风险是财富读模型的既有性能预算仍未得到本轮验证；
准确补跑命令为 `uv run pytest -q`。

标准 Web 验收命令如下：

```sh
cd web
npm test
npm run test:e2e
npm run test:preview
npm run build
```

结果：Vitest 为 `4` 个测试文件、`17 passed`；标准 Playwright 端到端为 `5 passed`；标准生产预览为
`1 passed`；TypeScript 检查和 Vite 生产构建通过。首次在沙箱内执行端到端和生产预览时，分别因禁止监听
`127.0.0.1:5174` 与 `127.0.0.1:8766` 退出；在允许本机监听的环境中以相同标准命令复跑后均通过。终端中的
`rvm` 对 `ps` 的权限提示未影响任何命令的退出码。

gstack `review` 已完成对 `a88139e..3e1e4b3` 的 020 只读复核，未发现可操作问题。gstack `qa` 未运行：该 skill
要求干净工作树，而当前存在用户保留的 019、021、022、023 未提交工作，且本次禁止提交或暂存。标准 Playwright
矩阵已覆盖键盘筛选、连续分页、证据详情、空/错误状态、窄屏、减少动效、背景不可交互、无横向溢出和生产预览，
是本轮的等价浏览器验证证据。
