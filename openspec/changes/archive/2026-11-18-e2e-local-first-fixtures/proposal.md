## Why

本地优先账本导入（`offline-first-ledger-import`）把现金浏览从服务端 `GET /api/v1/cash-projections` 改为从 `/api/v1/ledger/snapshot` 的规范事实在端侧派生。合并后，Compose Web E2E 套件仍有 14 个文件 mock 已退役的投影接口，断言也绑定旧 `projection_id`，导致约百条数据类用例失败；PR #116 以已知 E2E 缺口合入。

## What Changes

- 新增 E2E 快照夹具构造器：把测试需要的收支条目转成 `accounts`、`cash_categories`、`cash_transactions`、`cash_transaction_components`、`cash_entries`、`cash_entry_members`、`cash_transaction_issues`、`transaction_relations` 等规范事实。
- 迁移 14 个 `compose-*.e2e.ts`：`/cash-projections` 路由替换为 `/ledger/snapshot`，选择器改用派生后的稳定对象 ID，逐用例修正断言。
- 保留 `compose-fixtures.ts` 的统一 mock 入口，避免每个用例重复构造快照。
- 不改变任何产品行为、后端接口或数据库结构。

## Non-goals

- 不恢复已删除的远端投影读取接口。
- 不调整本地派生规则、账本快照合同或 UI 视觉。
- 不新增设备端 E2E；仅 Web Playwright。

## Acceptance

- `npx playwright test` 在本地产物上全量通过（201 条）。
- `openspec validate --all --strict` 通过。
- 受影响测试与 `compose/qa/web` 的 `npm run test:node` 通过。

## Grading

B：仅测试基础设施与断言，不改变产品行为；影响面覆盖 14 个 E2E 文件，需要完整浏览器回归。
