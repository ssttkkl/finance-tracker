# Quickstart: 工行退款摘要关系配对

## 1. 运行失败回归测试

```bash
uv run pytest -q tests/test_convert.py -k 'icbc or 工行'
uv run pytest -q tests/test_relations_index_injection.py tests/test_transaction_relations_refund.py
```

## 2. 使用目标 PDF 做只读解析验证

```bash
uv run python - <<'PY'
from ft.convert import _read_icbc_raw

# 将路径和密码替换为本机账单文件的私有值；不要把它们写入仓库。
path = "/path/to/your/icbc-credit-statement.pdf"
password = "YOUR_PDF_PASSWORD"
rows, bill_type, _ = _read_icbc_raw(path, password)
target = [row for row in rows if row["date"].startswith("2026-05-25 19:")]
for row in target:
    print(row["date"], row["amount"], row["counterparty"], row.get("summary"), row.get("refund_signal"))
assert bill_type == "icbc_credit"
PY
```

预期：目标三行的规范化 `counterparty` 相同，19:13 行的 `summary` 为 `退货`、`refund_signal` 为 `icbc_credit_return`。

## 3. 运行双后端验证

```bash
uv run pytest -q tests/contract/test_dual_backend_icbc_refund_pairing.py
```

没有设置 `FT_TEST_POSTGRES_URL` 时，测试只报告真实 PostgreSQL 缺少运行环境，不以 SQLite 结果替代 PostgreSQL 证据；补跑命令为：

```bash
FT_TEST_POSTGRES_URL='postgresql+psycopg://…' uv run pytest -q tests/contract/test_dual_backend_icbc_refund_pairing.py
```
