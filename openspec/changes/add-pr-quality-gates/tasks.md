## 1. 思考：现状、失败项与边界

- [x] 1.1 阅读 `AGENTS.md`、`openspec/project-context.md`、`DOMAIN_GLOSSARY.md`、现有 Mobile CI、npm/uv 配置和受影响测试，确认本变更只涉及测试与 CI。
- [x] 1.2 记录基线：Web 单测 `143 passed`、Web 构建通过、E2E `37 passed / 1 failed`、视觉 `14 passed / 1 failed`、Python SQLite 全量 `1526 passed / 183 skipped`；记录两个失败的根因和 PostgreSQL skip 原因。

## 2. 计划：OpenSpec 与执行策略

- [x] 2.1 创建 `add-pr-quality-gates` 变更，写入需求澄清结论、范围、非目标、影响和回滚说明。
- [x] 2.2 在 `design.md` 固定前端 macOS 快照策略、前后端 job 边界、PostgreSQL service、缓存、诊断 artifact 和安全约束。
- [x] 2.3 确认本变更设置 `skip_specs: true`，因为不改变产品、API、数据或权限行为；确保不新增虚假的产品 delta 规格。

## 3. 任务拆分与一致性

- [x] 3.1 对照 proposal、design 和本清单检查：两个 Web 失败项、前端完整门禁、SQLite、PostgreSQL、触发器、权限、回滚和远程验证均有对应任务。
- [x] 3.2 运行 OpenSpec 状态检查，确认 `proposal`、`design`、`tasks` 完整且规格按 `skip_specs` 跳过；发现范围变化时先回写 artifact。

## 4. 构建：测试先行与最小实现

- [x] 4.1 先运行失败的工作区入口 E2E 和 `1024×768` 视觉测试，确认失败仍分别来自预期取消误报和过期快照，而不是环境问题。
- [x] 4.2 调整工作区入口 E2E 的 request failure 收集，只忽略精确的 `net::ERR_ABORTED` 主动取消；保留其他请求失败的失败断言，并重跑该测试及完整 E2E。
- [x] 4.3 只更新已审查的 `cash-ledger-1024x768-darwin.png` 与对应证据抽屉视觉基线，重跑该视口和完整视觉套件。
- [x] 4.4 新增 `.github/workflows/pr-checks.yml`：所有 Pull Request 与手动触发；前端完整 Web job；SQLite/ PostgreSQL 功能 job 及隔离双后端性能 job；最小权限、并发取消、锁定依赖和失败诊断 artifact。
- [x] 4.5 立即检查 workflow 的 YAML、action 输入、服务健康检查、数据库 URL 和 artifact 路径，避免将生产凭据或未受控数据库带入 CI。
- [x] 4.6 修复 PostgreSQL 投影重建的工作区/状态锁序、来源指纹的旧 ORM 会话读取，以及迁移测试穿越不可逆分类迁移的问题；先保留失败复现，再用最小实现转绿。窄范围证据：`FT_REQUIRE_TEST_POSTGRES=1 ... pytest -q tests/integration/test_cash_projection_concurrency.py` 为 `9 passed`（约 7.5s），迁移回归为 `1 passed`（约 0.5s）；性能门禁 SQLite/ PostgreSQL 分别为 `2 passed`（约 330s），SQLite cold p95 `4.771s`、PostgreSQL cold p95 `5.873s`，hot p95 分别约 `43ms`/`66ms`。
- [x] 4.7 兼容 Node 26 下 `jsdom` 仅提供 `window.localStorage` 而不提供 Node 全局 `localStorage` 的测试环境差异；只在 Web Vitest setup 暴露同一存储对象，不改变浏览器运行时。

## 5. 审查：范围、工程、设计与安全

- [x] 5.1 独立复核最终 diff：确认只包含本变更定义的投影并发/迁移回归、Web 测试稳定性、两张 `1024×768` 视觉基线、workflow 与 OpenSpec；未修改 API/schema/分支保护/Mobile CI，其他工作树脏文件未纳入。Finding：无阻断项；Node 26 jsdom `localStorage` 差异已用测试 setup 的同一内存 Storage 修复。
- [x] 5.2 工程与安全复核 workflow：确认前后端检查职责清晰、PostgreSQL 只使用一次性 `_test` 服务库、权限为 `contents: read`、失败 artifact 仅上传测试诊断目录且不上传环境变量；确认仓库忽略 `uv.lock`，因此 workflow 使用 `uv sync`/`pyproject.toml` 缓存键；workflow Prettier 检查通过，当前环境无 `actionlint`，未声称 actionlint 通过。
- [x] 5.3 复核视觉基线差异仅包含当前表格列变化，且没有通过放宽像素阈值或跳过视觉测试隐藏差异。跨平台 change 另因共享错误态文案更新其直接相关 `cash-ledger-error-darwin.png`，不归入本 change 的两张基线范围。

## 6. 测试与 QA：本地与 OpenSpec 验证

