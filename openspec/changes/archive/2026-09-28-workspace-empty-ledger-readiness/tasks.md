## 1. 思考与范围

- [x] 1.1 记录 `grill-me` 技能不可用，并基于现有代码、主规格和截图确认目标、非目标、验收标准与风险。
- [x] 1.2 审查注册、创建/切换工作区、账本查询、首次新建/导入和错误/空状态路径；确认本次只覆盖新建工作区，不保留历史兼容迁移。

## 2. 失败测试先行

- [x] 2.1 为创建工作区后首次读取返回正常空账本增加失败回归测试。
- [x] 2.2 为切换到空工作区的隔离、重复刷新幂等和初始化失败回滚增加测试（初始化在创建事务内执行，重复创建路径受唯一工作区约束保护）。
- [x] 2.3 检查首次新建流水、首次导入路径；本次无需修改，因为新工作区创建后已具备 ready 数据集。

## 3. 实现

- [x] 3.1 在工作区创建事务内初始化并发布零事实活动数据集，保持事务一致性与工作区隔离。
- [x] 3.2 调整 Web 查询，使 ready 但无可见行的数据集返回正常空页；真实未就绪状态仍保留错误和重试。
- [x] 3.3 为工作区切换失败增加前端可见错误提示并保留当前工作区。

## 4. 审查与验证

- [x] 4.1 完成范围、工程、安全和最终 diff 复核；未发现阻断性 finding。
- [x] 4.2 `uv run pytest -q tests/test_user_workspace_access.py tests/test_application_cash_projections.py tests/contract/test_web_api.py` 为 56 passed, 4 skipped；Web `npm test -- --run` 为 101 passed；`npm run build` 通过；`git diff --check`、`openspec validate --all --strict`（24 passed）与 `openspec doctor` 通过。
- [ ] 4.3 `FT_TEST_POSTGRES_URL` 未配置，本轮 PostgreSQL 矩阵未完成。
- [x] 4.4 Playwright 真实浏览器 `FT_E2E_WEB_PORT=5187 npm run test:e2e -- --grep "工作区"` 为 1 passed；覆盖了工作区壳导航。新建空账本真实 API 浏览器流程仍需部署环境或专用 API 预览补跑。
- [ ] 4.5 当前运行时未提供 Hallmark `audit` 技能动作；已完成人工 DOM、文案、焦点和响应式代码复核。仓库是否存在 `hallmark` CLI 不作为技能可用性的判据。

## 5. 发布与反思

- [x] 5.1 记录发布准备、回滚与“不迁移历史未初始化工作区”的范围。
- [x] 5.2 已将新工作区空账本行为写入本变更 delta spec；无新领域术语。
