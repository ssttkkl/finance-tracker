# Quickstart: 标准记录类型导入

## 运行测试

```bash
uv run pytest -q tests/test_record_type.py tests/test_statement_import_mapping.py tests/test_postgres_statement_import.py
uv run pytest -q tests/contract/test_dual_backend_record_type.py
```

## 重建新 SQLite

```bash
export FT_DATABASE_URL="sqlite+pysqlite:////Users/huangwenlong/.ft/finance-tracker-record-type-rebuild.db"
export FT_WORKSPACE_ID=default
uv run alembic upgrade head
# 按现有 ~/.ft/accounts.yaml 和 ~/.ft/mapping.yaml 初始化账户后，导入 .ft/bills 中五个现金来源
```

完成后检查：

```sql
SELECT record_type, COUNT(*)
FROM cash_transactions
GROUP BY record_type
ORDER BY record_type;
```

确认 `record_type` 非空、现金总数为 11,394，并核对本次新规则分布：`withdrawal_in=2`、
`withdrawal_out=58`、`repayment=48`、`refund=586`、`other=1,030`。随后再备份并替换当前数据库。

2026-08-02 实际校验：SQLite `PRAGMA integrity_check` 返回 `ok`，schema revision 为
`20260802_15`；主库包含 11,394 条现金事实、533 条投资事实，关系 2,600 条，收支投影状态为
`ready`（8,911 条投影、11,394 个成员）。重建前备份为
`/Users/huangwenlong/.ft/finance-tracker.db.before-rebuild-20260802-094625`。

`东方证券 电子对账单.pdf` 当前无法用账单本身重解析（PDF 加密且未提供密码）。为避免替换时丢失投资事实，临时重建阶段保留了当前库中对应的 497 条 `dfzq_pdf` 和 36 条 `ibkr_csv` 事实；生产导入代码不读取旧库，也没有历史 `record_type` 回填逻辑。
