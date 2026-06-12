# CSV-Only Architecture: 去数据库化设计

## Context

Finance Tracker 当前混合两种存储：
- **转换流水线**（convert / merge）：已纯 CSV
- **持久化 & 报告**（load / report / list / checkin / transfer / acct）：SQLite

用户要求去掉 SQLite，全部改为 CSV 文件存储。动机：
- 人类可以用 Excel/编辑器直接查看和修改数据
- 按天分文件，方便定位和历史修改
- 唯一事实源，不需要 CSV↔DB 同步

## Data Layout

```
~/.ft/
├── mapping.yaml              # 已有，支付方式→account_name 映射
├── accounts.yaml             # 新建，账户元数据
└── records/                  # 新建，按天交易记录
    ├── cash/
    │   ├── 2026-01-01.csv
    │   ├── 2026-01-02.csv
    │   └── 2026-06-12.csv
    ├── loan/
    ├── lend/
    └── security/
```

`records/{type}/` 按账户类型分目录。`ft append` 时根据 `accounts.yaml` 中 `account_name → type` 路由到对应子目录。

## accounts.yaml

```yaml
accounts:
  - name: 支付宝余额
    type: cash
    currency: CNY
    active: true
  - name: 工行信用卡(1200)
    type: loan
    currency: CNY
    active: true
  - name: IBKR
    type: security
    currency: USD
    active: true
```

字段：
- `name`：账户名，与 CSV 中 `account_name` 列对应
- `type`：cash / loan / lend / security
- `currency`：CNY / USD / HKD
- `active`：true / false（停用账户在报告中隐藏）

首次运行 `ft acct list` 时自动创建带模板占位符的默认文件（类似 mapping.yaml）。

## Records CSV Format

每条 CSV 与现有 `merged.csv` 格式完全一致，10 列：

```
date,amount,currency,counterparty,description,category,account_name,source,platform,bill_source
```

- `date`：`YYYY-MM-DD HH:MM:SS`，精确到秒
- `amount`：正=流入，负=流出
- `category`：income / expense / transfer / checkin
- `checkin` 类别：amount=0，description 记录快照余额（如 `余额校准 ¥5000.00`）

**文件内排序：** 按 `date` 升序。`ft append` 追加后重排当天文件。

## CLI Changes

### 保留（重写后端）

| 命令 | 变更 |
|------|------|
| `ft convert` | 不变 |
| `ft merge` | 不变 |
| `ft report [--month YYYY-MM]` | CSV 后端，扫描 `records/` |
| `ft list [--month\|--account\|--category\|--limit]` | CSV 后端 |
| `ft checkin <account> --balance <N>` | CSV 后端，写当日 CSV |
| `ft transfer --from A --to B --amount N [--to-amount M]` | CSV 后端 |

### 重命名

| 旧命令 | 新命令 | 说明 |
|--------|--------|------|
| `ft load` | `ft append` | CSV→按天 CSV 追加 |

### 退役

| 命令 | 说明 |
|------|------|
| `ft init` | 不再需要初始化 DB。首次使用时自动创建目录/文件。 |
| `ft import` | 旧的 DB 一步导入，已被 convert→merge→append 替代 |
| `ft log` | 导入历史日志表，不再维护 |

### 删除的源文件

| 文件 | 说明 |
|------|------|
| `src/ft/db.py` | SQLite 连接/初始化 |
| `src/ft/txn.py` | insert_txn / insert_transfer_pair |
| `src/ft/load.py` | 重写为 append.py |

## Module Design

### models.py

```python
RECORDS_DIR = Path.home() / ".ft" / "records"
ACCOUNTS_PATH = Path.home() / ".ft" / "accounts.yaml"
# 删除 DB_DIR / DB_PATH
# 保留：CURRENCIES, CURRENCY_SYMBOLS, ACCOUNT_TYPES, ACCOUNT_LABELS, ACCOUNT_ICONS,
#        CATEGORIES, CATEGORY_LABELS, SOURCE_LABELS
```

