---
name: finance-tracker
category: finance
description: 管理 PostgreSQL 中的个人财务事实，导入银行/支付平台/券商账单，记录现金、转账与投资事件
documentation: README.md, docs/README.md, references/README.md
---

# Finance Tracker

Finance Tracker 只使用 PostgreSQL 作为运行时事实源。执行任何普通命令前确认：

```bash
export FT_DATABASE_URL='postgresql+psycopg://localhost/finance_tracker'
export FT_WORKSPACE_ID='default'
uv run alembic upgrade head
```

不要创建或读取文件账本，不要尝试把旧开发数据迁入数据库。缺少数据库、schema 或 workspace 时应失败
并报告配置问题。

## 命令速查

```bash
# 查询
uv run ft acct list
uv run ft report --month YYYY-MM
uv run ft list --account NAME --limit 30

# 账户与现金
uv run ft acct add NAME --type cash|loan|lend|security|crypto --currency CURRENCY
uv run ft add --amount AMOUNT --counterparty NAME --account ACCOUNT --currency CURRENCY
uv run ft checkin ACCOUNT --balance AMOUNT --currency CURRENCY --date YYYY-MM-DD
uv run ft transfer --from ACCOUNT --from-currency CURRENCY --to ACCOUNT --to-currency CURRENCY --amount AMOUNT [--to-amount AMOUNT]

# 投资
uv run ft stock buy --ticker TICKER --shares QTY --price PRICE --commission FEE --currency CURRENCY --account ACCOUNT
uv run ft stock sell --ticker TICKER --shares QTY --price PRICE --commission FEE --currency CURRENCY --account ACCOUNT
uv run ft stock deposit --amount AMOUNT --currency CURRENCY --account ACCOUNT
uv run ft stock withdraw --amount AMOUNT --currency CURRENCY --account ACCOUNT
uv run ft stock dividend --ticker TICKER --amount AMOUNT --currency CURRENCY --account ACCOUNT
uv run ft stock list
```

## 原始账单导入

```bash
uv run ft import FILE --source SOURCE [--currency CURRENCY] [--password-file FILE]
```

SOURCE 支持 `alipay`、`wechat`、`icbc`、`icbc-debit`、`ccb-debit` 和 `dfzq`。
**禁止** `--account`：每行账户只从账单字段 + `~/.ft/mapping.yaml` 推断（长 match 优先）。
`--currency` 仅作行内缺省币种回退；币种为任意 3 位字母码（如 JPY）。

导入的正确性门禁：

1. 相同 workspace/source/digest 重复执行不产生重复事实；
2. 金额和数量保持 Decimal 文本，拒绝非有限值和超过 18 位小数；
3. 中国账单 naive 时间按 Asia/Shanghai 解释并保存为 UTC；
4. parser、mapping 未匹配（default=error）、账户校验或数据库写入任一失败时整批回滚；
5. 每条 statement-derived fact 必须能追溯到 raw record 和 initial revision；
6. 单次导入可多账户，事实账户以 `fact.account_id` 为准。

## 显式导出

```bash
uv run ft convert FILE --source SOURCE --output preview.csv
uv run ft stock convert FILE --source dfzq --output preview.csv
```

导出只用于检查，账户路由与 import 相同。不要把输出文件当作账本、snapshot 或事务日志；正式写入使用原始
文件执行 `ft import`。

## 财务与数据规则

- 金额、数量和成本使用 `Decimal`/`NUMERIC(38,18)`；展示舍入不得回写事实。
- 有正式事实引用的账户不能硬删除，应停用；账户重命名依靠稳定 ID 保持历史归属。
- repository 在构造时绑定 workspace，调用参数不得覆盖它。
- 投影是可从 PostgreSQL facts 重建的读模型，不是第二事实源。
- 任何修复都应追加 revision 或通过受支持 application command，不直接伪造 projection。
- 行情缺失时展示成本回退或缺口，不伪造市场价格。

## 开发工作流

行为、模型、持久化或财务规则变更必须按仓库 `AGENTS.md` 走 Spec Kit 主流程。完成实现后运行：

```bash
uv run pytest
uv run alembic heads
uv build
git diff --check
```

需要真实 PostgreSQL 证据时设置 `FT_TEST_POSTGRES_URL` 再运行 `tests/test_postgres_live.py`。不要自行提交、
推送、建 PR 或部署，除非用户明确授权。
