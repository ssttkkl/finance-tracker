## Why

工作流 YAML 修复合并后，GitHub Actions 已能创建任务，但又暴露出后续失败：Compose 发布检查从错误目录调用 Gradle wrapper；后端测试仍断言已退役的 Expo CI、旧迁移错误信息和过时的文档用语。这些失败阻止当前 `main` 通过 CI。

## What Changes

- 从仓库根目录通过实际路径调用 Compose Gradle wrapper。
- 用当前 Compose CI 和旧版 Native 边界断言替换已退役 Expo 打包断言。
- 让迁移与运行文档测试匹配当前行为。

## Capabilities

### New Capabilities

无。

### Modified Capabilities

无。本变更只维护 CI 和测试，不改变产品行为或能力要求。`.openspec.yaml` 设置了 `skip_specs: true`。

## Impact

- 影响 `.github/workflows/compose-ci.yml`、后端测试断言和本 OpenSpec 记录。
- 不改应用逻辑、API、依赖、迁移或用户可见行为。回退这些工作流、测试和 OpenSpec 改动即可恢复原状态。
