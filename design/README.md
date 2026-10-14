# 历史 UI 设计资料

本目录仅保留可追溯的旧页面和业务组件设计稿。它们不参与当前界面的实现、设计审批或 QA 对照；当前 Compose 页面与 Material 3 控件实现、FinanceTheme 和 Git token 是唯一维护依据。

## 文件关系

- `finance-ui.lib.pen`：历史 Finance 业务组件稿。
- `login.pen`、`app-shell-entry.pen`、`workspace-entry.pen`、`cash-ledger.pen`、`cash-record.pen`、`cash-import.pen`、`invitation.pen`、`cash-categories.pen`、`investment-holdings.pen`、`investment-events.pen`、`workspace-management.pen`：历史页面设计稿；相关行为与验收证据保留在对应 OpenSpec 归档中。
- `generated/pen-variables.json`：历史 token 消费副本，不由 token 生成器更新，也不由 CI 校验。

## 当前事实源

OpenSpec 定义业务行为与验收；Compose `commonMain` 定义页面结构、Material 3 控件和样式；Git token JSON 是 token 唯一输入，生成器只产出 Compose Kotlin token。历史 `.pen` 文件不参与当前实现、设计审批或 QA 对照。

## Token 生成

```bash
python scripts/generate_ui_tokens.py
```

生成器从 `openspec/specs/shared/design-system/tokens/*.tokens.json` 读取唯一输入，并更新 Compose Kotlin token。当前页面按 OpenSpec 验收、`FinanceTheme` 和 Compose 演示模式完成评审。

## 当前状态

现存资产和此前的用户确认仅记录历史状态。新 UI 变更不要求打开、重建或更新这些 `.pen` 文件；相关过程可从 [`app-shell-entry-presentation`](../openspec/changes/archive/2026-10-12-app-shell-entry-presentation/) 归档中查阅。
