## 1. 思考

- [x] 1.1 确认线上故障边界：`login` 与 `session` 均返回 200，`worker.js` 与 `ledger-key-vault.mjs` 均加载成功，失败发生在本地账本缓存初始化；浏览器环境为 `secure: true, isolated: false, sab: "undefined", locks: true, opfs: true`。
- [x] 1.2 确认 `_headers` 内容正确但被 Render 当作普通静态文件返回；Render Static Site 的自定义响应头需通过 Dashboard 或 Blueprint `headers` 配置。
- [x] 1.3 读取 `compose/README.md`、`render.yaml`、`compose/build-wasm-web.sh` 与 Render Blueprint 文档，确认构建参数、发布目录和服务接管语义。

## 2. 计划

- [x] 2.1 用户确认：基于 `origin/main` 新建分支；服务名为 `finance-tracker-web`；后端地址写死 `https://finance-tracker-4gxf.onrender.com`；只校准 `render.yaml`。
- [x] 2.2 记录目标、非目标、风险与回滚：见 `proposal.md` 与 `design.md`。

## 3. 任务拆分

- [x] 3.1 建立轻量 change，使用 `skip_specs: true`，因为本次不改变能力行为、HTTP 合同或财务语义。
- [x] 3.2 仅修改 `render.yaml`：新增静态站点、两个隔离头、构建期 `FT_API_ORIGIN`，保留后端服务不变。

## 4. 构建

- [x] 4.1 在 `fix/render-static-site-headers` 分支写入 Compose Web 静态站点定义。
- [x] 4.2 写入 `headers`：`Cross-Origin-Opener-Policy: same-origin`、`Cross-Origin-Embedder-Policy: require-corp`。
- [x] 4.3 写入 `FT_API_ORIGIN=https://finance-tracker-4gxf.onrender.com`。

## 5. 审查

- [x] 5.1 复核 `render.yaml` 与 `compose/README.md` 的构建参数一致：`rootDir: compose`、`buildCommand: ./build-wasm-web.sh`、`staticPublishPath: webApp/build/dist/wasmJs/productionExecutable`；`staticPublishPath` 按 Render 规则相对 `rootDir`。
- [x] 5.2 复核服务名与用户确认值一致：`finance-tracker-web`；用户提供的 Render Dashboard 截图确认 Service ID `srv-d9uiu1qjobas73au3tig`、URL `https://finance-tracker-web-ozhr.onrender.com/`、跟踪 `main` 的 `ca9490c`。`-ozhr` 只是 URL 后缀；已在 `proposal.md` 记录接管风险。
- [x] 5.3 复核 diff：仅 `render.yaml` 与本 change 的 OpenSpec 记录，不涉及运行时代码。

## 6. 测试与 QA

- [x] 6.1 YAML 解析通过：`ruby -ryaml -e ...` 读出两个服务，静态站点 headers 与 `FT_API_ORIGIN` 值正确。
- [x] 6.2 `openspec validate --all --strict` 通过（31 items，0 failed）；`openspec doctor` 通过；`openspec validate fix-render-static-site-headers --strict` 通过。
- [x] 6.3 `git diff --check` 通过。
- [x] 6.4 Web 浏览器 QA、双后端矩阵、Native 检查不适用：本次只改部署配置，不改变应用代码、UI、存储或 HTTP 行为。

## 7. 发布

- [x] 7.1 记录部署步骤与验证命令：Blueprint 同步后检查 `curl -sSI .../ | grep -i cross-origin`，并在浏览器确认 `crossOriginIsolated === true`、`typeof SharedArrayBuffer === "function"`，登录后确认 `/api/v1/ledger/version` 与 `/api/v1/ledger/snapshot` 请求出现。
- [ ] 7.2 实际 Blueprint 同步、Dashboard 修改和线上验证需要用户单独授权并执行；本次未提交、未推送、未部署。
- [x] 7.3 记录回滚方式：恢复 `render.yaml` 上一版本并重新同步；Dashboard 响应头规则可独立移除。

## 8. 反思

- [x] 8.1 记录防复发项：`compose/README.md` 中“`_headers` 供 Render 采用”的表述需要后续文档修正；部署后 smoke 应断言 `crossOriginIsolated === true`。

验证证据：

- 分支：`fix/render-static-site-headers`；`HEAD` 与比较基线 `origin/main` 均为 `e2a16c7f5b7e4a75fd37b6edc61638ccc4264f95`。
- 2026-11-19：YAML 解析通过；`openspec validate --all --strict` 31/31、`openspec doctor` 通过；`git diff --check` 通过。
- 最终 diff：`render.yaml` 新增 19 行；未改运行时代码。
- 2026-11-19：用户提供 Render Dashboard 截图，确认 Service Name `finance-tracker-web`、Service ID `srv-d9uiu1qjobas73au3tig`、URL `https://finance-tracker-web-ozhr.onrender.com/`、跟踪 `main`、部署 commit `ca9490c`；`-ozhr` 仅为 URL 后缀。
- 未执行项：Blueprint 同步、Dashboard 修改、线上 `curl` 与浏览器验证，均需要用户授权与外部写操作，未在本次仓库改动中声称完成。