- [x] 6.1 运行 Web Vitest、共享包测试、共享包类型检查、Web 构建、完整 E2E、生产预览和完整视觉回归。联合结果：Web `144/144`、E2E `38/38`、preview `11/11`、visual `15/15`。
- [x] 6.2 运行 SQLite 功能后端全量 pytest（忽略独立性能文件），并记录通过数、跳过数和警告；运行 `uv run python -m compileall -q src`。本次使用锁定主工作树 venv 等价执行入口，结果 `1525 passed, 182 skipped`，1 条既有 deprecation warning，compileall 通过。
- [x] 6.3 准备专用 `_test` PostgreSQL（本机 Docker 或 `psql`），设置 `FT_TEST_POSTGRES_URL` 与 `FT_REQUIRE_TEST_POSTGRES=1`，运行 PostgreSQL 功能全量测试和独立双后端性能文件并记录结果；本次容器为 `postgres:16-alpine`、`finance_tracker_test`、端口 55432。功能套件 `1731 passed, 2 skipped, 2 failed`，失败为本机既有性能阈值/夹具波动；分类/投资性能单测复跑通过，PostgreSQL 独立性能参数通过，SQLite 独立 100k p95 受本机负载失败（5.664s/9.293s > 5s）。未放宽预算，远程 Linux CI 仍需最终确认。
- [x] 6.4 运行 `openspec validate add-pr-quality-gates --type change --strict`、`openspec validate --all --strict`、`openspec doctor` 和 `git diff --check`，区分本变更结果与既有无关失败；联合 patch 更新 tasks 后需在提交前再执行一次。

## 7. 发布准备：远程 PR 检查与回滚

- [x] 7.1 在提交前确认 `refactor/web` 是基线，最终分支为现有 `feat/cross-platform-experience`，并只选择本变更 tasks 直接相关的文件；无关未跟踪或脏文件不纳入提交、不删除。
- [x] 7.2 完成本变更的单变更验证后，将本变更作为独立逻辑提交加入 `feat/cross-platform-experience`；不把本变更提交到基线 `refactor/web`。跨平台逻辑提交为 `0b770a5`，质量门禁 patch 已按直接文件应用，尚未形成质量提交。
- [ ] 7.3 待 `cross-platform-experience` 也完成并合入同一 feature 分支后，只在合并结果上集中运行联合验收；通过后推送 `feat/cross-platform-experience`，观察 `PR Checks` 的 frontend、backend-sqlite、backend-postgres、backend-performance 四个 job，并记录 commit、run URL、runner、耗时和 artifact 结果。
- [ ] 7.4 若联合远程 job 失败，依据日志修复对应 workflow/测试并重新验证；若需回滚，恢复测试基线和删除新增 workflow，不执行数据库或分支保护变更。

## 8. 反思与交付记录

- [x] 8.1 在本清单记录本地与远程验证证据、最终 `HEAD`、比较基线、执行时间、未解决风险和审查结论；远程 run URL 与失败 finding 已在下方回写。
- [x] 8.2 记录可复用经验：浏览器主动取消必须与真实网络失败区分，视觉基线必须绑定 runner 平台，双后端 CI 必须拒绝静默 skip；Node 26 jsdom 需在测试 setup 显式提供一致的 Storage。
- [ ] 8.3 确认所有实现任务、审查和验证均完成后，按 OpenSpec 规则评估 delta（本变更无 delta）并准备归档；不把归档当作发布授权。

## 远程 PR 证据（2026-09-14，Asia/Shanghai）

- PR：`https://github.com/ssttkkl/finance-tracker/pull/82`；head `feat/cross-platform-experience`，base `refactor/web`；最终 `HEAD` `b9152f1`，比较基线 `fdb766cd02e0eed7f88d7cea960b966c49963f05`。质量工作流 run：`https://github.com/ssttkkl/finance-tracker/actions/runs/34858540652`；Mobile CI run：`https://github.com/ssttkkl/finance-tracker/actions/runs/34858540801`，Android、iOS 和 shared JavaScript checks 通过。
- Frontend (Web)：runner `macos-26-arm64`、macOS `26.6.2`、Chrome for Testing `151.0.7922.34`；共享测试、类型检查、Vitest、构建、E2E、生产预览通过，视觉回归 `3 passed / 12 failed`。失败均为现有 `darwin` 基线与该 runner 之间的文字像素差异（每个失败快照报告 27–128 个阈值像素），诊断 artifact 为 `frontend-playwright-diagnostics`；未通过放宽像素阈值或批量接受未审查基线处理。
- Backend (SQLite)：`1523 passed, 182 skipped, 2 failed`；失败为 `test_large_category_directory_has_constant_query_count[sqlite]`（p95 `408.921767ms` > `250ms`）和 `test_portfolio_query_with_investment_history_meets_p95_budget[sqlite]`（holdings p95 `1484.742273ms` > `1000ms`）。
- Backend (PostgreSQL)：`1729 passed, 2 skipped, 4 failed`；失败为分类过滤 p95 `542.553125ms` > `500ms`、现金投影重建 p95 `10.635443353s` > `10s`，以及 SQLite/PostgreSQL 投资组合查询 holdings p95 `1.410825496s`/`1.472413056s` > `1s`。
- Backend (Performance)：独立 `tests/test_wealth_performance.py` 运行双后端均失败；SQLite cold p95 `9.287514056s` > `5s`，PostgreSQL cold p95 `9.199102055s` > `6.5s`，hot p95 分别约 `128ms`/`147ms`，runner 为 Linux `6.17.0-1022-azure-x86_64`。这些 finding 与本地性能波动方向一致，但尚未有不改变预算语义的修复；因此 7.3、7.4、8.3 保持未完成，不归档 active change。
