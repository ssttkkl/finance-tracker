# pen.dev 设计工作区

本目录保存由 pen.dev 官方 CLI、Desktop 或 IDE MCP 生成的设计文件。

## 文件关系

- `finance-design-system.lib.pen`：组件和变量库。
- `login.pen`：登录/注册页首个设计样板。
- `generated/pen-variables.json`：由 `scripts/generate_ui_tokens.py` 生成的变量同步清单，不是 `.pen` 文件。

`ui-spec/` 是语义合同和 token 事实源，`.pen` 只表达视觉层级、组件组合和状态排布；Compose `commonMain` 才是运行实现。不要直接编辑 `.pen` 的内部 JSON、节点 ID 或引用关系。

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

本仓库已安装并核验 `pen 0.3.9`，并通过官方 interactive MCP 生成并保存 `finance-design-system.lib.pen` 与 `login.pen`。`login.pen` 包含登录正常、注册正常、校验错误、服务错误、提交中/禁用以及 compact、regular、wide、light、dark 画布状态。CI 使用 `PEN_CLI_KEY` 时执行 `pen status`、结构文件检查和 PNG 导出；未配置 key 时仅记录非阻断跳过原因。
