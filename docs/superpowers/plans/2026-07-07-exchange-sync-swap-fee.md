# 通用交易所同步 + 兑换/手续费账本原语 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 `ft stock sync polymarket` 泛化为通用 `ft stock sync <provider>`，通过 ccxt 同步加密货币交易所私有成交到 crypto 账户；为币币成交与多币种手续费给账本新增 SWAP/FEE 两个原语。

**Architecture:** 复用现有 stock 回放引擎（CSV 存物理事实、成本靠 replay 推导）。币币对写 `SWAP_OUT`/`SWAP_IN` 两行做成本结转（换出释放的 USD 成本原样转给换入币，不碰现金、不依赖外部价）；持仓币手续费写独立 `FEE` 行按均价核销。新增 `exchange_sync.py`（ETL 流水线，结构对应 `polymarket_sync.py`）、`credentials.py`（`~/.ft/credentials.yaml`）、`sync_common.py`（抽出 polymarket 与 exchange 共用的去重/写 CSV helper）。

**Tech Stack:** Python ≥3.11、ccxt（统一交易所库）、pyyaml、现有 ft 回放引擎（`src/ft/stock.py`）、pytest（全程 mock ccxt/文件系统，不碰真实网络）。

## Global Constraints

- Python `requires-python = ">=3.11"`（`pyproject.toml`，不改）。
- CSV schema 恒为 10 列：`date, action, ticker, shares, price, amount, commission, currency, account_name, note`（`stock.CSV_FIELDS`）。SWAP/FEE 复用现有列，金额类列（`price`/`amount`/`commission`）留空字符串 `""`。
- **零回归**：不改动 polymarket 现有映射与去重行为，只把 `_row_identity`、`write_stock_csv` 两个纯 helper 抽到 `sync_common.py` 并重新指向；判定标准 = 现有 polymarket 测试全部继续通过。
- 密钥（api_key/api_secret/password）**永不**进入 stdout/日志/异常消息。
- SWAP 两腿 USD 总成本守恒；replay 确定性可重建（`verify --fix` 语义）。
- 全部测试 mock ccxt（`fetch_my_trades` 返回构造 trade）与文件系统（用 `tmp_env` fixture 重定向 `models.FT_DIR`/`RECORDS_DIR`/`ACCOUNTS_PATH`、`snapshot.SNAPSHOT_PATH`），不发起真实网络请求。
- 币种转 ticker 一律小写；USDT 在 BUY/SELL 语境视为 USD 现金（1:1），在 SWAP 语境视为持仓。

---

## File Structure

- **Create** `src/ft/sync_common.py` — polymarket 与 exchange 共用的纯 helper：`row_identity`、`write_stock_csv`、`id_token_from_note`、`filter_new_rows`（通用、按账户+note token 去重）。
- **Create** `src/ft/credentials.py` — `~/.ft/credentials.yaml` 读取与 gitignore/chmod 保护。
- **Create** `src/ft/exchange_sync.py` — 交易所同步 ETL 流水线（校验账户 / 建客户端 / 拉成交 / 映射 / 去重 / 三态落库）。
- **Modify** `src/ft/stock.py` — `VALID_ACTIONS` 增 3 个 action；`_replay_security_rows` 增 SWAP_OUT/SWAP_IN/FEE 三分支；`do_append` 数值校验放开空串；`repair_security` 币种查找纳入 crypto；新增 `do_swap`。
- **Modify** `src/ft/polymarket_sync.py` — 删除本地 `_row_identity`/`write_stock_csv`，改从 `sync_common` 导入（行为不变）。
- **Modify** `src/ft/cli.py` — 泛化 `stock sync` 子命令分发到 `<provider>`；新增 `stock swap` 子命令。
- **Modify** `pyproject.toml` — dependencies 增加 `ccxt`。
- **Test** `tests/test_stock.py` — SWAP/FEE replay、do_swap、do_append 空串、repair 币种 单测追加到既有文件。
- **Test** `tests/test_exchange_sync.py` — `trade_to_rows`、`filter_new_rows`、`sync_exchange`（mock client）新建文件。
- **Test** `tests/test_credentials.py` — `load_credentials`/`ensure_credentials_gitignored` 新建文件。
- **Test** `tests/test_sync_common.py` — 共用 helper 单测新建文件。

**测试运行入口**：仓库根目录执行 `.venv/bin/python -m pytest <path> -v`（venv 已存在于 worktree）。

---

### Task 1: 增加 ccxt 依赖

**Files:**
- Modify: `pyproject.toml:7-13`（dependencies 列表）

**Interfaces:**
- Produces: `import ccxt` 在 venv 中可用（Task 8/9 依赖）。

- [ ] **Step 1: 写失败测试**

在 `tests/test_exchange_sync.py`（新建）写：

```python
def test_ccxt_is_importable():
    """ccxt 必须已安装，交易所同步依赖它。"""
    import ccxt
    assert hasattr(ccxt, "kraken")
```

- [ ] **Step 2: 运行测试确认失败**

Run: `.venv/bin/python -m pytest tests/test_exchange_sync.py::test_ccxt_is_importable -v`
Expected: FAIL，`ModuleNotFoundError: No module named 'ccxt'`

- [ ] **Step 3: 加依赖并安装**

编辑 `pyproject.toml`，把 dependencies 改为：

```toml
dependencies = [
    "ccxt>=4.4.0",
    "openpyxl>=3.1.5",
    "pdfplumber>=0.11.9",
    "pyyaml>=6.0.3",
    "xlrd>=2.0.2",
    "yfinance>=1.4.1",
]
```

然后安装：

```bash
uv sync
```

- [ ] **Step 4: 运行测试确认通过**

Run: `.venv/bin/python -m pytest tests/test_exchange_sync.py::test_ccxt_is_importable -v`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add pyproject.toml uv.lock tests/test_exchange_sync.py
git commit -m "feat: add ccxt dependency for exchange sync"
```

---

### Task 2: SWAP/FEE 回放引擎（stock.py）

给回放引擎新增三个 action 分支。SWAP 做成本结转：换出币按当前均价释放 USD 成本，原样转给换入币；FEE 按均价核销持仓与成本。均价 = `total_cost / shares`。

**Files:**
- Modify: `src/ft/stock.py:22`（`VALID_ACTIONS`）
- Modify: `src/ft/stock.py:1213-1308`（`_replay_security_rows`）
- Test: `tests/test_stock.py`（在文件末尾追加）

**Interfaces:**
- Consumes: 现有 `positions[(account, ticker)] = {"shares": float, "total_cost": float}`、`cash[account]`、`_normalize_position`、`_validate_position`、`_ensure_finite_values`。
- Produces: replay 支持 `SWAP_OUT`/`SWAP_IN`/`FEE`。配对键 = note 中的 `swap:<id>` token。SWAP_IN 找不到配对 `released` → `ValueError`。

- [ ] **Step 1: 写失败测试**

在 `tests/test_stock.py` 末尾追加（该文件顶部已有 `import pytest` 和 `tmp_env` fixture）：

```python
def test_replay_swap_conserves_total_cost():
    """SWAP: 换出币释放的成本原样转给换入币，USD 总成本守恒，不碰现金。"""
    from ft.stock import _replay_security_rows

    rows = [
        # 先用现金买入 1 BTC，成本 60000
        {"date": "2026-07-07 09:00:00", "action": "BUY", "ticker": "btc", "shares": "1",
         "price": "60000", "amount": "-60000", "commission": "0", "currency": "USD",
         "account_name": "币安", "note": "seed"},
        # 用 0.5 BTC 换 10 ETH
        {"date": "2026-07-07 10:00:00", "action": "SWAP_OUT", "ticker": "btc", "shares": "0.5",
         "price": "", "amount": "", "commission": "", "currency": "USD",
         "account_name": "币安", "note": "kraken tid:T1 swap:T1"},
        {"date": "2026-07-07 10:00:00", "action": "SWAP_IN", "ticker": "eth", "shares": "10",
         "price": "", "amount": "", "commission": "", "currency": "USD",
         "account_name": "币安", "note": "kraken tid:T1 swap:T1"},
    ]
    positions, cash = _replay_security_rows(rows)

    # BTC: 剩 0.5，成本 30000（释放了 0.5*60000=30000）
    assert positions[("币安", "btc")]["shares"] == pytest.approx(0.5)
    assert positions[("币安", "btc")]["total_cost"] == pytest.approx(30000.0)
    # ETH: 10 股，接收成本 30000
    assert positions[("币安", "eth")]["shares"] == pytest.approx(10.0)
    assert positions[("币安", "eth")]["total_cost"] == pytest.approx(30000.0)
    # 现金不动
    assert cash["币安"] == pytest.approx(-60000.0)
    # 总成本守恒
    assert (positions[("币安", "btc")]["total_cost"]
            + positions[("币安", "eth")]["total_cost"]) == pytest.approx(60000.0)


def test_replay_swap_in_without_pair_raises():
    """SWAP_IN 找不到配对 released 必须报错，不静默。"""
    from ft.stock import _replay_security_rows

    rows = [
        {"date": "2026-07-07 10:00:00", "action": "SWAP_IN", "ticker": "eth", "shares": "10",
         "price": "", "amount": "", "commission": "", "currency": "USD",
         "account_name": "币安", "note": "kraken tid:T9 swap:T9"},
    ]
    with pytest.raises(ValueError, match="swap"):
        _replay_security_rows(rows)


