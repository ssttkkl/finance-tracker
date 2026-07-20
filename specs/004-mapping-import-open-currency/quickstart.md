# Quickstart: Mapping Import & Open Currency

```bash
export FT_DATABASE_URL='postgresql+psycopg://…/finance_tracker'
export FT_WORKSPACE_ID='default'
uv run alembic upgrade head

# Open currency
uv run ft acct add '工行信用卡(1200)' --type loan --currency JPY

# Ensure ~/.ft/mapping.yaml routes payment methods / bill types

# Import: NO --account
uv run ft import ~/.ft/bills/支付宝交易明细.csv --source alipay
uv run ft import ~/.ft/bills/icbc-debit.pdf --source icbc-debit --password-file /tmp/pw.txt
uv run ft import ~/.ft/bills/hqmx.xls --source ccb-debit

# Preview same routing
uv run ft convert ~/.ft/bills/支付宝交易明细.csv --source alipay -o /tmp/preview.csv
```
