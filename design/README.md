# 历史 UI 设计资料

本目录保留此前由 pen.dev 生成的 UI 设计资产，供历史审计和迁移背景查阅。当前 UI 页面结构与样式以 Compose 实现为准；新变更不要求生成或更新 Pen 文件。

## 文件关系

- `heroui-reference.pen`：历史 HeroUI Gallery 参考稿。
- `heroui.lib.pen`：历史 HeroUI 组件参考库。
- `finance-ui.lib.pen`：历史 Finance 业务组件稿。
- `heroui-reference.lib.pen`、`finance-design-system.lib.pen`：历史占位资产，不作为当前来源。
- `login.pen`、`app-shell-entry.pen`、`workspace-entry.pen`、`cash-ledger.pen`、`cash-record.pen`、`cash-import.pen`、`invitation.pen`、`cash-categories.pen`、`investment-holdings.pen`、`investment-events.pen`、`workspace-management.pen`：历史页面设计稿；相关旧流程与验收证据保留在对应 OpenSpec 归档中。
- `generated/pen-variables.json`：历史 token 消费副本，不再由 token 生成器更新或由 CI 校验。

当前事实源：OpenSpec 定义业务行为与验收；Compose `commonMain` 定义页面结构和样式；Git token JSON 是 token 唯一输入，生成器只产出 Compose Kotlin token。旧 Pen 文件不参与当前实现、设计审批或 QA 对照。

## Token 生成

```bash
python scripts/generate_ui_tokens.py
```

生成器从 `openspec/specs/shared/design-system/tokens/*.tokens.json` 读取唯一输入，并更新 Compose Kotlin token。当前页面按 OpenSpec 验收、Finance 组件合同和 Compose 演示模式完成评审。

## 当前状态

现存资产和此前的用户确认仅记录历史状态。新 UI 变更不要求打开、重建或更新这些 `.pen` 文件；相关过程可从 [`app-shell-entry-presentation`](../openspec/changes/archive/2026-10-12-app-shell-entry-presentation/) 归档中查阅。
