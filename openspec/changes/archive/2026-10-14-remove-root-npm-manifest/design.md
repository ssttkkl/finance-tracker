## Context

动机和范围见 `proposal.md`。当前根清单只提供快捷脚本并固定 `@pen.dev/cli`；token 生成器仅使用 Python 标准库。Compose 构建使用 Gradle Wrapper；只有配置 `PEN_CLI_KEY` 时，CI 才需要 Node.js 和 Pen CLI。

## Goals / Non-Goals

**Goals：**

- 根目录开发说明直接调用 Python、Gradle 和 Node.js。
- 根 npm 清单与锁文件删除后，Compose CI 仍能生成 token，并保留可选的 Pen 校验。
- 分别说明 Compose 预览和 Pen CLI 的 Node.js 版本要求。

**Non-Goals：**

- 删除仓库对 Node.js 的使用；Compose 预览和可选 Pen 工具仍会使用 Node.js。
- 修改 `web/` 或 `mobile/` 的 npm 清单、锁文件和依赖，或修改现有以 `web/` 为根目录的 `render.yaml` 服务。
- 改变应用行为或仓库外配置的 Compose Render 服务。
- 让旧 Native 客户端恢复为受支持的安装、构建或发布工作流；本次只移除文档中的失效根级命令。
- 修改 `web/` 或 `mobile/` 的 npm 清单、锁文件和依赖；只从仓库根目录显式进入 `web/` 调用其现有 npm 项目。

## Decisions

1. **同时删除根清单和锁文件。** `package-lock.json` 对应根 `package.json`；单独保留会造成无效且易误导的文件。`web/` 和 `mobile/` 自己维护的 npm 文件保持不变。
2. **文档改用直接命令。** token 生成使用 `python3 scripts/generate_ui_tokens.py`；Gradle 命令通过 `compose/gradlew` 执行；Compose 本地预览使用 `node web/scripts/start-compose-demo.mjs`。只移除命令别名，不改变实际构建和预览流程。
3. **CI 移除 npm 安装与锁文件缓存。** 保留工作流使用的固定 Node.js 运行时，token 生成改为 Python 命令。Compose CI 仅在配置 `PEN_CLI_KEY` 时全局安装固定版本 `@pen.dev/cli@0.3.9`，随后直接运行 `pen`。这保留可选校验，同时不依赖根清单。
4. **不改 `render.yaml`。** 它显式使用 `rootDir: web`，该项目有独立清单和锁文件；用户配置的 Compose Render 服务使用 `compose/` 根目录和 Gradle。
5. **Dev Container 使用 Web 子项目。** 初始化脚本在 `web/` 内执行 `npm ci` 和 Playwright 安装，避免依赖已删除的根 npm workspace。

## Risks / Trade-offs

- [根级 `npm run` 快捷命令消失] → 将活跃 README 引用替换成等价直接命令，并搜索遗留引用。
- [可选 Pen CLI 安装会在 CI 运行时解析其传递依赖] → 固定 CLI 到确切版本 `0.3.9`，且仅在已有密钥时安装。
- [CI 可能仍需要 Node.js 支持 Kotlin/Wasm 工具链] → 保留 Node.js 环境设置，只移除 npm 依赖安装与基于锁文件的缓存。
- [Dev Container 和文档索引可能仍从根目录调用 npm] → 初始化脚本显式在 `web/` 执行 npm 命令，文档明确生产预览命令所在目录，并在审查时搜索根清单引用。
- [旧 Native 文档可能暗示根 workspace 仍受支持] → 将 `mobile/README.md` 标为迁移参考，移除已失效的安装、启动、测试和 CI 命令；不改 Native 源码或依赖清单。

## Migration Plan

更新活跃工作流和文档，删除根清单与锁文件，然后校验 OpenSpec 和最终差异。回滚时恢复这两个文件，并撤销对应 CI 与文档变更。不涉及数据库、用户数据或线上服务迁移。
