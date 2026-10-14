## Why

Compose 已成为默认客户端，Render 也从 `compose/` 目录直接运行 Gradle 构建。根目录 npm 清单增加了不必要的依赖安装步骤，也让仓库的 Python、Gradle 和 Node.js 实际命令不够直观。

## What Changes

- **BREAKING**：删除根目录的 `package.json` 和 `package-lock.json`，不再提供根级 `npm run` 命令。
- 将开发文档中的快捷命令改为直接调用 Python、Gradle 和 Node.js，并明确 Web npm 命令须在 `web/` 目录运行。
- 清理旧客户端文档和仓库 Skill 中失效的根 npm workspace 命令，并明确 Native 代码仅作迁移参考。
- 调整 CI：移除根 npm 安装与缓存；通过 Python 生成 UI token；仅在配置 Pen CLI 密钥时安装固定版本并保留可选校验。
- 调整 Dev Container 初始化脚本，使 npm 安装和 Playwright 浏览器安装使用 `web/` 自己的 npm 项目。

## Capabilities

### New Capabilities

无。本次只调整仓库工具和文档，不改变产品行为。

### Modified Capabilities

无。

## Impact

- 根级开发命令说明：`README.md`、`compose/README.md`、`docs/README.md`、`SKILL.md`。
- 旧 Native 客户端说明：`mobile/README.md`。
- Node.js、token 生成和 Pen CLI 步骤：`.github/workflows/compose-ci.yml`、`.github/workflows/pr-checks.yml`。
- Dev Container 初始化：`.devcontainer/post-create.sh`。
- 运行时版本说明：`design/README.md`。
- 删除根 npm 清单和锁文件。`web/package.json`、`mobile/package.json` 及各自锁文件不变；现有 `render.yaml` 以 `web/` 为根目录，仍使用其独立清单，不在本次范围内。
