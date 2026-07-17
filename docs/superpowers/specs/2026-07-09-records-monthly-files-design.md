# Records 月度单文件组织设计

## 背景

当前 `ft` 将现金类与证券类 records 落盘到 `records/{type}/YYYY-MM-DD.csv`。用户希望默认按月组织 records，改成 `records/{type}/YYYY-MM.csv`，并明确不做旧日文件兼容；切换前应先执行 `ft verify --fix` 或直接重建数据后再继续使用。

## 目标

- 所有 records 默认按月单文件落盘：`records/{type}/YYYY-MM.csv`
- 不改变 CSV 行结构与业务语义，只改变文件组织方式
- 读取、报表、verify、reconcile、股票回放等内部逻辑统一基于月文件工作
- 不兼容旧 `YYYY-MM-DD.csv` 文件；遇到旧数据时，要求用户先重建后再用

## 非目标

- 不保留按天/按月配置开关
- 不自动迁移历史日文件
- 不同时兼容新旧两种 records 布局
- 不调整 convert / append / reconcile 的业务规则

## 存储结构

从：

```text
records/
  cash/2026-06-12.csv
  loan/2026-06-12.csv
  security/2026-06-30.csv
```

改为：

```text
records/
  cash/2026-06.csv
  loan/2026-06.csv
  security/2026-06.csv
```

同一账户类型、同一月份的所有记录写入同一个 CSV 文件，文件内仍按 `date` 升序排序。

## 设计决策

### 1. 文件命名统一由公共规则生成

代码中不再手写 `date[:10] + ".csv"`。统一用公共规则：

- 月键：`date[:7]`
- records 文件路径：`records/{type}/{date[:7]}.csv`

这样 `append`、`transfer`、`checkin`、`stock`、`verify`、`reconcile`、各类 stock sync 去重扫描都共享同一命名约定，避免再出现局部仍写日文件的问题。

### 2. append 改为按月分组、按月写入

`append` 当前先按 `(type, date[:10])` 分组再写入日文件。改为按 `(type, date[:7])` 分组，写入月文件。统计输出可以继续按具体日期累计，不影响用户理解导入量。

### 3. 直接扫描类型目录下的月文件

`report`、`snapshot.rebuild_snapshot_from_records`、`reconcile._load_entries`、security 回放与外部同步去重，都继续扫描 `records/{type}/*.csv`，但它们语义上只认月文件。由于用户明确不要兼容旧结构，这里不增加“日/月混扫”分支。

### 4. 手动录入与证券流水同样按月落盘

以下入口与 `append` 保持一致：

- `ft add`
- `ft checkin`
- `ft transfer`
- `ft stock` 各写入路径

这样整个 records 目录不会混出日文件。

### 5. 切换策略

这是一次不兼容布局切换：

- 旧 `records/*/YYYY-MM-DD.csv` 不保证继续被读取
- 切换后用户应先执行 `ft verify --fix` 或直接重建 records，再继续导入和使用

README 需要明确写出这一点。

## 受影响模块

- `src/ft/append.py`
- `src/ft/transfer.py`
- `src/ft/stock.py`
- `src/ft/cli.py`
- `src/ft/report.py`
- `src/ft/snapshot.py`
- `src/ft/reconcile.py`
- `src/ft/sync_common.py`
- `src/ft/polymarket_sync.py`
- 相关测试与 README

## 测试策略

1. 更新 append / transfer / stock 的路径断言，先让月文件预期失败
2. 实现公共月文件路径规则，并接入所有写入点
3. 修正读取/扫描逻辑，使 report / snapshot / reconcile / security 回放在月文件下通过
4. 跑核心回归测试：
   - `tests/test_append.py`
   - `tests/test_transfer_csv.py`
   - `tests/test_stock.py`
   - `tests/test_report_csv.py`
   - `tests/test_snapshot.py`
   - `tests/test_reconcile.py`
   - `tests/test_reconcile_locked.py`
   - `tests/test_cli.py`
   - `tests/test_import.py`

## 验收标准

- 新写入 records 只生成 `YYYY-MM.csv`
- 同月不同日记录正确合并到一个月文件，文件内按 `date` 排序
- `report` / `verify --fix` / `reconcile` / stock 回放在月文件下正常工作
- README 明确 records 已改为按月单文件组织，且不兼容旧日文件布局
