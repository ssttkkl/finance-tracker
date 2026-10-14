# Finance Tracker Native

`mobile/` 保存旧版 Expo Android/iOS 客户端，当前只作为迁移参考，不属于默认开发、构建、测试或发布工作流。仓库不再提供根级 npm workspace，当前也没有 Native 依赖链接和 CI 设置；本文不提供旧版 Native 客户端的安装、启动或校验命令。

## Native 路由

```text
src/app/
  index.tsx              # 会话守卫
  (auth)/login.tsx       # 登录 / 注册
  (app)/workspace.tsx    # 工作区选择
  (app)/ledger.tsx       # 收支账本
  (app)/record.tsx       # 手工记账
  (app)/import.tsx       # 账单导入与关系审查
```

跨平台 API 契约、请求客户端、导入状态和设计 token 位于 `../packages/`。Native 专属的 SecureStore、文件来源、导航和 `StyleSheet` 留在本目录。

## 迁移边界

恢复 Native 安装或构建流程前，应在独立 OpenSpec 变更中重新确认本地包依赖、构建入口、签名要求和验证流程。Compose Multiplatform 是当前默认客户端；其构建命令见根目录 [`README.md`](../README.md)。
