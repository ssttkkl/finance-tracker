## 1. 思考

- [x] 1.1 检查 PR #92 合并后的 Actions 日志、当前工作流、失败断言和主分支状态；`grill-me` 调用入口不可用，已依据现有故障证据限定修复范围。
- [x] 1.2 定级为 B 类 CI/测试维护；确认不改产品能力、数据行为和依赖。

## 2. 计划

- [x] 2.1 记录 `skip_specs: true`、影响范围、实现选择和回滚方式。
- [x] 2.2 跨端影响检查：Web、Native 和共享 presentation 均不受影响，因为只改 CI 配置和测试；无需更新功能地图。

## 3. 任务拆分与一致性

- [x] 3.1 先增加 Compose wrapper 路径回归断言，并更新过时的迁移、文档和旧版 Native CI 断言。
- [x] 3.2 确认 GitHub Actions 的 SQLite/PostgreSQL 命令均运行 `PYTHONPATH=tests:.:src uv run pytest -q -m "not performance"`；PostgreSQL 使用专用 `finance_tracker_test` 服务库；Compose CI 运行 Web、Android 与 iOS 检查。

## 4. 构建与实现

- [x] 4.1 运行新增工作流断言并确认它在修复前失败（1 failed）；更新当前行为对应的后端断言。
- [x] 4.2 修正 Compose Android 发布检查调用 Gradle wrapper 的路径。

## 5. 审查

- [x] 5.1 手工复核 diff：仅修改 CI、测试及本变更记录；替换断言覆盖当前 Compose wrapper、旧版 Native 退役边界、测试签名边界、迁移回滚信息和运行文档用语。
- [x] 5.2 范围审查未发现阻断问题；保留旧版测试签名凭据断言，删除的只是不再适用的 Expo 打包流程断言。

## 6. 测试与 QA

- [x] 6.1 `uv run pytest -q tests/test_ci_workflow_contracts.py tests/test_alembic_migration.py tests/test_runtime_docs.py`：21 passed，2 skipped（未配置本地 PostgreSQL）。
- [x] 6.2 `uv run python -m compileall -q src` 与 `PYTHONPATH=tests:.:src uv run pytest -q -m "not performance"`：通过；1491 passed，163 skipped，50 deselected，5 subtests passed。SQLite 全矩阵通过；专用 PostgreSQL 与 Compose 全流程留待 PR Actions 验证。
- [ ] 6.3 运行 Compose 工作流所覆盖的 Android/Web/iOS 检查，或记录远端 Action run 证据。
- [x] 6.4 `openspec validate --all --strict`（33 项通过）、`openspec doctor` 与 `git diff --check` 均通过。本地验证基线为 `a3d72e9b1eccb18b65b05617338c214d3fa17f62`，完成时间为 2026-10-20 09:57 UTC；远端 Actions 尚待新 PR 验证。

## 7. 发布

- [ ] 7.1 提交并推送到新分支，创建目标为 `main` 的后续 PR。
- [ ] 7.2 持续处理失败，直到最终 PR 提交的所有必需 checks 通过；只在通过后正常合并。

## 8. 反思

- [ ] 8.1 记录最终 PR、合并提交、验证命令、远端 run、`HEAD`、基线、执行时间和未解决风险；归档本变更。
