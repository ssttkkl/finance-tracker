## Why

PR #122 基于 `315d7104`，`main` 已合入 PR #123 的 Compose Web 计算 Worker 迁移，分支因此冲突且不能直接复跑检查。合并后还暴露两处测试与合并结果不一致：启动兼容性用例仍期待旧的高级别 Console 错误，管理目录用例在弹层开场动画完成前截取选中标记。

## What Changes

- 将最新 `main` 合入 `core-ui-workflows-main`，保留双方合同：Worker 回执校验 + 待确认条目校验、Worker 账本刷新状态 + 新的记账/导入流程、双方新增的账本规格。
- 启动兼容性 E2E 按合入后的已处理分支断言零 Console 错误，并保留原兼容性面板、Sentry 与无应用下载断言。
- 管理目录 E2E 在弹层开场动画稳定后捕获选中标记，并以轮询等待标记颜色一致，不改变选择行为或产品 UI。
- 归档本次合并检查修复记录。

## Capabilities

### New Capabilities

无。

### Modified Capabilities

无。合并后的 `import/local-import-pipeline`、`cash-ledger/cash-ledger-browser`、`workspace/workspace-management` 与 `shared/deployment-diagnostics` 均沿用现有主规格；本次只对齐测试与合并冲突。

## Impact

只影响 PR 分支的合并结果与 Web 浏览器测试等待方式。生产 Worker 计算、账务、权限、数据库和原生端行为不变。