def test_replay_fee_reduces_holding_by_avg_cost():
    """FEE: 按均价核销持仓与成本。"""
    from ft.stock import _replay_security_rows

    rows = [
        {"date": "2026-07-07 09:00:00", "action": "BUY", "ticker": "bnb", "shares": "10",
         "price": "500", "amount": "-5000", "commission": "0", "currency": "USD",
         "account_name": "币安", "note": "seed"},
        {"date": "2026-07-07 10:00:00", "action": "FEE", "ticker": "bnb", "shares": "0.1",
         "price": "", "amount": "", "commission": "", "currency": "USD",
         "account_name": "币安", "note": "kraken tid:T1 fee"},
    ]
    positions, cash = _replay_security_rows(rows)

    # BNB: 剩 9.9，成本 5000 - 0.1*500 = 4950
    assert positions[("币安", "bnb")]["shares"] == pytest.approx(9.9)
    assert positions[("币安", "bnb")]["total_cost"] == pytest.approx(4950.0)
```

- [ ] **Step 2: 运行测试确认失败**

Run: `.venv/bin/python -m pytest tests/test_stock.py::test_replay_swap_conserves_total_cost tests/test_stock.py::test_replay_swap_in_without_pair_raises tests/test_stock.py::test_replay_fee_reduces_holding_by_avg_cost -v`
Expected: FAIL（SWAP/FEE 行被当作未知 action 跳过，断言不满足）

- [ ] **Step 3: 扩展 VALID_ACTIONS**

编辑 `src/ft/stock.py:22`，把：

```python
VALID_ACTIONS = {"BUY", "SELL", "DEPOSIT", "WITHDRAW", "DIVIDEND", "CHECKIN"}
```

改为：

```python
VALID_ACTIONS = {"BUY", "SELL", "DEPOSIT", "WITHDRAW", "DIVIDEND", "CHECKIN",
                 "SWAP_OUT", "SWAP_IN", "FEE"}
```

- [ ] **Step 4: 在 `_replay_security_rows` 加 pending_swaps 与三分支**

编辑 `src/ft/stock.py`。在 `_replay_security_rows` 里 `cash = defaultdict(float)` 之后（约 1218 行后）新增一行局部暂存表：

```python
    pending_swaps: dict[str, float] = {}
```

然后在 action 分支链里，`elif act == "WITHDRAW":`（约 1304-1306 行）之后、`return positions, cash` 之前，追加三个新分支：

```python
        elif act == "SWAP_OUT":
            import re
            h = positions[(a, t)]
            avg = h["total_cost"] / h["shares"] if h["shares"] else 0.0
            released = round(s * avg, 2)
            h["shares"] = round(h["shares"] - s, 10)
            h["total_cost"] = round(h["total_cost"] - released, 2)
            _normalize_position(h)
            _validate_position(a, t)
            m = re.search(r"swap:(\S+)", row.get("note", "") or "")
            if not m:
                raise ValueError(f"SWAP_OUT 缺少 note 中的 swap:<id>: {row!r}")
            pending_swaps[m.group(1)] = released
        elif act == "SWAP_IN":
            import re
            m = re.search(r"swap:(\S+)", row.get("note", "") or "")
            if not m or m.group(1) not in pending_swaps:
                raise ValueError(f"SWAP_IN 找不到配对的 swap released: {row!r}")
            received = pending_swaps.pop(m.group(1))
            h = positions[(a, t)]
            h["shares"] = round(h["shares"] + s, 10)
            h["total_cost"] = round(h["total_cost"] + received, 2)
            _normalize_position(h)
            _validate_position(a, t)
        elif act == "FEE":
            h = positions[(a, t)]
            avg = h["total_cost"] / h["shares"] if h["shares"] else 0.0
            h["total_cost"] = round(h["total_cost"] - round(s * avg, 2), 2)
            h["shares"] = round(h["shares"] - s, 10)
            _normalize_position(h)
            _validate_position(a, t)