### append.py（替换 load.py）

```
ft append merged.csv
```

1. 读 CSV → 按 `date` 的日期部分分组
2. 对每组：查 `accounts.yaml` 中 `account_name → type`
3. 写入/追加到 `records/{type}/YYYY-MM-DD.csv`
4. 追加后按 `date` 升序排序当天文件并写回
5. 打印统计（各日期各新增 N 条）

**不去重**——merge 阶段已完成跨源去重。

### report.py

**核心：** 扫描 `records/{type}/` 下所有 CSV 文件。

`report_networth`：
1. 对每个账户，找最近一次 checkin 行（category=checkin）
2. 余额 = checkin.snapshot_balance + checkin 之后所有非 checkin 非 transfer 行的 SUM(amount)
3. 无 checkin → 从全部记录开始算（折为从 0 开始）
4. 按 currency 分组展示

`report_expense [--month]`：
- 过滤 category=expense（amount<0），按 source / description 汇总
- --month 时只读对应月份文件

`report_income [--month]`：
- 过滤 category=income（amount>0），按 description 汇总

`report_flow`：
- 过滤 category=transfer，汇总内部转账

`list_txns`：
- 支持 --month / --account / --category / --limit 过滤
- 按 date 降序排列

### acct.py

读写 `~/.ft/accounts.yaml`：
- `acct_add(name, type, currency)` → 追加新账户
- `acct_list()` → 列出所有账户 + 从 `records/` 计算的余额
- `acct_rename(old, new, currency)` → 替换 name 字段
- `acct_delete(name, currency)` → 删除条目
- `acct_activate(name, currency, active)` → 切换 active 字段

**公共函数 `get_account(name)`：** 从 accounts.yaml 取单个账户的 `{type, currency, active}`，供 append 和 report 调用。

### transfer.py

```
# 同币种：
ft transfer --from 工行借记卡 --to 工行信用卡 --amount 3000

# 跨币种：
ft transfer --from 工行借记卡 --to IBKR --amount 36250 --to-amount 5000
```

1. 从 `accounts.yaml` 查 from/to 账户的 currency
2. 同币种：`--to-amount` 不允许传
3. 跨币种：`--to-amount` 必填
4. 往 `records/{from.type}/YYYY-MM-DD.csv` 和 `records/{to.type}/YYYY-MM-DD.csv` 各写一行
5. 两个文件分别排序

写入格式：
```
2026-06-12 12:00:00,-36250,CNY,,购汇至USD,transfer,工行借记卡,手动,,
2026-06-12 12:00:00,+5000,USD,,购汇自CNY,transfer,IBKR,手动,,
```

### checkin

```
ft checkin 支付宝余额 --balance 5000
```

往 `records/{type}/YYYY-MM-DD.csv` 追加一行：
```
2026-06-12 12:00:00,0,CNY,,余额校准¥5000.00,checkin,支付宝余额,手动,,
```

## Balance Reset (Checkin)

checkin 行起到硬重置余额的作用——checkin 之前的记录不参与余额计算。

对每个账户：
1. 找最近一次 checkin 行
2. 余额 = checkin 快照值 + 该 checkin 之后所有非 checkin 非 transfer 的 SUM(amount)
3. 无 checkin → 从该账户第一条记录开始累加

expense / income 报表同理：遇 checkin 切段，只统计切段之后的记录。

## append 路由逻辑

```
CSV row.account_name → accounts.yaml 查找 → 得到 type → 写入 records/{type}/YYYY-MM-DD.csv
```

若 `account_name` 在 accounts.yaml 中不存在 → 报错跳过（需先 `ft acct add`）。

## 迁移路径

旧 SQLite 数据（`~/.ft/ft.db`）不会被读取。如需迁移旧数据：
1. `ft export`（使用旧版代码）→ 导出 CSV
2. 创建 accounts.yaml
3. `ft append exported.csv`

迁移可后续单独处理，不纳入本次重构范围。
