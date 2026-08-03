# Quickstart: uSmart HK import validation

## Prerequisites
- `qpdf`, `mutool` on PATH (PDF path)
- Workspace DB: `FT_DATABASE_URL`, `FT_WORKSPACE_ID`
- Migrations applied
- Security account: `ft acct add 盈立证券 --type security --currency USD` (multi-ccy cash via positions)

## Fixture (CI)
```bash
# Unit only — no password
pytest tests/unit/importers/test_usmart_hk.py -q
```

## Local PDF calibration (not in git)
```bash
export FT_DATABASE_URL=sqlite+pysqlite:////tmp/ft-usmart-cal.db
export FT_WORKSPACE_ID=default
# migrate + acct add ...
echo '<password>' > /tmp/usmart_pw.txt
ft import /path/to/month.pdf --source usmart-hk --account 盈立证券 --password-file /tmp/usmart_pw.txt
ft import /path/to/month.pdf --source usmart-hk --account 盈立证券 --password-file /tmp/usmart_pw.txt
# second run: novel 0
```

## Pass criteria (SC-002 sample structure)
| Metric | Expected |
|--------|----------|
| USD cash after checkin | 4750.17 |
| HKD cash after checkin | 2021.09 |
| 00700 shares | 100 |
| MRVL shares | 3 |
| SPCX shares | 5 |
| Equity double-fee | 0 |
| Re-import novel | 0 |

Fixture calibration evidence: the redacted 2026-06 structure yields 37 novel
events (24 order groups, 7 non-trade cash rows, one FX swap, and five CHECKINs).

## Text fixture import
```bash
ft import tests/fixtures/usmart_hk/monthly_sample.txt --source usmart-hk --account 盈立证券
```

## Dual backend
Same fixture against SQLite and `FT_TEST_POSTGRES_URL`; compare event counts and ending cash/shares.
