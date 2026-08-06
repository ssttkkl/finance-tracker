## Context

见 `proposal.md`。当前 `npm run dev` 只启动 Vite，不注入 `VITE_FT_API_ORIGIN`；即使人工设置为本机 API，前端端口与 API 允许来源不一致时，浏览器仍会阻止响应。Python API、数据库选择和生产预览合同均不在本次范围内。

## Goals / Non-Goals

**Goals:**

- 让 `npm run dev` 在已运行本机 API 的前提下直接提供可读取的收支账本。
- 固定本地开发前端端口为 `5174`，并仅经回环地址转发 API 请求。
- 保持测试和生产预览传入的显式 API 地址优先。

**Non-Goals:**

- 不启动、停止或配置 Python API、数据库或工作区。
- 不改变生产预览、API 路由、CORS 中间件或前端 API 客户端的校验规则。
- 不为远程 API、任意代理目标或自动端口探测提供支持。

## Decisions

### 默认开发脚本注入同源地址

`web/package.json` 的 `dev` 脚本使用 shell 默认值：未传入 `VITE_FT_API_ORIGIN` 时设为 `http://127.0.0.1:5174`，并以 `--port 5174 --strictPort` 启动 Vite。已有调用者传入该变量时保持原值，以保留端到端测试的独立 mock API。

备选方案是提交 `web/.env.local`。该文件只能代表单个开发环境，且不能保证端口与启动命令一致，因此不采用。

### 开发服务器转发同源 API

Vite 将 `/api` 转发到固定的 `http://127.0.0.1:8000`。浏览器请求和前端页面同源，避免依赖 API 的 CORS 允许来源；Vite 与 API 的通信仍限定在回环地址。前端在显式指定其他 `VITE_FT_API_ORIGIN` 时直接请求指定地址，不经过该代理。

备选方案是修改 Python API 的默认 CORS 端口。该方案会改变 API 的运行时合同，且仍要求每次启动前后端时同步两个端口，因此不采用。

## Risks / Trade-offs

- [本机 API 未在 `8000` 运行] → 页面会保留既有可操作错误；开发者可显式设置 `VITE_FT_API_ORIGIN` 使用测试或其他明确指定的本机 API。
- [`5174` 已被占用] → `--strictPort` 让命令明确失败，避免前端地址与注入地址不一致。
- [shell 默认值依赖 POSIX shell] → 仓库现有 Playwright 启动命令已使用同一环境变量赋值方式，目标开发环境为项目规定的本机 shell。

## Migration Plan

1. 先新增启动配置回归测试，证明旧脚本不满足默认端口、同源地址和代理要求。
2. 更新 Vite 配置、开发脚本和 README。
3. 验证默认启动可通过 `5174` 读取本机 `8000` 的账户与账本接口，并验证显式 API 覆盖不受影响。

回滚时恢复 `web/package.json`、`web/vite.config.ts` 和 README；不涉及数据迁移或 API 回滚。
