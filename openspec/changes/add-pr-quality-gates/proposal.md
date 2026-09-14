## Why

当前 `mobile-ci.yml` 已能验证共享 TypeScript 包和 Native 构建，但 Pull Request 还没有稳定的 Web 与 Python 后端质量门禁。最近的本地复现还暴露出一个会被并行浏览器执行放大的 E2E 测试误报，以及一个已落后于当前表格结构的视觉快照；先闭合这些失败项，才能让新增的 PR 检查真正反映代码质量。

## What Changes

- 修复工作区创建页返回账本时对主动取消请求的错误测试判定，保留真实网络失败的检测能力。
- 更新 `1024×768` Web 视觉基线，使快照反映当前收支表格的交易类型、金额和操作列。
- 修复 PostgreSQL 收支投影重建的锁顺序与来源指纹刷新缺陷，并让迁移回归测试停在可逆的迁移边界。
- 新增独立的 `.github/workflows/pr-checks.yml`，在所有 Pull Request 和手动触发时执行前端与后端检查。
- 前端检查覆盖共享包、Web Vitest、TypeScript、生产构建、Playwright E2E、生产预览和视觉回归，并在失败时保留诊断 artifact。
- 后端检查覆盖 SQLite 与 PostgreSQL 功能全量测试，并在独立性能 job 中使用专用 `finance_tracker_test` 数据库完整执行双后端性能门禁，强制开启双后端矩阵且避免性能样本被功能套件负载污染。
- 保持现有 Mobile CI 独立运行，不修改 GitHub 分支保护、Required checks、签名发布或生产部署配置。

## Capabilities

### New Capabilities

本变更只涉及测试稳定性、开发质量门禁和 CI 配置，不改变用户可观察的账本、API、金额、持久化或权限行为；因此不新增产品能力规格。

### Modified Capabilities

无。

## Impact

- **测试**：`web/tests/workspace-entry.e2e.ts` 的请求失败收集逻辑、Web 视觉快照，以及 PostgreSQL 并发/迁移回归测试。
- **后端适配器**：只修复投影构建事务的锁顺序和来源指纹读取一致性，不改变账本/API/金额契约。
- **工作流**：新增 GitHub Actions 前后端 PR 检查；使用 Node 24、Python 3.11、`uv`、Chromium 和 PostgreSQL 服务容器。
- **运行时与数据**：不修改 API、数据库 schema、迁移文件、账本数据或外部服务凭据；CI PostgreSQL 只使用临时的 `_test` 数据库。适配器修复仅影响投影构建事务的并发保护。
- **迁移与回滚**：工作流失败不会影响现有 Mobile CI 或本地入口；回滚时删除新增 workflow、恢复快照和测试辅助逻辑即可，不需要数据库回滚。

## 需求澄清结论

- 检查范围覆盖所有 Pull Request，不设置路径过滤，并提供手动触发；不额外修改分支保护规则。
- 前端使用完整现有 Web 检查作为门禁，视觉回归在基线更新后重新纳入检查。
- 后端同时运行 SQLite 和 PostgreSQL；PostgreSQL 使用 Actions 服务容器，并通过 `FT_TEST_POSTGRES_URL` 与 `FT_REQUIRE_TEST_POSTGRES=1` 显式启用。
- 前后端使用独立的 PR workflow，现有 Mobile CI 保持单独职责和触发方式。
- 两个 OpenSpec 变更分别完成开发和相称的单变更验证后，将本变更的直接相关文件作为独立提交加入现有 `feat/cross-platform-experience`；该分支以 `refactor/web` 为基线，最终 PR 目标为 `refactor/web`。
- 两个 worktree 中与本变更无关的既有脏文件只保留、不纳入提交、不删除；联合验收在两个变更合并到最终 feature 分支后集中执行一次。
