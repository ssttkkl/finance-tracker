# Unified Snapshot for All Account Types

## Context

当前快照设计只覆盖 security 类型（`snapshot_security.yaml`）。cash/loan/lend 类型的余额每次查询都从 CSV 文件全量扫描计算，速度慢且无法通过对比 CSV 来发现不一致。

需要将快照覆盖到所有账户类型，统一快照文件，让查询秒出，同时保留 CSV 作为审计日志。

## Architecture

**统一快照文件：** `~/.ft/snapshot.yaml` 替代 `~/.ft/snapshot_security.yaml`。

**数据结构：**

```yaml
updated_at: "2026-06-12 15:30:00"
accounts:
  cash:
    支付宝余额: 2414.94
    微信零钱: 1460.76
    工行借记卡: 17653.26
  loan:
    工行信用卡(1200): -13349.95
  lend: {}
  security:
    IBKR:
      currency: USD
      cash: 9979.98
      positions:
        nvda.us:
          shares: 45
          avg_cost: 224.14
```

- cash/loan/lend：直接存余额（float），简单直接
- security：保持现有结构化格式（含持仓明细、币种、均价）

## 读/写时机

### 写操作更新快照

| 操作 | 快照变更 |
|------|---------|
| `ft append merged.csv` | 对每行，找到对应账户，余额 += amount。checkin 行设置余额。transfer 行跳过（在 transfer 命令中处理） |
| `ft checkin A --balance N` | 直接覆盖 `accounts[type][A] = N` |
| `ft transfer` | 从账余额 -= amount，到账余额 += amount |
| `ft stock buy/sell/init/deposit/withdraw/dividend/checkin` | 更新 `accounts.security` 下的结构化数据 |

### 查询从快照读

- `ft acct list` — 从快照读余额，不再扫 CSV
- `ft report` — 从快照读，不再扫 CSV
- `ft stock list` — 从快照读，不变

### 修复

- `ft verify --fix` — 扫描全部 `records/{cash,loan,lend,security}/*.csv`，全量重建 snapshot.yaml

## 模块变更

### 新建 `src/ft/snapshot.py`

公共快照管理模块：

```python
SNAPSHOT_PATH = FT_DIR / "snapshot.yaml"

def load_snapshot(path=None) -> dict
def save_snapshot(data: dict, path=None)
def get_balance(snap: dict, acct_name: str) -> tuple[float | None, str | None]
    # 返回 (余额, 类型) 或 (None, None)
    # 从快照中查找账户，返回余额和类型

def set_balance(snap: dict, acct_name: str, type_: str, balance: float)
    # 设置 cash/loan/lend 的余额

def update_balance(snap: dict, acct_name: str, delta: float)
    # 余额 += delta（用于 append 和 transfer）
```

### 修改 `src/ft/stock.py`

- 移除 `SNAPSHOT_PATH = ... / "snapshot_security.yaml"`
- `load_snapshot` / `save_snapshot` 改为从 `src/ft/snapshot.py` 导入
- `repair_security` 改为写入 `snapshot.yaml` 的 `accounts.security` 段

### 修改 `src/ft/append.py`

`do_append` 追加 CSV 后，根据每行的 `account_name` 和 `amount` 更新快照：

```python
snap = load_snapshot()
for row in rows:
    if row["category"] == "checkin":
        # checkin 行：从 description 解析余额，直接设置
        set_balance(snap, row["account_name"], type_, parsed_balance)
    elif row["category"] != "transfer":
        # 普通行：余额 += amount
        update_balance(snap, row["account_name"], float(row["amount"]))
save_snapshot(snap)
```

### 修改 `src/ft/report.py`

`report_networth` 从快照读取所有账户余额，不再调用 `_read_records`：

```python
snap = load_snapshot()
for type_, accounts in snap.get("accounts", {}).items():
    for acct_name, balance_data in accounts.items():
        if type_ in ("cash", "loan", "lend"):
            # balance_data 直接是 float
        elif type_ == "security":
            # balance_data 是 dict 含 cash + positions
```

### 修改 `src/ft/acct.py`

`_compute_balance` 改为从快照读取，不再扫描 CSV：

```python
def _compute_balance(account_name, currency):
    snap = load_snapshot()
    bal, type_ = get_balance(snap, account_name)
    return bal or 0.0
```

### 修改 `src/ft/cli.py`

- checkin 命令：写 CSV 后更新快照
- stock checkin：走公共 snapshot 模块

### 修改 `src/ft/transfer.py`

转账后更新 from 和 to 账户的快照余额。

### 删除文件

`src/ft/stock.py` 中的 `SNAPSHOT_PATH` 和独立 `load_snapshot`/`save_snapshot` 迁移到公共模块。

## 迁移

首次 `ft verify --fix` 从 CSV 重建完整快照：

```bash
ft verify --fix
# 扫描 records/{cash,loan,lend,security}/*.csv
# 重建 snapshot.yaml
```

完成后删除 `~/.ft/snapshot_security.yaml`。

## 数据流对比

### 改版前
```
ft append → 写 CSV
ft checkin → 写 CSV
ft report → 扫全部 CSV 文件计算余额 ← 慢
ft acct list → 扫 CSV 计算余额 ← 慢
```

### 改版后
```
ft append → 写 CSV + 更新 snapshot.yaml
ft checkin → 写 CSV + 更新 snapshot.yaml
ft report → 读 snapshot.yaml ← 秒出
ft acct list → 读 snapshot.yaml ← 秒出
ft verify --fix → 从 CSV 全量重建 snapshot.yaml
```
