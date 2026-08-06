## 1. 思考

- [x] 1.1 确认当前 `npm run dev` 未注入 API 地址，且 `5174` 与 API 默认 CORS 来源不一致；界定为不改运行时 API 的 B 类工具链变更。

## 2. 计划

- [x] 2.1 确定固定 `5174`、同源 `/api` 代理至本机 `8000`、保留显式 `VITE_FT_API_ORIGIN` 覆盖的方案，并记录替代方案、风险和回滚方式。

## 3. 任务拆分与一致性

- [x] 3.1 先在 `web/tests/runtime.test.tsx` 添加默认开发脚本与 Vite API 代理的失败回归测试。

## 4. 构建

- [x] 4.1 更新 Vite 开发服务器代理，使同源 `/api` 仅转发到 `http://127.0.0.1:8000`。
- [x] 4.2 更新 `npm run dev`，默认使用 `5174`、严格端口和同源 API 地址，同时允许显式 API 覆盖。
- [x] 4.3 更新 README 的本地 Web 启动说明，区分默认开发启动与显式生产预览配置。

## 5. 审查

- [x] 5.1 复核端口、回环地址边界、测试 API 覆盖和文档一致性；UI 审计不适用，因为不修改用户可见界面或交互。

## 6. 测试与 QA

- [x] 6.1 运行新增回归、完整 Vitest、生产构建、默认开发服务器与代理接口探测、`git diff --check`、OpenSpec 严格校验和 `openspec doctor`。

## 7. 发布

- [x] 7.1 记录交付证据、回滚方式和未执行提交、推送、PR 或部署的原因。

## 8. 反思

- [x] 8.1 记录默认启动覆盖的防复发场景及不支持自动启动 API 的边界。

## 审查与验证记录

- 基线与当前 `HEAD`：`e119eec`。本次只修改本地 Node 开发服务器、启动脚本、运行时测试和说明文档；不触及账本、数据库、Python API、CORS 中间件、依赖或生产 API 合同，因此数据库双后端矩阵、性能和安全专项检查不适用。
- 测试先行：实现前执行 `npm test -- --run tests/runtime.test.tsx`，新增「本地开发默认使用同源 API 代理」场景失败，原因为 `dev` 脚本没有默认 `VITE_FT_API_ORIGIN`；实现后同一命令通过。
- 范围复核：复核 `README.md`、`web/package.json`、`web/vite.config.ts` 与 `web/tests/runtime.test.tsx`。代理只处理 `/api` 并指向 `http://127.0.0.1:8000`；Vite 继续仅绑定 `127.0.0.1`；显式 `VITE_FT_API_ORIGIN=http://127.0.0.1:8765` 启动的 `5175` 实例实际注入了该覆盖值。无 critical、major 或 minor finding。
- UI 审计：不适用。本次不改变页面、视觉层级、交互、响应式样式或用户可见文案，故不运行 Hallmark audit。
- 自动化验证：`npm test` 通过，4 个测试文件共 37 条测试通过；`npm run build` 通过；`FT_PREVIEW_WEB_PORT=5179 npm run test:preview` 通过，生产预览的 1 条端到端测试通过。
- 运行时验证：不设置 `VITE_FT_API_ORIGIN` 直接执行 `npm run dev` 后，`http://127.0.0.1:5174` 可访问；经该地址读取账户目录和收支账本接口均返回 HTTP 200 且响应包含 `items`。工作区未遗留临时 Vite 配置。
- 规格与静态检查：`openspec validate fix-local-web-dev-startup --strict`、`openspec validate --all --strict`（30 项）和 `openspec doctor` 通过；`git diff --check` 通过。
- 发布准备：未执行提交、推送、创建 PR、部署或其他外部写入，因为未获得相应授权。回滚只需恢复 `web/package.json`、`web/vite.config.ts` 和 README；无需数据回滚。
- 反思：默认开发路径已覆盖固定端口、同源 API 地址和代理三个容易漏配的条件；Python API 仍须由使用者以有效数据库配置独立启动，故不自动启动 API 或猜测数据库、工作区配置。

## 归档记录

- 2026-08-06：已归档至 `openspec/changes/archive/2026-08-06-fix-local-web-dev-startup/`。本变更使用 `skip_specs: true`，无主规格同步动作。
