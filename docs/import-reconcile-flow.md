# 原始账单直接导入流程

当前产品没有文件型 reconcile、converted-CSV append 或 pending review session。原始账单直接进入一个
PostgreSQL transaction；未来 Review Inbox 需要以独立 feature 建立数据库原生状态模型。

## 命令

```bash
ft import FILE \
  --source alipay|wechat|icbc|icbc-debit|ccb-debit|dfzq \
  --account NAME \
  [--currency CURRENCY] \
  [--password-file FILE]
```

`--account` 必填，导入不读取本地 mapping 来猜目标账户。

## 事务链路

```text
原始文件
  → SHA-256 digest + ImportBatch(pending)
  → RawFile + immutable RawRecords
  → provider parser + Decimal/time validation
  → CashTransaction 或 InvestmentEvent
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

## 失败与幂等

- 文件不存在、provider 不支持、密码/解析失败：非零退出，不写数据库。
- 账户不存在或属于其他 workspace：整批回滚。
- 金额非有限或 scale 超过 18：整批回滚，不依赖数据库舍入。
- 重复 digest：不重复发布事实或投影。
- repository 中途异常：batch、raw rows、facts、revision 和 projection 全部回滚。

## 显式导出

`ft convert ... --output FILE` 与 `ft stock convert ... --output FILE` 只用于人工检查解析结果。导出的
CSV 不被应用读取为当前账本、快照或事务日志；正式写入仍使用原始文件的 `ft import`。
