## Context

Compose Web 的本地加密账本缓存依赖 OPFS、Web Locks 和 `SharedArrayBuffer`。`ledger-storage/worker.js` 在打开数据库前检查：

```js
if (!self.crossOriginIsolated || !self.SharedArrayBuffer) {
  throw new Error("ledger_cache_unavailable");
}
```

线上站点 `https://finance-tracker-web-ozhr.onrender.com/` 的文档响应没有 `Cross-Origin-Opener-Policy` 和 `Cross-Origin-Embedder-Policy`，浏览器返回：

```js
{ secure: true, isolated: false, sab: "undefined", locks: true, opfs: true }
```

登录接口与 `session` 接口均返回 200，失败发生在随后的本地缓存初始化。站点上的 `/_headers` 文件内容正确，但被 Render 当作普通静态文件返回，说明 Render Static Site 不读取该约定。

## Decision

在 `render.yaml` 中声明 Compose Web 静态站点，并使用 Render Blueprint 的 `headers` 字段发送两个隔离头，而不是依赖 `_headers` 文件。

站点参数取自 `compose/README.md` 的 Render 部署说明：

- `rootDir: compose`
- `buildCommand: ./build-wasm-web.sh`
- `staticPublishPath: webApp/build/dist/wasmJs/productionExecutable`

`staticPublishPath` 相对于 `rootDir`，因此不写 `compose/` 前缀。

`FT_API_ORIGIN` 是 BuildKonfig 的构建期输入，必须出现在静态站点的 `envVars` 中。当前后端公开 origin 为 `https://finance-tracker-4gxf.onrender.com`，由用户确认写死；该值不包含 `/api`、路径或查询参数。

保留 `finance-tracker-api` 服务定义不变，避免把响应头修复扩大成后端配置变更。

## Risks

- **服务接管风险**：Render 按 `name` 匹配已有服务。用户确认服务名为 `finance-tracker-web`；同步前应在 Dashboard 核对，避免新建重复站点。
- **构建期配置漂移**：`FT_API_ORIGIN` 写死；后端域名变化时前端会继续请求旧 origin，需要在同一变更中更新。
- **缓存延迟**：Render CDN 当前对文档响应使用 `s-maxage=300`；Blueprint 同步后旧响应可能短暂缓存，验证时使用无痕窗口或等待缓存过期。
- **路由规则**：本次不声明 `routes`，Render 会保留已有路由规则；Compose Web 当前不依赖路径路由，根路径即可加载。
- **`_headers` 保留**：文件仍可用于 Netlify/Cloudflare Pages 等托管方式；本次不删除，避免破坏其它部署路径，但需要在文档中说明 Render 不读取它。

## Validation

- 解析 `render.yaml`，确认 YAML 合法且服务字段完整。
- 运行 `openspec validate --all --strict` 和 `openspec doctor`。
- 运行 `git diff --check`。
- 部署后（不在仓库内执行）：
  - `curl -sSI https://finance-tracker-web-ozhr.onrender.com/ | grep -i cross-origin` 返回两个头；
  - 浏览器 `crossOriginIsolated === true` 且 `typeof SharedArrayBuffer === "function"`；
  - 登录后出现 `/api/v1/ledger/version` 与 `/api/v1/ledger/snapshot` 请求并进入账本页。
