# Stock Trading Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add stock trading to finance-tracker — security accounts with buy/sell/dividend/deposit/withdraw/init/checkin operations, YAML snapshot for fast queries, yfinance for live prices.

**Architecture:** Double-layer: CSV files in `~/.ft/records/security/` as audit log, `~/.ft/snapshot_security.yaml` as current state. Every operation writes both. Queries read only snapshot.

**Tech Stack:** Python 3.11+, PyYAML, yfinance, csv

**Spec:** `docs/superpowers/specs/2026-06-12-stock-trading-design.md`

---

## File Structure

**New:**
- `src/ft/stock.py` — All stock operations + snapshot management + portfolio listing + yfinance integration
- `tests/test_stock.py` — Tests for snapshot CSV + snapshot YAML roundtrip

**Modify:**
- `src/ft/cli.py` — Add `ft stock` subcommand
- `src/ft/report.py` — Update networth to read snapshot_security.yaml for security accounts
- `pyproject.toml` — Add yfinance dependency

---

### Task 1: Create stock.py

**Files:**
- Create: `src/ft/stock.py`
- Create: `tests/test_stock.py`

- [ ] **Step 1: Write failing tests**

Create `tests/test_stock.py`:

```python
"""Tests for stock trading"""
import pytest, tempfile, csv
from pathlib import Path
from ft.accounts import save_accounts

@pytest.fixture
def tmp_env():
    d = Path(tempfile.mkdtemp())
    records_dir = d / "records"
    from ft import models
    import ft.stock as stock
    old_r, old_s = models.RECORDS_DIR, stock.SNAPSHOT_PATH
    models.RECORDS_DIR = records_dir
    stock.SNAPSHOT_PATH = d / "snapshot_security.yaml"
    save_accounts([{"name":"IBKR","type":"security","currency":"USD","active":True}])
    yield records_dir
    models.RECORDS_DIR, stock.SNAPSHOT_PATH = old_r, old_s
    import shutil; shutil.rmtree(d, ignore_errors=True)

def test_snapshot_empty(tmp_env):
    from ft.stock import load_snapshot
    assert load_snapshot() == {"updated_at":"","accounts":{}}

def test_snapshot_roundtrip(tmp_env):
    from ft.stock import load_snapshot, save_snapshot
    d = {"updated_at":"2026-06-12","accounts":{"IBKR":{"currency":"USD","cash":100,"positions":{"nvda.us":{"shares":45,"avg_cost":224.14}}}}}
    save_snapshot(d)
    assert load_snapshot() == d

def test_record_trade_writes_csv(tmp_env):
    from ft.stock import record_trade
    record_trade("2026-06-12 09:30","BUY","nvda.us",5,120.0,-600.0,0.35,"USD","IBKR","")
    csv_file = Path(str(tmp_env)) / "security" / "2026-06-12.csv"
    assert csv_file.exists()
    with open(csv_file) as f:
        rows = list(csv.DictReader(f))
    assert len(rows) == 1
    assert float(rows[0]["shares"]) == 5

def test_record_trade_sorts(tmp_env):
    from ft.stock import record_trade
    record_trade("2026-06-12 14:00","BUY","nvda.us",3,125.0,-375.0,0.0,"USD","IBKR","")
    record_trade("2026-06-12 09:30","BUY","nvda.us",5,120.0,-600.0,0.0,"USD","IBKR","")
    csv_file = Path(str(tmp_env)) / "security" / "2026-06-12.csv"
    with open(csv_file) as f:
        rows = list(csv.DictReader(f))
    assert rows[0]["date"].startswith("2026-06-12 09:30")

def test_do_buy_updates_snapshot(tmp_env):
    from ft.stock import do_buy, load_snapshot
    do_buy("nvda.us",5,120.0,0.35,"USD","IBKR","", "2026-06-12 09:30")
    snap = load_snapshot()
    assert snap["accounts"]["IBKR"]["positions"]["nvda.us"]["shares"] == 5
    assert snap["accounts"]["IBKR"]["cash"] == -600.35  # amount(-600) - commission(0.35)

def test_do_sell_updates_snapshot(tmp_env):
    from ft.stock import do_buy, do_sell, load_snapshot
    do_buy("nvda.us",5,120.0,0.0,"USD","IBKR","", "2026-06-01")
    do_sell("nvda.us",2,130.0,0.15,"USD","IBKR","", "2026-06-12")
    snap = load_snapshot()
    assert snap["accounts"]["IBKR"]["positions"]["nvda.us"]["shares"] == 3
    assert abs(snap["accounts"]["IBKR"]["cash"] - (-600+260-0.15)) < 0.01
```

- [ ] **Step 2: Run tests — expect FAIL**

Run: `cd ~/Projects/finance-tracker && python -m pytest tests/test_stock.py -v`

- [ ] **Step 3: Create src/ft/stock.py**

Full implementation with: load_snapshot, save_snapshot, record_trade, do_buy, do_sell, do_deposit, do_withdraw, do_dividend, do_init, do_checkin_ticker, do_checkin_cash, do_list (with _fetch_prices yfinance).

- [ ] **Step 4: Run tests — expect PASS**

Run: `cd ~/Projects/finance-tracker && python -m pytest tests/test_stock.py -v`

- [ ] **Step 5: Commit**

```bash
cd ~/Projects/finance-tracker
git add src/ft/stock.py tests/test_stock.py
git commit -m "feat(stock): snapshot management + CSV trade recording + all stock operations"
```

---

### Task 2: Add yfinance + Wire CLI + Update report.py

- [ ] **Step 1: Add yfinance**

```bash
cd ~/Projects/finance-tracker && uv add yfinance
```

- [ ] **Step 2: Add `ft stock` subcommands to cli.py**

In `main()`, add stock subparse and dispatch.

- [ ] **Step 3: Update report.py networth to read security snapshot**

Add code in report_networth to load snapshot_security.yaml and add security account values.

- [ ] **Step 4: Run all tests**

```bash
cd ~/Projects/finance-tracker && python -m pytest tests/ -v
```

- [ ] **Step 5: Commit**

```bash
cd ~/Projects/finance-tracker
git add src/ft/cli.py src/ft/report.py pyproject.toml
git commit -m "feat(cli): wire ft stock subcommands; feat(report): integrate security snapshot"
```

---

### Task 3: Import initial portfolio from portfolio.json

- [ ] **Step 1: Read portfolio.json and generate init commands**

```bash
cat ~/.hermes/portfolio.json
```

- [ ] **Step 2: Run init commands**

```bash
cd ~/Projects/finance-tracker
ft stock init ...
ft stock deposit ... --note "初始现金"
```

- [ ] **Step 3: Verify**

```bash
ft stock list
ft report
```

- [ ] **Step 4: Commit**

```bash
cd ~/Projects/finance-tracker
git add docs/superpowers/plans/2026-06-12-stock-trading-plan.md
git commit -m "docs(plan): stock trading implementation plan"
```
