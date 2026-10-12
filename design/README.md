# pen.dev 设计工作区

本目录保存由 pen.dev 官方 CLI、Desktop 或 IDE MCP 生成的设计文件。

## 文件关系

- `heroui-reference.pen`：从 HeroUI Gallery 真实 DOM Browser Import 得到的可编辑参考稿，保留来源 `context` 和官方主题变量。
- `heroui.lib.pen`：由参考稿清理并组件化的 HeroUI 可复用组件库。
- `finance-ui.lib.pen`：基于 HeroUI 组件和语义变量派生的 Finance 业务组件库。
- `heroui-reference.lib.pen`、`finance-design-system.lib.pen`：历史占位资产，不作为当前来源。
- `login.pen`：登录/注册页首个设计样板。
- `app-shell-entry.pen`：Compose 登录式工作区/邀请入口及已登录 Scaffold 标题层级设计稿；实现映射见 `openspec/changes/archive/2026-10-12-app-shell-entry-presentation/design.md`。
- `workspace-entry.pen`、`cash-ledger.pen`、`cash-record.pen`、`cash-import.pen`、`invitation.pen`、`cash-categories.pen`、`investment-holdings.pen`、`investment-events.pen`、`workspace-management.pen`：其余页面的 Compact、Regular、Wide 设计稿。
- `generated/pen-variables.json`：由 `scripts/generate_ui_tokens.py` 生成的变量同步清单，不是 `.pen` 文件。CLI 导出的预览图片与 PDF 属于临时产物，不提交到仓库；需要审查时写入 `/tmp` 或对应变更的测试结果目录。

HeroUI 官方代码、`@heroui/styles` 和 CSS variables 是视觉与 token 的上游事实源；OpenSpec 定义业务行为与验收，`.pen` 维护页面结构、表单顺序、反馈位置、组件语义、无障碍设计和状态画面，Compose `commonMain` 是运行实现。不再维护重复的页面、模式和组件 YAML。token JSON 位于 `openspec/specs/shared/design-system/tokens/`，仍是项目 token 生成器的唯一输入；`.pen` 变量与生成清单是消费副本。不要直接编辑 `.pen` 的内部 JSON、节点 ID 或引用关系。

## 本地 bootstrap

pen.dev CLI 需要 Node.js 22.19+；其余仓库 Node 工作流仍以根 `package.json` 声明的 20.19+ 为准。

```bash
npm install --global @pen.dev/cli
pen version
pen login
pen status
```

在 pen.dev Desktop 或 IDE 中启用 Codex MCP 后，打开本仓库的 `finance-ui.lib.pen` 和 `cash-ledger.pen`。CLI 的 MCP shell 可用于无头编辑：

```bash
pen interactive -i design/login.pen -o /tmp/login.updated.pen
```

CI 使用组织范围的 `PEN_CLI_KEY`；不要提交 `~/.pencil/session-cli.json`、CLI key 或模型 API key。

## Token 与 HeroUI 参考同步

```bash
python scripts/generate_ui_tokens.py
```

生成器会更新 Compose Kotlin token 和 `generated/pen-variables.json`。变量写入 `.pen` 必须继续通过 pen.dev CLI/MCP 完成。

HeroUI CSS 变量用于校准 semantic role、主题值和状态命名。组件参考优先来自临时 Gallery 的真实 DOM Browser Import；Figma Kit 只用于视觉核对，整包导入不作为默认流程。

Gallery 使用独立入口，避免业务应用的全局样式覆盖官方组件；本地运行 `cd web && npm run dev` 后打开 `/design-system/`。样式来自已安装的 `@heroui/styles` 预编译 CSS，保留官方 token 与组件状态；其页面排版仅用于逐项查看和导入，不作为 Finance UI 的页面设计。

## 当前状态

本变更已通过官方 interactive CLI 生成并保存 `heroui-reference.pen`、`heroui.lib.pen`、`finance-ui.lib.pen` 和 F-01 至 F-10 的页面稿。持仓、投资事件表格是 `finance-ui.lib.pen` 中的可复用组件，页面通过库实例引用；重建命令见 `tools/pen/build-finance-tables.mjs` 与 `tools/pen/rebuild-web-pages.mjs`。`app-shell-entry.pen` 与独立 HTML 原型已通过用户确认，Compose 实现与最终审查证据记录在 `openspec/changes/archive/2026-10-12-app-shell-entry-presentation/tasks.md`。
