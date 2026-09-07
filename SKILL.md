---
name: finance-tracker
category: finance
description: 管理 PostgreSQL 或 SQLite 中的个人财务事实，导入银行/支付平台/券商账单，记录现金、转账与投资事件
documentation: README.md, docs/README.md, references/README.md
---

# Finance Tracker

Finance Tracker 通过 FastAPI、React/Vite Web 和 Expo Android/iOS 客户端提供财务能力。后端是唯一事实源；客户端不复制余额、投影、估值、关系配对或导入解析规则。

执行任何运行期操作前，必须显式配置数据库并确认 schema：

```bash
export FT_DATABASE_URL='postgresql+psycopg://localhost/finance_tracker'
uv run alembic upgrade head
uv run uvicorn ft.web.app:create_runtime_app --factory --host 127.0.0.1 --port 8000
```

也可使用文件型 SQLite。缺少数据库、schema 或 workspace 时服务必须失败，不得回退、双写或隐式迁移。

## API 与客户端入口

业务请求统一发送 `Authorization: Bearer <session-token>`：

- `/api/v1/auth/*`：登录、注册、workspace 和成员；
- `/api/v1/accounts`：账户管理；
- `/api/v1/cashflow/*`、`/api/v1/cash-records`：现金流水、余额校准和转账；
- `/api/v1/cash-import/*`：账单选择、扫描、映射、预览、关系审查和确认；
- `/api/v1/cash-projections`、`/api/v1/evidence/*`：收支查询和证据；
- `/api/v1/investment-events`、`/api/v1/investment-portfolio`：投资事件与持仓查询；
- `/api/v1/relations/*`、`/api/v1/cash-facts/*`：关系操作和可审计事实删除；
- `/api/v1/operations/*`：仅管理员可执行的同步和投影运维。

Web 使用 `npm run dev:web`，Expo 使用 `npm run start:mobile`、`npm run android` 和 `npm run ios`。共享 API 契约、错误模型、精确十进制字符串和导入会话状态位于 `packages/`；DOM、Native 文件 URI、导航和平台存储留在各自客户端。

## 账单与财务规则

- 现金账单由账单字段和 workspace 映射路由；投资账单确认时选择 `security` 或 `crypto` 账户；预览是导入会话的一部分，不单独生成用户操作步骤。
- 支持 `alipay`、`wechat`、`icbc`、`icbc-debit`、`ccb-debit`、`icbc-asia`、`dfzq`、`ibkr`、`schwab`、`usmart-hk` / `usmart_hk` 等来源。
- 导入幂等权威为 `source_type` × `record_id`；解析、映射、账户校验或数据库写入失败时整批回滚。
- 金额、数量和成本保持 Decimal 文本；HTTP JSON 不接受浮点账务值，拒绝非有限值和超精度输入。
- 中国账单 naive 时间按 Asia/Shanghai 解释并保存为 UTC；每条来源事实都要能追溯到原始记录和初始 revision。
- 有正式事实引用的账户不能硬删除，应停用；投影是可从事实重建的读模型，不是第二事实源。
- 行情缺失时展示成本回退或缺口，不伪造市场价格。

## 凭据和敏感信息

连接器凭据只在后端受控位置加载，客户端和请求体不携带密钥。账单密码只存在当前导入请求，不进入日志、TokenStore、缓存或数据库。Native 短会话 Token 使用 `expo-secure-store`，Web Token 使用浏览器本地存储。

## 开发工作流

行为、模型、持久化或财务规则变更必须按仓库 `AGENTS.md` 走 OpenSpec 主流程（`$openspec-propose` → `$openspec-apply-change` → `$openspec-archive-change`）。完成实现后运行：

```bash
npm run test:web
npm run build:web
npm run test:shared
PYTHONPATH=tests:.:src uv run pytest
uv build
git diff --check
```

双后端测试必须使用专用 `_test` PostgreSQL 数据库；测试脚本会拒绝业务库或其他数据库名。不要自行提交、推送、建 PR 或部署，除非用户明确授权。
