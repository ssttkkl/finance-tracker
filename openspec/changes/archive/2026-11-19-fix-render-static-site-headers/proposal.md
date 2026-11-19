## Why

线上 Compose Web 静态站点未发送 `Cross-Origin-Opener-Policy` 与 `Cross-Origin-Embedder-Policy`，页面因此不是跨源隔离环境，`SharedArrayBuffer` 不可用；登录请求成功后，本地加密账本缓存的 Worker 在打开数据库时失败，用户看到“发生未知错误，请稍后重试”。

仓库中的 `_headers` 文件是 Netlify/Cloudflare Pages 约定，Render Static Site 不读取它。Render 的自定义响应头必须配置在 Dashboard 或 Blueprint 的 `headers` 字段中。当前 `render.yaml` 仍停留在旧 React 站点配置，没有声明 Compose Web 静态站点，也没有这两个响应头。

## What Changes

- 将 `render.yaml` 校准到当前 Compose Web 部署：移除旧 React 静态站点配置，新增 `finance-tracker-web` 静态站点。
- 静态站点的构建参数与 `compose/README.md` 的 Render 说明一致：`rootDir: compose`、`buildCommand: ./build-wasm-web.sh`、`staticPublishPath: webApp/build/dist/wasmJs/productionExecutable`。
- 为新静态站点配置 Blueprint `headers`，对 `/*` 发送：
  - `Cross-Origin-Opener-Policy: same-origin`
  - `Cross-Origin-Embedder-Policy: require-corp`
- 在静态站点构建环境注入 `FT_API_ORIGIN=https://finance-tracker-4gxf.onrender.com`，确保 Wasm 产物请求已部署的后端，而不是构建机本地地址。
- 保留现有 `finance-tracker-api` 服务定义不变。

非目标：不改前端或后端运行时代码；不改后端环境变量；不调整 Dashboard 中已有的其它响应头或路由规则；不提交、不推送、不触发 Render 部署。

## Capabilities

### New Capabilities

无。

### Modified Capabilities

无。本次仅校准部署配置，不改变任何产品行为、财务语义、HTTP 合同或用户可见流程。

## Impact

- 仅修改 `render.yaml`；新增一个 OpenSpec 配置变更记录。
- Render 会按服务名接管已有 `finance-tracker-web` 服务。用户已通过 Render Dashboard 确认：Service Name 为 `finance-tracker-web`，Service ID 为 `srv-d9uiu1qjobas73au3tig`，公开 URL 为 `https://finance-tracker-web-ozhr.onrender.com/`，跟踪 `main` 的 `ca9490c`。`-ozhr` 只是 URL 唯一后缀，不是服务名；Blueprint 同步时应接管该服务而不是新建。
- `FT_API_ORIGIN` 是公开构建期配置，写死为当前后端 origin；后端域名变化时必须同步修改，否则前端会请求旧地址。
- 回滚：恢复 `render.yaml` 上一版本并重新同步 Blueprint；Dashboard 中的响应头规则可独立移除。
- 部署后的验证不在本次仓库改动内：Blueprint 同步或重新部署后，用 `curl -I` 确认两个响应头，并在浏览器确认 `crossOriginIsolated === true`、`typeof SharedArrayBuffer === "function"`。
