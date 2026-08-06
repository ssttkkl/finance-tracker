## Why

本地开发前端必须手动设置 `VITE_FT_API_ORIGIN`，并让前端端口与 API 的 CORS 来源保持一致。漏配任一项时，收支账本虽然可以打开，却不能读取账户目录或账本数据。

## What Changes

- 将 `npm run dev` 固定为本机 `5174` 端口，并默认注入该同源 API 地址。
- 让 Vite 仅将同源 `/api` 请求转发到默认的本机 API `http://127.0.0.1:8000`，避免浏览器 CORS 端口不匹配。
- 保留显式 `VITE_FT_API_ORIGIN` 覆盖，以支持端到端测试、生产预览和其他明确指定的本机 API。
- 更新本地 Web 启动文档，并增加启动配置回归测试。

## Capabilities

### New Capabilities

无。本次只调整本地开发工具链，不改变产品行为。

### Modified Capabilities

无。本次使用 `skip_specs: true`，不修改 OpenSpec 主规格。

## Impact

- 受影响文件：`web/package.json`、`web/vite.config.ts`、`web/tests/runtime.test.tsx`、`README.md`。
- 不修改账本、持久化、API 路由、CORS 合同、生产构建或依赖。
- 回滚时恢复前端开发脚本、Vite 代理和文档；不涉及数据回滚。
