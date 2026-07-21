# 原始账单直接导入流程

当前产品没有文件型 reconcile、converted-CSV append 或 pending review session。原始账单直接进入一个
数据库 transaction；未来 Review Inbox 需要以独立 feature 建立数据库原生状态模型。

## 命令

```bash
ft import FILE \
  --source alipay|wechat|icbc|icbc-debit|ccb-debit|dfzq \
  [--currency CURRENCY] \
  [--password-file FILE]
```

**禁止** `--account`。每一行的目标账户只从账单字段（支付方式 / 卡号 / bill_type 等）结合
`~/.ft/mapping.yaml` 推断；同一文件可写入多个账户，整批单事务提交。

`--currency` 可选，仅作行内缺省币种回退，不得选择或覆盖账户名。币种为任意 3 位字母码（如 JPY），
归一为大写。

```bash
# 预览（账户路由与 import 相同）
ft convert FILE --source alipay -o preview.csv
```

## 事务链路

```text
原始文件
  → SHA-256 digest + ImportBatch(pending, target_account_id 可空)
  → RawFile + immutable RawRecords
  → provider parser + mapping 路由每行 account_name/currency
  → CashTransaction 或 InvestmentEvent（按 fact.account_id）
  → raw_record_id lineage + initial RecordRevision
  → LedgerProjection update
  → ImportBatch(completed) + COMMIT

任意失败 → ROLLBACK，无部分 raw/formal/projection 状态
重复 workspace + source + digest → 返回已有 completed batch
```

原始文件字节不会复制到仓库或日志。数据库只保存必要的摘要、大小、媒体类型、去敏文件名和解析证据。

## Provider 和时间

| Source | 输入 | 无 offset 时间解释 |
|---|---|---|
| Alipay | CSV | Asia/Shanghai |
| WeChat | XLSX | Asia/Shanghai |
| ICBC credit/debit | PDF | Asia/Shanghai |
| CCB debit | XLS | Asia/Shanghai |
| DFZQ | PDF | Asia/Shanghai |

带 offset 的时间保留其瞬时时间；无 offset 的中国账单按 Asia/Shanghai 解释，事实统一保存为 UTC
`timestamptz`，查询再按 workspace 时区分桶。

## Mapping

规则文件：`~/.ft/mapping.yaml`（首次缺失时创建默认模板）。匹配优先
`{bill_type}_{card_number}` + `*`，否则 `bill_type` + `payment_method` 的 fnmatch，**更长 match 优先**。
`default: error|fail` 时未匹配整批失败；`default: skip` 跳过未匹配行，若全部跳过则失败。

## 失败与幂等

- 文件不存在、provider 不支持、密码/解析失败：非零退出，不写数据库。
- 目标账户不存在或币种与现金/贷款账户不一致：整批回滚。
- 未匹配 mapping（default=error/fail）：整批回滚。
- 金额非有限或 scale 超过 18：整批回滚，不依赖数据库舍入。
- 重复 digest：不重复发布事实或投影。
- repository 中途异常：batch、raw rows、facts、revision 和 projection 全部回滚。

## 显式导出

`ft convert ... --output FILE` 与 `ft stock convert ... --output FILE` 只用于人工检查解析结果。导出的
CSV 不被应用读取为当前账本、快照或事务日志；正式写入仍使用原始文件的 `ft import`。

<!-- 006-transaction-relations -->
## Transaction relations (006)

After `ft import` commits formal facts, a relation check may create `payment_mirror`,
`transfer_pair` (optional `credit_repayment` subtype), and `refund_offset` relations.
Reports use **active facts + accepted relations** only. Pairing never physically deletes
facts or rewrites amounts. Historical duplicates are handled by audited logical delete
(`ft fact-delete`); re-import of the same source identity publishes a **new** active fact.
Legacy `offset_*` / `transfer_account` / `proposed_action` fields are non-authoritative.
Review: `ft relations pending|accept|reject|later`. Manual re-check: `ft relations check`.

