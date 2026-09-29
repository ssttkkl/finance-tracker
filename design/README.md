# pen.dev 设计工作区

本目录保存由 pen.dev 官方 CLI、Desktop 或 IDE MCP 生成的设计文件。

## 文件关系

- `finance-design-system.lib.pen`：组件和变量库。
- `login.pen`：登录/注册页首个设计样板。
- `workspace-entry.pen`、`cash-ledger.pen`、`cash-record.pen`、`cash-import.pen`、`invitation.pen`、`cash-categories.pen`、`investment-holdings.pen`、`investment-events.pen`、`workspace-management.pen`：其余页面的 Compact、Regular、Wide 设计稿。
- `generated/pen-variables.json`：由 `scripts/generate_ui_tokens.py` 生成的变量同步清单，不是 `.pen` 文件。

OpenSpec 定义业务行为与验收，`.pen` 维护页面结构、表单顺序、反馈位置、组件语义、无障碍设计和状态画面，Compose `commonMain` 是运行实现。不再维护重复的页面、模式和组件 YAML。token JSON 位于 `openspec/specs/shared/design-system/tokens/`，仍是现有生成器的唯一输入；`.pen` 变量与生成清单是消费副本。不要直接编辑 `.pen` 的内部 JSON、节点 ID 或引用关系。

## 本地 bootstrap

pen.dev CLI 需要 Node.js 22.19+；其余仓库 Node 工作流仍以根 `package.json` 声明的 20.19+ 为准。

```bash
npm install --global @pen.dev/cli
pen version
pen login
pen status
```

在 pen.dev Desktop 或 IDE 中启用 Codex MCP 后，打开本仓库的 `finance-design-system.lib.pen` 和 `login.pen`。CLI 的 MCP shell 可用于无头编辑：

```bash
pen interactive -i design/login.pen -o /tmp/login.updated.pen
```

CI 使用组织范围的 `PEN_CLI_KEY`；不要提交 `~/.pencil/session-cli.json`、CLI key 或模型 API key。

## Token 同步

```bash
python scripts/generate_ui_tokens.py
```

生成器会更新 Compose Kotlin token 和 `generated/pen-variables.json`。变量写入 `.pen` 必须继续通过 pen.dev CLI/MCP 完成。

## 当前状态

本仓库已安装并核验 `pen 0.3.9`，并通过官方 interactive MCP 生成并保存组件库、登录页和其余 9 个页面设计稿。当前页面稿以 Web Compose 生产实现的布局和元素为基准，采用专业金融工作台方向：Cobalt 强调色、低装饰 UI、中高数据密度、少量标题/说明文字、图标优先的操作区；每个新增页面包含 Compact、Regular、Wide 画布，登录页另有完整状态画布。最终 PNG 位于 `openspec/changes/compose-multiplatform-client/screenshots/pen-web/final/`，并与同目录变更下的 Web 基准截图完成视觉对比。CI 使用 `PEN_CLI_KEY` 时执行 `pen status`、结构文件检查和 PNG 导出；未配置 key 时仅记录非阻断跳过原因。
