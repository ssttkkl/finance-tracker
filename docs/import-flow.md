# 原始账单导入、关系与同步

可执行行为以 `specs/` 与代码为准。本文描述 **015 之后** 的运行时语义：无文件 reconcile、无 converted-CSV `append`、无独立 import/raw 作业表。

## 命令

### 一步导入（主路径）

```bash
# 现金：禁止 --account；账户由账单字段 + ~/.ft/mapping.yaml 推断
ft import FILE --source alipay|wechat|icbc|icbc-debit|ccb-debit \
  [--currency CURRENCY] [--password-file FILE]

# 投资：必须 --account（security 或 crypto）
ft import FILE --source dfzq|ibkr|schwab|usmart-hk \
  --account NAME [--currency CURRENCY] [--password-file FILE]
```

- `--currency` 可选：仅当行内缺省币种时回退；任意 3 位字母码，归一大写。
- 密码只允许 `--password-file`（不写 argv）。
- 同一文件可路由到多个现金账户；整批在一个 UnitOfWork 内提交。

### 解析预览（非账本）

```bash
ft convert FILE --source alipay -o preview.csv
ft stock convert FILE --source dfzq -o preview.csv
```

导出不被读取为账本；正式写入仍用 `ft import` / `ft sync` / 手动命令。

### 连接器同步（018）

```bash
ft sync --source binance|kraken|okx|polymarket --account NAME [--full] [--batch-size N]
```

凭据：`~/.ft/credentials.yaml`。增量游标：`sync_cursors`（`workspace_id + account_id + source_type`）。失败 fail-closed，不留部分事件/游标。

### 关系审查（006+）

```bash
ft relations pending [--kind …]
ft relations check [--fact-id …] [--batch-id …]
ft relations accept|reject|later <relation_id> […]
ft relations alias-add --value … --account …
ft fact-delete <fact_id> --reason '…'
```

## 事务语义（015 后）

```text
原始文件或 API 页
  → parser / connector 映射为行或投资事件
  → 幂等键：source_type × record_id（渠道名 × 业务行键）
  → 已存在则跳过；新行写入 cash_transactions 或 investment_events
       （含 source_payload 内联溯源；无 raw_records 表）
  → 投资：apply_investment_event → ledger_snapshots + 校验
  → 现金导入后可触发 relations check（镜像 / 转账 / 退款等）
  → 同步成功则 upsert sync_cursors
  → COMMIT

任一行映射/校验/分页失败 → 回滚本批；无部分正式事实或游标
```

报告与持仓只消费 **未逻辑删除的活跃事实** 与 **已 accept 的关系**。配对不物理删事实、不改写金额；历史重复用 `fact-delete` 逻辑删除。

## Provider 与时间

| Source | 输入 | 无 offset 时间 |
|---|---|---|
| Alipay / WeChat / ICBC / CCB / DFZQ 等文件 | CSV/XLSX/PDF/… | Asia/Shanghai |
| ccxt 交易所 / Polymarket Activity | API | 连接器规范（见 018） |

带 offset 的时间保留瞬时；事实侧统一可查询的 UTC 存储，分桶按 workspace 时区。

## Mapping

`~/.ft/mapping.yaml`（缺失时建默认模板）。匹配优先更长 `fnmatch`；`default: error|fail` 时未匹配整批失败。

## 失败与幂等

- 文件/解析/密码/工具缺失：非零退出，不写库。
- 账户不存在、类型不符、币种非法、金额非有限或超精度：整批回滚。
- 重复 `(workspace, source_type, record_id)`：跳过，不重复发布。
- API 可重试错误：有限次退避后仍失败则本批不提交。

## 关系种类（摘要）

导入提交后可能产生：

- `payment_mirror` — 平台与银行同一支付
- `transfer_pair`（可含 `credit_repayment` 子类型）
- `refund_offset` — 退款核销

扫描分 Phase（硬键退款 → mirror → transfer → 弱退款/diamond）；细节见 `statement-source-onboarding` skill 与 `006`/`007`/`008` specs。