```

- [ ] **Step 5: 运行测试确认通过**

Run: `.venv/bin/python -m pytest tests/test_stock.py::test_replay_swap_conserves_total_cost tests/test_stock.py::test_replay_swap_in_without_pair_raises tests/test_stock.py::test_replay_fee_reduces_holding_by_avg_cost -v`
Expected: PASS ×3

- [ ] **Step 6: 跑全量 stock 测试确认零回归**

Run: `.venv/bin/python -m pytest tests/test_stock.py -q`
Expected: 全绿（已有测试不受影响）

- [ ] **Step 7: 提交**

```bash
git add src/ft/stock.py tests/test_stock.py
git commit -m "feat: add SWAP_OUT/SWAP_IN/FEE replay branches to stock engine"
```

---

### Task 3: do_append 放开 SWAP/FEE 空数值列 + crypto 币种修复

SWAP/FEE 行的 `price`/`amount`/`commission` 为空串，现有 `do_append` 数值校验 `float(row[field])` 会崩。同时修复既有 bug：`repair_security` 币种查找只认 `type=="security"`，导致 crypto 账户经 `do_append` 重建后币种丢成 `""`。

**Files:**
- Modify: `src/ft/stock.py:327-338`（`do_append` 数值校验循环）
- Modify: `src/ft/stock.py:1363`（`repair_security` 币种查找）
- Test: `tests/test_stock.py`（追加）

**Interfaces:**
- Consumes: `VALID_ACTIONS`（Task 2 已含 SWAP/FEE）。
- Produces: `do_append` 接受含空数值列的 SWAP/FEE 行；`repair_security` 保留 crypto 账户币种。

- [ ] **Step 1: 写失败测试**

在 `tests/test_stock.py` 末尾追加：

```python
def test_append_accepts_swap_fee_rows_and_keeps_currency(tmp_env):
    """含空数值列的 SWAP/FEE 行可导入；crypto 账户币种在重建后保留。"""
    from ft.accounts import save_accounts
    from ft.stock import CSV_FIELDS, do_append, load_snapshot
    from ft import models

    save_accounts([
        {"name": "币安", "type": "crypto", "currency": "USD", "active": True},
    ], models.ACCOUNTS_PATH)

    csv_path = tmp_env / "swap.csv"
    with csv_path.open("w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=CSV_FIELDS)
        writer.writeheader()
        writer.writerow({"date": "2026-07-07 09:00:00", "action": "BUY", "ticker": "btc",
                         "shares": "1", "price": "60000", "amount": "-60000", "commission": "0",
                         "currency": "USD", "account_name": "币安", "note": "seed"})
        writer.writerow({"date": "2026-07-07 10:00:00", "action": "SWAP_OUT", "ticker": "btc",
                         "shares": "0.5", "price": "", "amount": "", "commission": "",
                         "currency": "USD", "account_name": "币安", "note": "kraken tid:T1 swap:T1"})
        writer.writerow({"date": "2026-07-07 10:00:00", "action": "SWAP_IN", "ticker": "eth",
                         "shares": "10", "price": "", "amount": "", "commission": "",
                         "currency": "USD", "account_name": "币安", "note": "kraken tid:T1 swap:T1"})

    assert do_append(csv_path) is True
    snap = load_snapshot()
    acct = snap["accounts"]["security"]["币安"]
    assert acct["currency"] == "USD"          # 币种未丢
    assert acct["positions"]["eth"]["shares"] == pytest.approx(10.0)
    assert acct["positions"]["btc"]["shares"] == pytest.approx(0.5)
```

- [ ] **Step 2: 运行测试确认失败**

Run: `.venv/bin/python -m pytest tests/test_stock.py::test_append_accepts_swap_fee_rows_and_keeps_currency -v`
Expected: FAIL（`float('')` 报“不是有效数字”，`do_append` 返回 False）

- [ ] **Step 3: 放开空数值列**

编辑 `src/ft/stock.py` 约 331 行，把：

```python
            try:
                value = float(row[field])
            except (ValueError, TypeError):
```

改为（空串按 0 处理，与 replay 的 `float(row["shares"] or 0)` 一致）：

```python
            try:
                value = float(row[field] or 0)
            except (ValueError, TypeError):
```

- [ ] **Step 4: 修复 repair_security 币种查找**

编辑 `src/ft/stock.py:1363`，把：

```python
    acct_currencies = {a["name"]: a["currency"] for a in load_accounts()
                       if a["type"] == "security"}
```

改为：

```python
    acct_currencies = {a["name"]: a["currency"] for a in load_accounts()
                       if a["type"] in ("security", "crypto")}
```

- [ ] **Step 5: 运行测试确认通过**

Run: `.venv/bin/python -m pytest tests/test_stock.py::test_append_accepts_swap_fee_rows_and_keeps_currency -v`
Expected: PASS

- [ ] **Step 6: 跑全量 stock 测试**

Run: `.venv/bin/python -m pytest tests/test_stock.py -q`
Expected: 全绿

- [ ] **Step 7: 提交**

```bash
git add src/ft/stock.py tests/test_stock.py
git commit -m "fix: accept empty numeric cols for SWAP/FEE and preserve crypto currency on rebuild"
```

---

### Task 4: 共用去重/写 CSV helper（sync_common.py）

抽出 polymarket 与 exchange 共用的纯 helper，避免复制粘贴。通用去重按“账户 + note 中的 id token（`tx:` 或 `tid:`）”工作，并保留整行精确去重。

**Files:**
- Create: `src/ft/sync_common.py`
- Modify: `src/ft/polymarket_sync.py:218-250`（删除本地 `_row_identity`/`write_stock_csv`，改导入）
- Test: `tests/test_sync_common.py`

**Interfaces:**
- Produces:
  - `row_identity(row: dict) -> tuple[str, ...]` — 用 `stock.CSV_FIELDS` 顺序取值。
  - `id_token_from_note(note: str, prefix: str) -> str | None` — 从 note 提取 `<prefix>:<token>`。
  - `write_stock_csv(rows: list[dict], output: str | Path) -> Path`。
  - `filter_new_rows(rows, records_dir=None, account_name=None, *, prefix="tid") -> list[dict]` — 按账户 + id token + 整行去重。account_name 必填（exchange 场景总提供）。

- [ ] **Step 1: 写失败测试**

创建 `tests/test_sync_common.py`：

```python
import csv
import tempfile
from pathlib import Path

import pytest


@pytest.fixture
def tmp_env():
    d = Path(tempfile.mkdtemp())
    from ft import models
    old = models.RECORDS_DIR
    models.RECORDS_DIR = d / "records"
    yield d
    models.RECORDS_DIR = old
    import shutil
    shutil.rmtree(d, ignore_errors=True)


def test_id_token_from_note_extracts_prefix():
    from ft.sync_common import id_token_from_note
    assert id_token_from_note("kraken tid:T123 swap:T123", "tid") == "T123"
    assert id_token_from_note("kraken tid:T123 swap:T123", "swap") == "T123"
    assert id_token_from_note("no token here", "tid") is None


def test_write_stock_csv_roundtrip(tmp_env):
    from ft.sync_common import write_stock_csv
    from ft.stock import CSV_FIELDS
    rows = [{f: "" for f in CSV_FIELDS} | {"action": "BUY", "ticker": "btc"}]
    out = write_stock_csv(rows, tmp_env / "o.csv")
    with out.open(encoding="utf-8") as f:
        got = list(csv.DictReader(f))
    assert got[0]["ticker"] == "btc"


def test_filter_new_rows_dedupes_by_tid(tmp_env):
    from ft import stock
    from ft.sync_common import filter_new_rows

    stock.record_trade(date="2026-07-07 10:00:00", action="BUY", ticker="btc",
                       shares=1, price=60000, amount=-60000, commission=0,
                       currency="USD", account_name="币安", note="kraken tid:OLD")
    rows = [
        {"date": "2026-07-07 10:00:00", "action": "BUY", "ticker": "btc", "shares": "1",
         "price": "60000", "amount": "-60000", "commission": "0", "currency": "USD",
         "account_name": "币安", "note": "kraken tid:OLD"},
        {"date": "2026-07-08 10:00:00", "action": "BUY", "ticker": "eth", "shares": "2",
         "price": "3000", "amount": "-6000", "commission": "0", "currency": "USD",
         "account_name": "币安", "note": "kraken tid:NEW"},
    ]
    new = filter_new_rows(rows, account_name="币安", prefix="tid")
    assert len(new) == 1
    assert new[0]["note"] == "kraken tid:NEW"
```

- [ ] **Step 2: 运行测试确认失败**

Run: `.venv/bin/python -m pytest tests/test_sync_common.py -v`
Expected: FAIL，`ModuleNotFoundError: No module named 'ft.sync_common'`

- [ ] **Step 3: 创建 sync_common.py**

创建 `src/ft/sync_common.py`：

```python
"""Shared helpers for external-platform sync pipelines (polymarket, exchanges)."""
from __future__ import annotations

import csv
import re
from pathlib import Path
from typing import Iterable

from . import models
from .stock import CSV_FIELDS


def row_identity(row: dict) -> tuple[str, ...]:
    """Exact-row identity across all CSV columns."""
    return tuple(str(row.get(field, "")) for field in CSV_FIELDS)


def id_token_from_note(note: str, prefix: str) -> str | None:
    """Extract `<prefix>:<token>` from a note string (token = non-space run)."""
    m = re.search(rf"{re.escape(prefix)}:(\S+)", note or "")
    return m.group(1) if m else None


def write_stock_csv(rows: list[dict], output: str | Path) -> Path:
    output_path = Path(output)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    with output_path.open("w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=CSV_FIELDS)
        writer.writeheader()
        writer.writerows(rows)
    return output_path


def _existing_identities(
    records_dir: Path | None,
    account_name: str,
    prefix: str,
) -> tuple[set[str], set[tuple[str, ...]]]:
    if records_dir is None:
        records_dir = models.RECORDS_DIR
    security_dir = Path(records_dir) / "security"
    id_tokens: set[str] = set()
    exact_rows: set[tuple[str, ...]] = set()
    if not security_dir.exists():
        return id_tokens, exact_rows
    for path in sorted(security_dir.glob("*.csv")):
        with path.open(encoding="utf-8") as f:
            for row in csv.DictReader(f):
                if row.get("account_name") != account_name:
                    continue
                tok = id_token_from_note(row.get("note", ""), prefix)
                if tok:
                    id_tokens.add(tok)
                exact_rows.add(row_identity(row))
    return id_tokens, exact_rows


def filter_new_rows(
    rows: Iterable[dict],
    records_dir: Path | None = None,
    account_name: str | None = None,
    *,
    prefix: str = "tid",
) -> list[dict]:
    """Drop rows whose trade is already recorded (by note id token) or exact-dup."""
    if account_name is None:
        raise ValueError("filter_new_rows 需要 account_name")
    id_tokens, exact_rows = _existing_identities(records_dir, account_name, prefix)
    new_rows: list[dict] = []
    seen_exact: set[tuple[str, ...]] = set()
    for row in rows:
        tok = id_token_from_note(row.get("note", ""), prefix)
        ident = row_identity(row)
        if tok and tok in id_tokens:          # 整笔 trade 已入库 → 跳过
            continue
        if ident in exact_rows or ident in seen_exact:  # 整行重复 → 跳过
            continue
        new_rows.append(row)
        seen_exact.add(ident)
    return new_rows
```

注：一笔 trade 可能展开成 2-3 行（SWAP_OUT/SWAP_IN/FEE）共享同一 `tid`。去重规则：**若该 `tid` 已在历史记录中出现，整笔的所有行都跳过**（重复同步幂等）；同批次内不按 `tid` 去重（否则会误删同 tid 的 SWAP_IN/FEE 行），仅用整行精确匹配防批内重复。

- [ ] **Step 4: 运行测试确认通过**

Run: `.venv/bin/python -m pytest tests/test_sync_common.py -v`
Expected: PASS ×3

- [ ] **Step 5: polymarket 重新指向共用 helper（零回归）**

编辑 `src/ft/polymarket_sync.py`：

1. 顶部导入处（第 16 行 `from .stock import CSV_FIELDS, do_append` 下方）新增：

```python
from .sync_common import row_identity as _shared_row_identity, write_stock_csv
```

2. 删除本地 `write_stock_csv` 定义（原 243-250 行整段）。

3. 把本地 `_row_identity`（原 218-219 行）改为委托：

```python
def _row_identity(row: dict) -> tuple[str, ...]:
    return _shared_row_identity(row)
```

（保留 `_row_identity` 名字，因 `_existing_polymarket_identities`/`filter_new_rows` 仍引用它；polymarket 的 `filter_new_rows`、`_tx_hash_from_note`、`_existing_polymarket_identities` 逻辑**不动**。）

- [ ] **Step 6: 跑 polymarket 相关测试确认零回归**

Run: `.venv/bin/python -m pytest tests/test_stock.py tests/test_cli.py -q -k "polymarket or filter_new or sync"`
Expected: 全绿

- [ ] **Step 7: 提交**

```bash
git add src/ft/sync_common.py src/ft/polymarket_sync.py tests/test_sync_common.py
git commit -m "refactor: extract shared sync helpers into sync_common"
```

---

### Task 5: 凭证读取与保护（credentials.py）

**Files:**
- Create: `src/ft/credentials.py`
- Test: `tests/test_credentials.py`

**Interfaces:**
- Produces:
  - `load_credentials(provider: str) -> dict` — 返回含 `api_key`/`api_secret`（可选 `password`）的段；文件/段/必填字段缺失均抛 `ValueError`（消息含路径与所需字段，**不含**密钥值）。
  - `ensure_credentials_gitignored() -> None` — 确保 `<FT_DIR>/.gitignore` 含 `credentials.yaml`，文件存在时 `chmod 600`。
  - 常量 `CREDENTIALS_FILENAME = "credentials.yaml"`。

- [ ] **Step 1: 写失败测试**

创建 `tests/test_credentials.py`：

```python
import os
import stat
import tempfile
from pathlib import Path

import pytest
import yaml


@pytest.fixture
def tmp_ft(tmp_path):
    from ft import models
    old = models.FT_DIR
    models.FT_DIR = tmp_path
    yield tmp_path
    models.FT_DIR = old


def _write_creds(ft_dir, data):
    (ft_dir / "credentials.yaml").write_text(yaml.safe_dump(data), encoding="utf-8")


def test_load_credentials_reads_provider_section(tmp_ft):
    from ft.credentials import load_credentials
    _write_creds(tmp_ft, {"kraken": {"api_key": "K", "api_secret": "S"}})
    creds = load_credentials("kraken")
    assert creds["api_key"] == "K"
    assert creds["api_secret"] == "S"


def test_load_credentials_missing_file_raises(tmp_ft):
    from ft.credentials import load_credentials
    with pytest.raises(ValueError, match="credentials.yaml"):
        load_credentials("kraken")


def test_load_credentials_missing_section_raises(tmp_ft):
    from ft.credentials import load_credentials
    _write_creds(tmp_ft, {"okx": {"api_key": "K", "api_secret": "S"}})
    with pytest.raises(ValueError, match="kraken"):
        load_credentials("kraken")


def test_load_credentials_missing_field_raises(tmp_ft):
    from ft.credentials import load_credentials
    _write_creds(tmp_ft, {"kraken": {"api_key": "K"}})
    with pytest.raises(ValueError, match="api_secret"):
        load_credentials("kraken")


def test_load_credentials_error_never_leaks_secret(tmp_ft):
    from ft.credentials import load_credentials
    _write_creds(tmp_ft, {"kraken": {"api_key": "SUPERSECRETKEY"}})
    with pytest.raises(ValueError) as exc:
        load_credentials("kraken")
    assert "SUPERSECRETKEY" not in str(exc.value)


def test_ensure_gitignored_adds_entry_and_chmods(tmp_ft):
    from ft.credentials import ensure_credentials_gitignored
    _write_creds(tmp_ft, {"kraken": {"api_key": "K", "api_secret": "S"}})
    ensure_credentials_gitignored()
    gitignore = (tmp_ft / ".gitignore").read_text(encoding="utf-8")
    assert "credentials.yaml" in gitignore
    mode = stat.S_IMODE(os.stat(tmp_ft / "credentials.yaml").st_mode)
    assert mode == 0o600
```

- [ ] **Step 2: 运行测试确认失败**

Run: `.venv/bin/python -m pytest tests/test_credentials.py -v`
Expected: FAIL，`ModuleNotFoundError: No module named 'ft.credentials'`

- [ ] **Step 3: 创建 credentials.py**

创建 `src/ft/credentials.py`：

```python
"""Exchange API credentials: read from ~/.ft/credentials.yaml, keep it private."""
from __future__ import annotations

import os
from pathlib import Path

import yaml

from . import models

CREDENTIALS_FILENAME = "credentials.yaml"
REQUIRED_FIELDS = ("api_key", "api_secret")


def _credentials_path() -> Path:
    return Path(models.FT_DIR) / CREDENTIALS_FILENAME


def load_credentials(provider: str) -> dict:
    """Load one provider's credential section. Never echoes secret values on error."""
    path = _credentials_path()
    if not path.exists():
        raise ValueError(
            f"未找到凭证文件 {path}，请创建并写入：\n"
            f"{provider}:\n  api_key: \"...\"\n  api_secret: \"...\""
        )
    data = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
    section = data.get(provider)
    if not isinstance(section, dict) or not section:
        raise ValueError(
            f"凭证文件 {path} 缺少 '{provider}' 段，请补充 api_key/api_secret"
        )
    for field in REQUIRED_FIELDS:
        if not section.get(field):
            raise ValueError(f"凭证 '{provider}' 缺少必填字段 '{field}'（见 {path}）")
    return section


def ensure_credentials_gitignored() -> None:
    """Ensure credentials.yaml is gitignored under FT_DIR and chmod 600 if present."""
    ft_dir = Path(models.FT_DIR)
    ft_dir.mkdir(parents=True, exist_ok=True)
    gitignore = ft_dir / ".gitignore"
    lines = []
    if gitignore.exists():
        lines = gitignore.read_text(encoding="utf-8").splitlines()
    if CREDENTIALS_FILENAME not in {ln.strip() for ln in lines}:
        lines.append(CREDENTIALS_FILENAME)
        gitignore.write_text("\n".join(lines) + "\n", encoding="utf-8")
    path = _credentials_path()
    if path.exists():
        os.chmod(path, 0o600)
```

- [ ] **Step 4: 运行测试确认通过**

Run: `.venv/bin/python -m pytest tests/test_credentials.py -v`
Expected: PASS ×6

- [ ] **Step 5: 提交**

```bash
git add src/ft/credentials.py tests/test_credentials.py
git commit -m "feat: add ~/.ft/credentials.yaml loader with gitignore/chmod protection"
```

---

### Task 6: ccxt trade → CSV 映射（exchange_sync.trade_to_rows）

一笔 ccxt trade 展开成 1-3 行。现金计价对（quote ∈ {usdt,usd}）→ BUY/SELL；币币对 → SWAP_OUT+SWAP_IN；持仓币手续费 → 独立 FEE 行。

**Files:**
- Create: `src/ft/exchange_sync.py`（本任务先建骨架 + `trade_to_rows` 及其私有 helper）
- Test: `tests/test_exchange_sync.py`（追加，Task 1 已建文件）

**Interfaces:**
- Consumes: `stock.CSV_FIELDS`。
- Produces:
  - `trade_to_rows(trade: dict, account_name: str, provider: str) -> list[dict]` — 未知 side/缺 id/symbol 无法拆 base/quote → `ValueError`。
  - 私有：`_num(x) -> str`、`_format_trade_timestamp(ms) -> str`、常量 `CASH_QUOTES = {"usdt", "usd"}`。

**映射规则（精确）**：设 `base, quote = symbol.split("/")`（小写），`price`、`amount`（base 数量）、`cost`（= `trade["cost"]` 或 `price*amount`），`fee = trade.get("fee") or {}`，`fee_cost = fee.get("cost")`，`fee_ccy = str(fee.get("currency","")).lower()`。note 前缀 `f"{provider} tid:{tid}"`。

- A. `quote ∈ CASH_QUOTES`：
  - `side=buy` → `BUY base, shares=amount, price=price, amount=-cost`
  - `side=sell` → `SELL base, shares=amount, price=price, amount=+cost`
  - 手续费：`fee_cost>0` 且 `fee_ccy ∈ CASH_QUOTES` → 主行 `commission=fee_cost`；否则 `fee_cost>0` → 追加 `FEE ticker=fee_ccy shares=fee_cost`（主行 `commission=0`）。
- B. `quote ∉ CASH_QUOTES`（币币对）：
  - `side=buy`（用 quote 买 base）→ `SWAP_OUT ticker=quote shares=cost` + `SWAP_IN ticker=base shares=amount`
  - `side=sell` → `SWAP_OUT ticker=base shares=amount` + `SWAP_IN ticker=quote shares=cost`
  - 两 SWAP 行 note = `f"{provider} tid:{tid} swap:{tid}"`；`price`/`amount`/`commission` 留空。
  - 手续费：`fee_cost>0` → 追加 `FEE ticker=fee_ccy shares=fee_cost`（swap 语境即使 fee 是 usdt 也记 FEE 减持仓）。

- [ ] **Step 1: 写失败测试**

在 `tests/test_exchange_sync.py` 追加（文件顶部加 `import pytest`）：

```python
def _base_trade(**over):
    t = {"id": "T1", "timestamp": 1751852400000, "symbol": "BTC/USDT",
         "side": "buy", "price": 60000.0, "amount": 0.05, "cost": 3000.0,
         "fee": None}
    t.update(over)
    return t


def test_trade_to_rows_usdt_buy():
    from ft.exchange_sync import trade_to_rows
    rows = trade_to_rows(_base_trade(), account_name="币安", provider="kraken")
    assert len(rows) == 1
    r = rows[0]
    assert r["action"] == "BUY"
    assert r["ticker"] == "btc"
    assert r["shares"] == "0.05"
    assert r["price"] == "60000"
    assert r["amount"] == "-3000"
    assert r["commission"] == "0"
    assert r["currency"] == "USD"
    assert r["account_name"] == "币安"
    assert r["note"] == "kraken tid:T1"


def test_trade_to_rows_usdt_sell():
    from ft.exchange_sync import trade_to_rows
    rows = trade_to_rows(_base_trade(side="sell"), account_name="币安", provider="kraken")
    assert rows[0]["action"] == "SELL"
    assert rows[0]["amount"] == "3000"


def test_trade_to_rows_coin_pair_buy_makes_swap():
    from ft.exchange_sync import trade_to_rows
    t = _base_trade(symbol="ETH/BTC", side="buy", price=0.05, amount=10.0, cost=0.5)
    rows = trade_to_rows(t, account_name="币安", provider="kraken")
    assert [r["action"] for r in rows] == ["SWAP_OUT", "SWAP_IN"]
    out, inn = rows
    assert out["ticker"] == "btc" and out["shares"] == "0.5"   # 换出 quote=btc
    assert inn["ticker"] == "eth" and inn["shares"] == "10"    # 换入 base=eth
    assert out["note"] == inn["note"] == "kraken tid:T1 swap:T1"
    assert out["price"] == "" and out["amount"] == "" and out["commission"] == ""


def test_trade_to_rows_coin_pair_sell_reverses():
    from ft.exchange_sync import trade_to_rows
    t = _base_trade(symbol="ETH/BTC", side="sell", price=0.05, amount=10.0, cost=0.5)
    rows = trade_to_rows(t, account_name="币安", provider="kraken")
    out, inn = rows
    assert out["ticker"] == "eth" and out["shares"] == "10"    # 卖出 base=eth
    assert inn["ticker"] == "btc" and inn["shares"] == "0.5"   # 得到 quote=btc


def test_trade_to_rows_cash_fee_goes_to_commission():
    from ft.exchange_sync import trade_to_rows
    t = _base_trade(fee={"cost": 1.5, "currency": "USDT"})
    rows = trade_to_rows(t, account_name="币安", provider="kraken")
    assert len(rows) == 1
    assert rows[0]["commission"] == "1.5"


def test_trade_to_rows_holding_fee_makes_fee_row():
    from ft.exchange_sync import trade_to_rows
    t = _base_trade(fee={"cost": 0.001, "currency": "BNB"})
    rows = trade_to_rows(t, account_name="币安", provider="kraken")
    assert [r["action"] for r in rows] == ["BUY", "FEE"]
    fee_row = rows[1]
    assert fee_row["ticker"] == "bnb"
    assert fee_row["shares"] == "0.001"
    assert fee_row["note"] == "kraken tid:T1 fee"


def test_trade_to_rows_swap_fee_always_fee_row():
    from ft.exchange_sync import trade_to_rows
    t = _base_trade(symbol="ETH/BTC", side="buy", price=0.05, amount=10.0, cost=0.5,
                    fee={"cost": 0.01, "currency": "USDT"})
    rows = trade_to_rows(t, account_name="币安", provider="kraken")
    assert [r["action"] for r in rows] == ["SWAP_OUT", "SWAP_IN", "FEE"]
    assert rows[2]["ticker"] == "usdt"


def test_trade_to_rows_unknown_side_raises():
    from ft.exchange_sync import trade_to_rows
    with pytest.raises(ValueError, match="side"):
        trade_to_rows(_base_trade(side="transfer"), account_name="币安", provider="kraken")


def test_trade_to_rows_missing_id_raises():
    from ft.exchange_sync import trade_to_rows
    with pytest.raises(ValueError, match="id"):
        trade_to_rows(_base_trade(id=None), account_name="币安", provider="kraken")


def test_trade_to_rows_bad_symbol_raises():
    from ft.exchange_sync import trade_to_rows
    with pytest.raises(ValueError, match="symbol"):
        trade_to_rows(_base_trade(symbol="BTCUSDT"), account_name="币安", provider="kraken")
```

- [ ] **Step 2: 运行测试确认失败**

Run: `.venv/bin/python -m pytest tests/test_exchange_sync.py -v -k trade_to_rows`
Expected: FAIL，`ImportError: cannot import name 'trade_to_rows'`

- [ ] **Step 3: 创建 exchange_sync.py 骨架 + trade_to_rows**

创建 `src/ft/exchange_sync.py`：

```python
"""Generic ccxt exchange private-trades sync → ft crypto records."""
from __future__ import annotations

from datetime import datetime, timedelta, timezone
from decimal import Decimal, InvalidOperation

from .stock import CSV_FIELDS

CASH_QUOTES = {"usdt", "usd"}
UTC_PLUS_8 = timezone(timedelta(hours=8))


def _num(value) -> str:
    """Format a number as a normalized plain-decimal string ('3000' not '3000.0')."""
    try:
        d = Decimal(str(value))
    except (InvalidOperation, ValueError) as exc:
        raise ValueError(f"invalid numeric value: {value!r}") from exc
    if not d.is_finite():
        raise ValueError(f"invalid numeric value: {value!r}")
    text = format(d.normalize(), "f")
    return "0" if text == "-0" else text


def _format_trade_timestamp(ms) -> str:
    try:
        seconds = int(ms) / 1000
    except (TypeError, ValueError) as exc:
        raise ValueError(f"invalid trade timestamp: {ms!r}") from exc
    return (datetime.fromtimestamp(seconds, tz=timezone.utc)
            .astimezone(UTC_PLUS_8).strftime("%Y-%m-%d %H:%M:%S"))


def _blank_row(account_name: str) -> dict:
    row = {field: "" for field in CSV_FIELDS}
    row["currency"] = "USD"
    row["account_name"] = account_name
    return row


def trade_to_rows(trade: dict, account_name: str, provider: str) -> list[dict]:
    """Map one ccxt trade to 1-3 ft stock CSV rows. Raises on ambiguous shapes."""
    tid = trade.get("id")
    if tid is None or str(tid) == "":
        raise ValueError(f"trade 缺少 id: {trade!r}")
    tid = str(tid)

    symbol = str(trade.get("symbol", ""))
    if "/" not in symbol:
        raise ValueError(f"无法拆分 trade symbol '{symbol}'（应形如 BASE/QUOTE）")
    base, quote = (part.strip().lower() for part in symbol.split("/", 1))
    if not base or not quote:
        raise ValueError(f"无法拆分 trade symbol '{symbol}'")

    side = str(trade.get("side", "")).lower()
    if side not in {"buy", "sell"}:
        raise ValueError(f"未知 trade side: {trade.get('side')!r}")

    date = _format_trade_timestamp(trade.get("timestamp"))
    price = trade.get("price")
    amount = trade.get("amount")
    cost = trade.get("cost")
    if cost is None:
        cost = Decimal(str(price)) * Decimal(str(amount))

    fee = trade.get("fee") or {}
    fee_cost = fee.get("cost")
    fee_ccy = str(fee.get("currency", "")).lower()
    has_fee = fee_cost is not None and Decimal(str(fee_cost)) != 0

    note = f"{provider} tid:{tid}"
    rows: list[dict] = []

    if quote in CASH_QUOTES:
        cash_fee = has_fee and fee_ccy in CASH_QUOTES
        main = _blank_row(account_name)
        main["date"] = date
        main["action"] = "BUY" if side == "buy" else "SELL"
        main["ticker"] = base
        main["shares"] = _num(amount)
        main["price"] = _num(price)
        main["amount"] = _num(-Decimal(str(cost)) if side == "buy" else Decimal(str(cost)))
        main["commission"] = _num(fee_cost) if cash_fee else "0"
        main["note"] = note
        rows.append(main)
        if has_fee and not cash_fee:
            rows.append(_fee_row(account_name, date, fee_ccy, fee_cost, tid, provider))
    else:
        swap_note = f"{provider} tid:{tid} swap:{tid}"
        if side == "buy":
            out_ticker, out_shares, in_ticker, in_shares = quote, cost, base, amount
        else:
            out_ticker, out_shares, in_ticker, in_shares = base, amount, quote, cost
        for action, ticker, shares in (
            ("SWAP_OUT", out_ticker, out_shares),
            ("SWAP_IN", in_ticker, in_shares),
        ):
            r = _blank_row(account_name)
            r["date"] = date
            r["action"] = action
            r["ticker"] = ticker
            r["shares"] = _num(shares)
            r["note"] = swap_note
            rows.append(r)
        if has_fee:
            rows.append(_fee_row(account_name, date, fee_ccy, fee_cost, tid, provider))

    return rows


def _fee_row(account_name, date, fee_ccy, fee_cost, tid, provider) -> dict:
    r = _blank_row(account_name)
    r["date"] = date
    r["action"] = "FEE"
    r["ticker"] = fee_ccy
    r["shares"] = _num(fee_cost)
    r["note"] = f"{provider} tid:{tid} fee"
    return r
```

- [ ] **Step 4: 运行测试确认通过**

Run: `.venv/bin/python -m pytest tests/test_exchange_sync.py -v -k trade_to_rows`
Expected: PASS ×10

- [ ] **Step 5: 提交**

```bash
git add src/ft/exchange_sync.py tests/test_exchange_sync.py
git commit -m "feat: map ccxt trades to BUY/SELL/SWAP/FEE stock rows"
```

---

### Task 7: 交易所客户端与拉取（build_client / fetch_trades / validate_crypto_account）

**Files:**
- Modify: `src/ft/exchange_sync.py`（追加三个函数）
- Test: `tests/test_exchange_sync.py`（追加）

**Interfaces:**
- Consumes: `accounts.find_account`。
- Produces:
  - `validate_crypto_account(account_name: str, currency: str = "USD") -> None` — 目标须是已存在的 **crypto** 账户，否则 `ValueError`。
  - `build_client(provider: str, creds: dict)` — `getattr(ccxt, provider)({...})`；provider 非法 → `ValueError`（消息不含密钥）。
  - `fetch_trades(client, since=None, symbols=None, limit=1000) -> list[dict]` — 循环 `client.fetch_my_trades(symbol, since, limit)`，用最后一条 timestamp+1 作游标翻页，按 trade id 合并去重。

- [ ] **Step 1: 写失败测试**

在 `tests/test_exchange_sync.py` 追加：

```python
class _FakeClient:
    """Mock ccxt client: serves canned pages of my-trades."""
    def __init__(self, pages):
        self._pages = list(pages)
        self.calls = []

    def fetch_my_trades(self, symbol=None, since=None, limit=None):
        self.calls.append((symbol, since, limit))
        return self._pages.pop(0) if self._pages else []


def test_validate_crypto_account_ok(tmp_path, monkeypatch):
    from ft import accounts, exchange_sync
    monkeypatch.setattr(accounts, "find_account",
                        lambda name, currency=None: {"type": "crypto"})
    monkeypatch.setattr(exchange_sync, "find_account",
                        accounts.find_account, raising=False)
    # find_account is imported into exchange_sync; patch there:
    monkeypatch.setattr("ft.exchange_sync.find_account",
                        lambda name, currency="USD": {"type": "crypto"})
    exchange_sync.validate_crypto_account("币安")  # no raise


def test_validate_crypto_account_wrong_type_raises(monkeypatch):
    from ft import exchange_sync
    monkeypatch.setattr("ft.exchange_sync.find_account",
                        lambda name, currency="USD": {"type": "security"})
    with pytest.raises(ValueError, match="crypto"):
        exchange_sync.validate_crypto_account("IBKR")


def test_validate_crypto_account_missing_raises(monkeypatch):
    from ft import exchange_sync
    monkeypatch.setattr("ft.exchange_sync.find_account",
                        lambda name, currency="USD": None)
    with pytest.raises(ValueError, match="未知账户"):
        exchange_sync.validate_crypto_account("nope")


def test_build_client_unknown_provider_raises():
    from ft.exchange_sync import build_client
    with pytest.raises(ValueError, match="notanexchange"):
        build_client("notanexchange", {"api_key": "K", "api_secret": "S"})


def test_fetch_trades_paginates_and_dedupes():
    from ft.exchange_sync import fetch_trades
    page1 = [{"id": "A", "timestamp": 1000}, {"id": "B", "timestamp": 2000}]
    page2 = [{"id": "B", "timestamp": 2000}, {"id": "C", "timestamp": 3000}]
    client = _FakeClient([page1, page2, []])
    trades = fetch_trades(client, since=0, symbols=["BTC/USDT"], limit=2)
    assert [t["id"] for t in trades] == ["A", "B", "C"]
    # 第二页游标应为上页最后 timestamp+1
    assert client.calls[1][1] == 2001
```

- [ ] **Step 2: 运行测试确认失败**

Run: `.venv/bin/python -m pytest tests/test_exchange_sync.py -v -k "validate_crypto or build_client or fetch_trades"`
Expected: FAIL，`ImportError`/`AttributeError`

- [ ] **Step 3: 追加实现**

在 `src/ft/exchange_sync.py` 顶部 import 区加入：

```python
from .accounts import find_account
```

在文件末尾追加：

```python
def validate_crypto_account(account_name: str, currency: str = "USD") -> None:
    account = find_account(account_name, currency=currency)
    if account is None:
        raise ValueError(f"未知账户 '{account_name}' ({currency})，请先 ft acct add")
    if account.get("type") != "crypto":
        raise ValueError(
            f"账户 '{account_name}' ({currency}) 不是 crypto 类型，不能同步交易所成交"
        )


def build_client(provider: str, creds: dict):
    import ccxt
    if not hasattr(ccxt, provider):
        raise ValueError(f"ccxt 不支持交易所 '{provider}'")
    params = {"apiKey": creds["api_key"], "secret": creds["api_secret"]}
    if creds.get("password"):
        params["password"] = creds["password"]
    return getattr(ccxt, provider)(params)


def fetch_trades(client, since=None, symbols=None, limit=1000) -> list[dict]:
    """Paginate client.fetch_my_trades over symbols; merge & dedupe by trade id."""
    targets = list(symbols) if symbols else [None]
    seen: set[str] = set()
    out: list[dict] = []
    for symbol in targets:
        cursor = since
        while True:
            batch = client.fetch_my_trades(symbol, cursor, limit)
            if not batch:
                break
            fresh = 0
            for tr in batch:
                tid = str(tr.get("id"))
                if tid in seen:
                    continue
                seen.add(tid)
                out.append(tr)
                fresh += 1
            if len(batch) < limit:
                break
            last_ts = batch[-1].get("timestamp")
            if last_ts is None or fresh == 0:
                break
            cursor = int(last_ts) + 1
    return out
```

- [ ] **Step 4: 运行测试确认通过**

Run: `.venv/bin/python -m pytest tests/test_exchange_sync.py -v -k "validate_crypto or build_client or fetch_trades"`
Expected: PASS ×5

- [ ] **Step 5: 提交**

```bash
git add src/ft/exchange_sync.py tests/test_exchange_sync.py
git commit -m "feat: add crypto account validation, ccxt client builder, paginated trade fetch"
```

---

### Task 8: 顶层编排 sync_exchange（三态落库）+ 去重

把校验 → 拉取 → 映射 → 去重 → dry-run/`-o`/append 串起来。

**Files:**
- Modify: `src/ft/exchange_sync.py`（追加 `filter_new_rows` 薄封装与 `sync_exchange`）
- Test: `tests/test_exchange_sync.py`（追加集成测试）

**Interfaces:**
- Consumes: `credentials.load_credentials`/`ensure_credentials_gitignored`、`sync_common.filter_new_rows`/`write_stock_csv`、`stock.do_append`、`stock.CSV_FIELDS`。
- Produces:
  - `filter_new_rows(rows, records_dir=None, account_name=None) -> list[dict]` —（薄封装 `sync_common.filter_new_rows(..., prefix="tid")`）。
  - `sync_exchange(provider, account_name, since=None, dry_run=False, output=None, symbols=None, _client=None) -> list[dict]` — 顶层编排；`_client` 仅测试注入用（生产为 None → 走凭证+build_client）。打印 provider/账户/计数，**绝不**打印 key/secret。

- [ ] **Step 1: 写失败测试**

在 `tests/test_exchange_sync.py` 追加（复用 `_FakeClient` 与 `tmp_env`；此文件需自带 `tmp_env` fixture，见下）：

```python
import csv as _csv
import tempfile as _tempfile
from pathlib import Path as _Path


@pytest.fixture
def tmp_env():
    d = _Path(_tempfile.mkdtemp())
    from ft import models
    import ft.snapshot as snapshot_mod
    olds = (models.FT_DIR, models.RECORDS_DIR, models.ACCOUNTS_PATH,
            snapshot_mod.SNAPSHOT_PATH)
    models.FT_DIR = d
    models.RECORDS_DIR = d / "records"
    models.ACCOUNTS_PATH = d / "accounts.yaml"
    snapshot_mod.SNAPSHOT_PATH = d / "snapshot.yaml"
    yield d
    (models.FT_DIR, models.RECORDS_DIR, models.ACCOUNTS_PATH,
     snapshot_mod.SNAPSHOT_PATH) = olds
    import shutil
    shutil.rmtree(d, ignore_errors=True)


def _seed_crypto_account():
    from ft.accounts import save_accounts
    from ft import models
    save_accounts([{"name": "币安", "type": "crypto", "currency": "USD", "active": True}],
                  models.ACCOUNTS_PATH)


def test_sync_exchange_end_to_end_mixed(tmp_env):
    from ft.exchange_sync import sync_exchange
    from ft.stock import load_snapshot, verify_security
    _seed_crypto_account()

    trades = [
        {"id": "T1", "timestamp": 1751852400000, "symbol": "BTC/USDT", "side": "buy",
         "price": 60000.0, "amount": 0.1, "cost": 6000.0, "fee": None},
        {"id": "T2", "timestamp": 1751856000000, "symbol": "ETH/BTC", "side": "buy",
         "price": 0.05, "amount": 1.0, "cost": 0.05,
         "fee": {"cost": 0.001, "currency": "BNB"}},
    ]
    client = _FakeClient([trades, []])

    # dry-run：不写入
    new = sync_exchange("kraken", account_name="币安", dry_run=True,
                        symbols=["BTC/USDT", "ETH/BTC"], _client=client)
    assert len(new) == 4          # BUY + (SWAP_OUT+SWAP_IN+FEE)
    assert not (tmp_env / "records" / "security").exists()

    # 真实 append
    client2 = _FakeClient([trades, []])
    sync_exchange("kraken", account_name="币安",
                  symbols=["BTC/USDT", "ETH/BTC"], _client=client2)
    snap = load_snapshot()
    acct = snap["accounts"]["security"]["币安"]
    # BTC: 买入 0.1，换出 0.05 → 0.05
    assert acct["positions"]["btc"]["shares"] == pytest.approx(0.05)
    # ETH: 换入 1.0
    assert acct["positions"]["eth"]["shares"] == pytest.approx(1.0)
    ok, _ = verify_security()
    assert ok is True


def test_sync_exchange_is_idempotent(tmp_env):
    from ft.exchange_sync import sync_exchange
    _seed_crypto_account()
    trades = [{"id": "T1", "timestamp": 1751852400000, "symbol": "BTC/USDT",
               "side": "buy", "price": 60000.0, "amount": 0.1, "cost": 6000.0, "fee": None}]
    sync_exchange("kraken", account_name="币安", _client=_FakeClient([trades, []]))
    # 再同步一次：0 新增
    new = sync_exchange("kraken", account_name="币安", _client=_FakeClient([trades, []]))
    assert new == []


def test_sync_exchange_writes_output_csv(tmp_env):
    from ft.exchange_sync import sync_exchange
    _seed_crypto_account()
    trades = [{"id": "T1", "timestamp": 1751852400000, "symbol": "BTC/USDT",
               "side": "sell", "price": 60000.0, "amount": 0.1, "cost": 6000.0, "fee": None}]
    out = tmp_env / "out.csv"
    sync_exchange("kraken", account_name="币安", dry_run=True, output=str(out),
                  _client=_FakeClient([trades, []]))
    with out.open(encoding="utf-8") as f:
        rows = list(_csv.DictReader(f))
    assert rows[0]["action"] == "SELL"
```

- [ ] **Step 2: 运行测试确认失败**

Run: `.venv/bin/python -m pytest tests/test_exchange_sync.py -v -k sync_exchange`
Expected: FAIL，`ImportError: cannot import name 'sync_exchange'`

- [ ] **Step 3: 追加实现**

在 `src/ft/exchange_sync.py` 顶部 import 区加入：

```python
import csv
import tempfile
from pathlib import Path

from . import sync_common
from .stock import do_append
from .credentials import load_credentials, ensure_credentials_gitignored
```

在文件末尾追加：

```python
def _since_to_ms(since: str | None) -> int | None:
    if not since:
        return None
    dt = datetime.strptime(since, "%Y-%m-%d").replace(tzinfo=UTC_PLUS_8)
    return int(dt.timestamp() * 1000)


def filter_new_rows(rows, records_dir=None, account_name=None) -> list[dict]:
    return sync_common.filter_new_rows(
        rows, records_dir=records_dir, account_name=account_name, prefix="tid"
    )


def sync_exchange(provider, account_name, since=None, dry_run=False,
                  output=None, symbols=None, _client=None) -> list[dict]:
    """Fetch private trades via ccxt, map, dedupe, and (unless dry-run) append."""
    validate_crypto_account(account_name)

    client = _client
    if client is None:
        creds = load_credentials(provider)
        ensure_credentials_gitignored()
        client = build_client(provider, creds)

    trades = fetch_trades(client, since=_since_to_ms(since), symbols=symbols)
    rows: list[dict] = []
    for trade in trades:
        rows.extend(trade_to_rows(trade, account_name, provider))
    # 稳定排序：同 tid 的 SWAP_OUT→SWAP_IN→FEE 同 timestamp，靠稳定性保序。
    rows.sort(key=lambda r: r["date"])
    new_rows = filter_new_rows(rows, account_name=account_name)

    print(f"交易所: {provider}; 账户: {account_name}")
    print(f"成交: {len(trades)}; 映射行: {len(rows)}; 新增行: {len(new_rows)}")

    if output:
        sync_common.write_stock_csv(new_rows, output)
        print(f"✅ 已写出待导入 CSV: {output}")

    if dry_run or not new_rows:
        if dry_run:
            print("DRY-RUN: 未写入 ft records")
        elif not new_rows:
            print("✅ 没有新增成交")
        return new_rows

    with tempfile.NamedTemporaryFile("w", newline="", encoding="utf-8",
                                     suffix=".csv", delete=False) as f:
        writer = csv.DictWriter(f, fieldnames=CSV_FIELDS)
        writer.writeheader()
        writer.writerows(new_rows)
        tmp_path = f.name
    try:
        if not do_append(tmp_path):
            raise ValueError("交易所成交 append 失败")
    finally:
        Path(tmp_path).unlink(missing_ok=True)
    return new_rows
```

- [ ] **Step 4: 运行测试确认通过**

Run: `.venv/bin/python -m pytest tests/test_exchange_sync.py -v -k sync_exchange`
Expected: PASS ×3

- [ ] **Step 5: 跑全量 exchange 测试**

Run: `.venv/bin/python -m pytest tests/test_exchange_sync.py -q`
Expected: 全绿

- [ ] **Step 6: 提交**

```bash
git add src/ft/exchange_sync.py tests/test_exchange_sync.py
git commit -m "feat: sync_exchange orchestration with dry-run/output/append tri-state"
```

---

### Task 9: 手工兑换命令 do_swap + `ft stock swap`

供 App 币币兑换等手工记账。写两条 SWAP 行（OUT→IN 顺序，共享 `swap:<id>`），同步更新 snapshot（成本结转，不碰现金）。

**Files:**
- Modify: `src/ft/stock.py`（新增 `do_swap`，置于 `do_sell` 之后约 580 行）
- Modify: `src/ft/cli.py:83-149`（新增 `swap` 子命令）与 `src/ft/cli.py:515-526`（分发）
- Test: `tests/test_stock.py`（追加）

**Interfaces:**
- Consumes: `load_snapshot`、`_ensure_account`、`_save_snapshot_and_record_trade`、`record_trade`、`_now`、`_ensure_finite_values`。
- Produces:
  - `do_swap(account_name, from_ticker, from_shares, to_ticker, to_shares, currency="USD", note="", date=None) -> bool` — from 持仓不足 → `ValueError`。写入两行，note 含 `swap:<id>`，`id = date+from+to` 派生。

- [ ] **Step 1: 写失败测试**

在 `tests/test_stock.py` 末尾追加：

```python
def test_do_swap_conserves_cost_and_ignores_cash(tmp_env):
    from ft.accounts import save_accounts
    from ft import models
    from ft.stock import do_deposit, do_buy, do_swap, load_snapshot, verify_security

    save_accounts([{"name": "币安", "type": "crypto", "currency": "USD", "active": True}],
                  models.ACCOUNTS_PATH)
    do_deposit(amount=100000, currency="USD", account_name="币安",
               date="2026-07-07 08:00:00")
    do_buy(ticker="btc", shares=1, price=60000, commission=0, currency="USD",
           account_name="币安", date="2026-07-07 09:00:00")

    do_swap(account_name="币安", from_ticker="btc", from_shares=0.5,
            to_ticker="eth", to_shares=10, date="2026-07-07 10:00:00")

    snap = load_snapshot()
    acct = snap["accounts"]["security"]["币安"]
    assert acct["positions"]["btc"]["shares"] == pytest.approx(0.5)
    assert acct["positions"]["eth"]["shares"] == pytest.approx(10.0)
    # ETH 成本 = 释放的 BTC 成本 0.5*60000 = 30000 → 均价 3000
    assert acct["positions"]["eth"]["avg_cost"] == pytest.approx(3000.0)
    # 现金：deposit 100000 - buy 60000 = 40000，swap 不动
    assert acct["cash"] == pytest.approx(40000.0)
    ok, _ = verify_security()
    assert ok is True


def test_do_swap_insufficient_from_shares_raises(tmp_env):
    from ft.accounts import save_accounts
    from ft import models
    from ft.stock import do_swap

    save_accounts([{"name": "币安", "type": "crypto", "currency": "USD", "active": True}],
                  models.ACCOUNTS_PATH)
    with pytest.raises(ValueError, match="持仓不足"):
        do_swap(account_name="币安", from_ticker="btc", from_shares=1,
                to_ticker="eth", to_shares=10, date="2026-07-07 10:00:00")
```

- [ ] **Step 2: 运行测试确认失败**

Run: `.venv/bin/python -m pytest tests/test_stock.py::test_do_swap_conserves_cost_and_ignores_cash tests/test_stock.py::test_do_swap_insufficient_from_shares_raises -v`
Expected: FAIL，`ImportError: cannot import name 'do_swap'`

- [ ] **Step 3: 实现 do_swap**

在 `src/ft/stock.py` 的 `do_sell` 函数结束后（约 580 行、`def do_deposit` 之前）插入：

```python
def do_swap(
    account_name: str,
    from_ticker: str,
    from_shares: float,
    to_ticker: str,
    to_shares: float,
    currency: str = "USD",
    note: str = "",
    date: Optional[str] = None,
):
    """Crypto-to-crypto swap: carry from_ticker's released cost to to_ticker.

    Records two rows (SWAP_OUT then SWAP_IN) sharing swap:<id>. Cash untouched.
    """
    if date is None:
        date = _now()
    _ensure_finite_values(from_shares=from_shares, to_shares=to_shares)
    date_key = date[:10]

    snap = load_snapshot()
    acct = _ensure_account(snap, account_name, currency)

    from_pos = acct["positions"].get(from_ticker)
    if from_pos is None or round(from_pos["shares"] - from_shares, 10) < 0:
        have = from_pos["shares"] if from_pos else 0
        raise ValueError(
            f"{account_name} 的 {from_ticker} 持仓不足：有 {have}，需 {from_shares}"
        )

    released = round(from_shares * from_pos["avg_cost"], 2)
    from_pos["shares"] = round(from_pos["shares"] - from_shares, 10)
    if from_pos["shares"] == 0:
        del acct["positions"][from_ticker]

    to_pos = acct["positions"].setdefault(to_ticker, {"shares": 0, "avg_cost": 0.0})
    old_cost = round(to_pos["avg_cost"] * to_pos["shares"], 2)
    to_pos["shares"] = round(to_pos["shares"] + to_shares, 10)
    to_pos["avg_cost"] = round((old_cost + released) / to_pos["shares"], 2) \
        if to_pos["shares"] != 0 else 0.0

    snap["updated_at"] = date_key
    swap_id = f"{date_key}-{from_ticker}-{to_ticker}"
    base_note = (note + " ").lstrip() if note else ""
    swap_note = f"{base_note}swap:{swap_id}".strip()

    # 先存快照，再写两行（OUT→IN），任一失败由 record_trade 抛出。
    save_snapshot(snap)
    record_trade(date=date, action="SWAP_OUT", ticker=from_ticker,
                 shares=from_shares, price=0, amount=0, commission=0,
                 currency=currency, account_name=account_name, note=swap_note)
    record_trade(date=date, action="SWAP_IN", ticker=to_ticker,
                 shares=to_shares, price=0, amount=0, commission=0,
                 currency=currency, account_name=account_name, note=swap_note)
    print(f"✅ 兑换 {_fmt_shares(from_shares)} {from_ticker} → "
          f"{_fmt_shares(to_shares)} {to_ticker} ({account_name})")
    return True
```

> 说明：`record_trade` 写 `shares/price/amount` 时 `str(0)` → `"0"`，replay 读 SWAP 行只用 `shares` 与 note 中的 `swap:<id>`，`price/amount` 为 `"0"` 不影响成本结转（`_replay_security_rows` 的 SWAP 分支不读 price/amount）。快照与 CSV replay 结果一致（测试 `verify_security` 验证）。

- [ ] **Step 4: 在 CLI 注册 swap 子命令**

编辑 `src/ft/cli.py`，在 `dep_p = stk_sub.add_parser("deposit", ...)` 之前（约第 106 行前）插入：

```python
    swap_p = stk_sub.add_parser("swap", help="币币兑换（持仓换持仓，成本结转）")
    swap_p.add_argument("--from-ticker", required=True)
    swap_p.add_argument("--from-shares", type=float, required=True)
    swap_p.add_argument("--to-ticker", required=True)
    swap_p.add_argument("--to-shares", type=float, required=True)
    swap_p.add_argument("--account", required=True)
    swap_p.add_argument("--currency", default="USD", choices=["CNY", "USD", "HKD"])
    swap_p.add_argument("--note", default="")
    swap_p.add_argument("--date")
```

再在分发处（`src/ft/cli.py` 约 518 行 `elif args.stock_cmd == "sell":` 之后）插入：

```python
        elif args.stock_cmd == "swap":
            from .stock import do_swap
            try:
                do_swap(args.account, args.from_ticker, args.from_shares,
                        args.to_ticker, args.to_shares, args.currency,
                        args.note, args.date)
            except ValueError as exc:
                print(f"❌ {exc}")
                sys.exit(1)
```

（`do_swap` 在函数顶部的 `from .stock import (...)` 里未列出，故此处就地 `from .stock import do_swap`。）

- [ ] **Step 5: 运行测试确认通过**

Run: `.venv/bin/python -m pytest tests/test_stock.py::test_do_swap_conserves_cost_and_ignores_cash tests/test_stock.py::test_do_swap_insufficient_from_shares_raises -v`
Expected: PASS ×2

- [ ] **Step 6: 提交**

```bash
git add src/ft/stock.py src/ft/cli.py tests/test_stock.py
git commit -m "feat: add do_swap and 'ft stock swap' for manual crypto-to-crypto swaps"
```

---

### Task 10: CLI 泛化 `ft stock sync <provider>`

`sync` 子命令组做成 provider 分发：`polymarket` 分支原样保留；其它 provider 走 `exchange_sync.sync_exchange`。

**Files:**
- Modify: `src/ft/cli.py:151-161`（sync 子命令参数）
- Modify: `src/ft/cli.py:544-562`（sync 分发）
- Test: `tests/test_cli.py`（追加）

**Interfaces:**
- Consumes: `exchange_sync.sync_exchange`、既有 `polymarket_sync.sync_polymarket`。
- Produces: `ft stock sync kraken --account 币安 --dry-run [--since YYYY-MM-DD] [-o csv] [--symbol PAIR ...]`。

- [ ] **Step 1: 写失败测试**

先看既有 `tests/test_cli.py` 如何调用 CLI（`grep -n "def main\|main(\|sys.argv\|capsys" tests/test_cli.py | head`），沿用同样风格。在 `tests/test_cli.py` 末尾追加：

```python
def test_stock_sync_exchange_dispatches_to_sync_exchange(monkeypatch, capsys):
    """`ft stock sync kraken` 应调用 exchange_sync.sync_exchange 并透传参数。"""
    import sys
    from ft import cli

    captured = {}

    def fake_sync_exchange(provider, account_name, since=None, dry_run=False,
                           output=None, symbols=None):
        captured.update(provider=provider, account_name=account_name,
                        since=since, dry_run=dry_run, output=output, symbols=symbols)
        return []

    monkeypatch.setattr("ft.exchange_sync.sync_exchange", fake_sync_exchange)
    monkeypatch.setattr(sys, "argv", [
        "ft", "stock", "sync", "kraken", "--account", "币安",
        "--dry-run", "--since", "2026-01-01", "--symbol", "BTC/USDT",
    ])
    cli.main()

    assert captured["provider"] == "kraken"
    assert captured["account_name"] == "币安"
    assert captured["dry_run"] is True
    assert captured["since"] == "2026-01-01"
    assert captured["symbols"] == ["BTC/USDT"]


def test_stock_sync_polymarket_still_dispatches(monkeypatch):
    """polymarket 分支零回归：仍调用 sync_polymarket。"""
    import sys
    from ft import cli

    called = {}
    monkeypatch.setattr("ft.polymarket_sync.sync_polymarket",
                        lambda **kw: called.update(kw) or [])
    monkeypatch.setattr(sys, "argv", [
        "ft", "stock", "sync", "polymarket", "--wallet", "0xabc", "--dry-run",
    ])
    cli.main()
    assert called["wallet"] == "0xabc"
    assert called["dry_run"] is True
```

- [ ] **Step 2: 运行测试确认失败**

Run: `.venv/bin/python -m pytest tests/test_cli.py::test_stock_sync_exchange_dispatches_to_sync_exchange -v`
Expected: FAIL（当前非 polymarket 分支只打印“请指定 sync 子命令”并 exit 1，`SystemExit`）

- [ ] **Step 3: 给 sync 加通用 provider 子命令**

编辑 `src/ft/cli.py`，在 `sync_pm.add_argument("--max-pages", ...)`（约 161 行）之后追加通用交易所子命令。ccxt 常见 provider 各加一个 subparser，共享参数集：

```python
    for _provider in ("kraken", "okx", "binance", "coinbase", "bybit"):
        _sp = sync_sub.add_parser(_provider, help=f"从 {_provider} 同步私有成交（ccxt）")
        _sp.add_argument("--account", required=True, help="目标 crypto 账户名")
        _sp.add_argument("--since", help="起始日期 YYYY-MM-DD（增量同步）")
        _sp.add_argument("--dry-run", action="store_true", help="只拉取/去重/预览，不写入")
        _sp.add_argument("-o", "--output", help="把新增记录写出为 stock CSV")
        _sp.add_argument("--symbol", action="append", dest="symbols",
                         help="只同步指定交易对，可重复（调试用）")
```

- [ ] **Step 4: 扩展 sync 分发**

编辑 `src/ft/cli.py` 的 sync 分发块（约 544-562 行）。把：

```python
        elif args.stock_cmd == "sync":
            if args.sync_cmd == "polymarket":
                from .polymarket_sync import sync_polymarket
                try:
                    sync_polymarket(
                        wallet=args.wallet,
                        proxy_wallet=args.proxy_wallet,
                        account_name=args.account,
                        dry_run=args.dry_run,
                        output=args.output,
                        limit=args.limit,
                        max_pages=args.max_pages,
                    )
                except ValueError as exc:
                    print(f"❌ {exc}")
                    sys.exit(1)
            else:
                print("❌ 请指定 sync 子命令，例如: ft stock sync polymarket")
                sys.exit(1)
```

改为：

```python
        elif args.stock_cmd == "sync":
            if not args.sync_cmd:
                print("❌ 请指定 sync provider，例如: ft stock sync polymarket / ft stock sync kraken")
                sys.exit(1)
            if args.sync_cmd == "polymarket":
                from .polymarket_sync import sync_polymarket
                try:
                    sync_polymarket(
                        wallet=args.wallet,
                        proxy_wallet=args.proxy_wallet,
                        account_name=args.account,
                        dry_run=args.dry_run,
                        output=args.output,
                        limit=args.limit,
                        max_pages=args.max_pages,
                    )
                except ValueError as exc:
                    print(f"❌ {exc}")
                    sys.exit(1)
            else:
                from .exchange_sync import sync_exchange
                try:
                    sync_exchange(
                        provider=args.sync_cmd,
                        account_name=args.account,
                        since=args.since,
                        dry_run=args.dry_run,
                        output=args.output,
                        symbols=args.symbols,
                    )
                except ValueError as exc:
                    print(f"❌ {exc}")
                    sys.exit(1)
```

- [ ] **Step 5: 运行测试确认通过**

Run: `.venv/bin/python -m pytest tests/test_cli.py::test_stock_sync_exchange_dispatches_to_sync_exchange tests/test_cli.py::test_stock_sync_polymarket_still_dispatches -v`
Expected: PASS ×2

- [ ] **Step 6: 提交**

```bash
git add src/ft/cli.py tests/test_cli.py
git commit -m "feat: generalize 'ft stock sync' to dispatch ccxt exchange providers"
```

---

### Task 11: 全量回归与收尾

**Files:** 无新增，仅验证。

- [ ] **Step 1: 跑整套测试**

Run: `.venv/bin/python -m pytest -q`
Expected: 全绿（含既有 polymarket/crypto/transfer/import 等，零回归）

- [ ] **Step 2: CLI 冒烟（真实解析，不触网）**

Run:
```bash
.venv/bin/ft stock sync 2>&1 | head -3
.venv/bin/ft stock swap --help 2>&1 | head -5
```
Expected: 第一条打印“请指定 sync provider …”；第二条打印 swap 子命令用法（含 `--from-ticker`）。

- [ ] **Step 3: 更新 SKILL.md（如适用）**

若 `SKILL.md` 列了 `ft stock sync` / stock 子命令清单，追加 `ft stock sync <exchange>` 与 `ft stock swap` 的一行用法说明；否则跳过。

Run: `grep -n "stock sync\|stock swap" SKILL.md | head`

- [ ] **Step 4: 最终提交**

```bash
git add -A
git commit -m "docs: document exchange sync and swap commands"
```

---

## Self-Review

**Spec coverage（逐节核对）**：
- 命令泛化 `ft stock sync <provider>` → Task 10。参数 `--account/--since/--dry-run/-o/--symbol` → Task 10 Step 3。
- 新模块 `exchange_sync.py`（validate/build_client/fetch_trades/trade_to_rows/filter_new_rows/sync_exchange）→ Task 6/7/8。
- ccxt trade→CSV 映射（A 现金对 BUY/SELL、B 币币对 SWAP、fee 分流）→ Task 6。
- SWAP 账本原语（存储、replay、排序保证、pending_swaps、IN 缺配对报错）→ Task 2；下单命令 do_swap + `ft stock swap` → Task 9。
- FEE 账本原语（现金 fee 走 commission、持仓 fee 走 FEE 行、swap fee always FEE、replay 均价核销）→ Task 6（映射）+ Task 2（replay）。
- 凭证 `credentials.py`（load/ensure_gitignored/不泄密）→ Task 5。
- 依赖 ccxt → Task 1。
- 数据模型改动（VALID_ACTIONS 增 3、replay 三分支、CSV 10 列不变、do_append 已放开 crypto）→ Task 2/3。
- 错误处理（provider 非法、credentials 缺失、trade 缺字段、SWAP_IN 无配对、ccxt 失败向上抛 CLI exit 1）→ Task 5/6/7/8/10。
- 测试（trade_to_rows / replay / load_credentials / filter_new_rows / sync_exchange 集成 / swap 手工 / verify）→ Task 2/4/5/6/8/9。
- 附带修复：`repair_security` crypto 币种、`do_append` 空数值列 → Task 3（spec 未列但阻塞落库，必须做）。

**YAGNI 遵守**：不做实时下单、不做非 trade 类型识别、不改 polymarket 映射逻辑、不做 B 方案市价盈亏——均未进任何 Task。

**Type consistency**：`row_identity`/`write_stock_csv`/`filter_new_rows`（`sync_common`，prefix 参数）、`trade_to_rows(trade, account_name, provider)`、`sync_exchange(provider, account_name, since, dry_run, output, symbols, _client)`、`do_swap(account_name, from_ticker, from_shares, to_ticker, to_shares, currency, note, date)`、`load_credentials(provider)`、`build_client(provider, creds)`、`fetch_trades(client, since, symbols, limit)`、`validate_crypto_account(account_name, currency)` 在定义处与调用处签名一致。replay 用 note 中 `swap:<id>` 配对，映射写 note `swap:<tid>`、do_swap 写 `swap:<date-from-to>`——均为 `swap:` 前缀，`re.search(r"swap:(\S+)")` 可解析。
