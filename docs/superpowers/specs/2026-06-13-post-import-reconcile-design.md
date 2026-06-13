# 导入后 Reconcile 设计

> 日期: 2026-06-13
> 状态: 设计完成
> 关联: `src/ft/cli.py`, `src/ft/append.py`, `src/ft/dedup.py`, `src/ft/merge.py`, `src/ft/snapshot.py`, `SKILL.md`

## 背景

当前消费账单导入流程为：

```text
convert -> merge -> append
```

其中：

- `convert` 负责账单解析、退款核销、`counterparty` 标准化
- `merge` 负责跨源去重并输出 `merged.csv` / `removed.csv`
- `append` 只接受单个 `merged.csv`，负责落盘和更新 snapshot

这个流程有两个问题：

1. 去重发生在落盘前，无法对整个账本做全量或按时间范围重跑
2. `merge` 是一次性中间产物，用户完成导入需要额外维护中间目录和手工衔接

用户希望改成：

```text
convert -> append -> reconcile
```

其中退款核销和 `counterparty` 标准化仍保留在 `convert`，导入后的统一后处理命令先只做去重，后续再扩展到账户转账识别等动作。

## 目标

1. 删除 `ft merge` 命令和对应流程
2. 保持 `ft convert` 的退款核销和 `counterparty` 标准化行为不变
3. `ft append` 支持一次导入多个 converted CSV
4. 新增 `ft reconcile` 命令，对已落盘账本执行后处理
5. 当前版本的 `ft reconcile` 只做跨源重复交易识别与删除
6. `ft reconcile` 支持全量运行，也支持按时间范围运行
7. `ft reconcile` 原地重写受影响的 `records/*.csv`，并产出审计文件供人工复核

## 非目标

1. 本次不改变 `convert` 的解析、退款核销、`counterparty` 标准化规则
2. 本次不实现账户转账识别，只为后续扩展预留命令入口
3. 本次不引入数据库、sidecar ledger 或新的持久化格式
4. 本次不处理 `security` 账户的股票导入流程

## 新流程

消费账单导入改为 3 步：

```text
① convert -> ② append -> ③ reconcile
```

### ① convert

命令不变：

```bash
ft convert <账单> -s alipay|wechat|icbc|ccb-debit -o <csv>
```

职责保持不变：

- 账单格式解析
- 退款核销
- `counterparty` 标准化
- 输出统一 CSV

### ② append

改为支持多个输入文件：

```bash
ft append a.csv b.csv c.csv
```

职责：

- 校验 `account_name`、`date`、账户存在性
- 将多份 converted CSV 逐条追加进 `records/{type}/YYYY-MM-DD.csv`
- 更新 snapshot
- `git add -A`

明确不做：

- 跨源去重
- 转账识别
- 任何基于全账本扫描的后处理

### ③ reconcile

新增命令：

```bash
ft reconcile
ft reconcile --month 2026-06
ft reconcile --from 2026-06-01 --to 2026-06-30
```

职责：

- 读取已落盘账本
- 对目标范围内的消费流水做跨源去重
- 原地重写受影响的 `records/*.csv`
- 输出审计文件
- 全量重建 snapshot
- `git add -A`

## CLI 设计

### 删除 `merge`

从 `cli.py` 移除：

```python
mg = sub.add_parser("merge", help="步骤② 合并去重CSV")
```

`src/ft/merge.py` 删除，所有文档和测试中不再出现 `ft merge`。

### 修改 `append`

原签名：

```bash
ft append merged.csv
```

新签名：

```bash
ft append <csv1> [csv2 ...]
```

CLI 实现上将 `file` 改为 `files`, `nargs="+"`。

### 新增 `reconcile`

CLI 形态：

```bash
ft reconcile [--month YYYY-MM]
ft reconcile [--from YYYY-MM-DD] [--to YYYY-MM-DD]
```

约束：

1. `--month` 与 `--from/--to` 互斥
2. `--from` 和 `--to` 可单独使用
3. 无参数时表示全量

语义：

- `--month 2026-06`：处理 `2026-06-01` 到 `2026-06-30`
- `--from 2026-06-01`：处理该日及之后
- `--to 2026-06-30`：处理该日及之前
- `--from` + `--to`：处理闭区间

## Reconcile 数据范围

### 账户范围

当前版本只处理：

- `records/cash/*.csv`
- `records/loan/*.csv`

不处理：

- `records/security/*.csv`
- `records/lend/*.csv`

原因：

- 当前跨源重复交易来自消费账单导入，落点是 `cash` / `loan`
- `security` 是独立股票流水
- `lend` 不在现有消费账单导入范围内

### 时间范围

`reconcile` 以交易的 `date` 字段决定是否属于目标窗口，而不是文件名。这样即便未来出现补录或跨天写入，也不会因文件边界而误判。

### 重写范围

只重写“受影响的日文件”，定义为：

1. 文件中至少有 1 条记录落在 `reconcile` 目标时间范围内
2. 该文件内存在被删除的重复项，或其行集因保留/删除结果发生变化

未受影响的文件保持字节级不动，避免无意义 git diff。

## 去重规则

`ft reconcile` 第一版复用当前 `dedup.py` 的业务规则，不在这次设计中改变匹配标准：

1. 以 `(minute(date), amount, currency)` 分组
2. 支付宝、微信优先保留，银行账单作为候选删除项
3. 时间差不超过 10 秒
4. `account_name` 必须相同
5. `counterparty` 或 `description` 需满足双向子串校验

这意味着本次是“时机后移”，不是“规则重写”。

## Reconcile 执行模型

### 1. 读取账本

扫描目标目录下全部日文件，读取为统一的记录列表，并保留每条记录的来源文件路径。

内部建议结构：

```python
{
    "row": <csv row dict>,
    "file_path": Path(...),
    "line_no": 12,
}
```

其中 `line_no` 只用于审计输出，不写回正式账本。

### 2. 筛选参与去重的记录

仅将目标时间范围内的记录送入 dedup 匹配器。

范围外记录的处理规则：

- 不作为删除目标
- 不参与匹配

这样可以保证“按范围 reconcile”只影响用户指定窗口内的数据，不会跨窗口删历史记录。

### 3. 生成保留/删除决策

对参与范围内的记录执行 dedup，得到：

- 保留记录集合
- 删除记录集合
- 配对审计信息

范围外记录原样并入最终账本结果。

### 4. 回写 records

按原文件分组重建受影响日文件：

1. 保留原来未参与处理的记录
2. 加上参与处理后仍保留的记录
3. 按 `date` 升序写回
4. 维持 `models.CSV_FIELDS` 现有 9 列顺序

如果某个日文件在去重后为空，则删除该文件。

### 5. 重建 snapshot

`reconcile` 完成后不做增量修补，直接调用现有“从 CSV 全量重建 snapshot”的逻辑，效果等同 `ft verify --fix`。

原因：

- `reconcile` 会删除历史记录
- 增量回滚 balance 成本高且容易漏边界
- 账本规模当前足够小，全量重建更稳

### 6. Git stage

完成回写和 snapshot 重建后，执行 `git add -A`，保持与其他写操作一致。

## 审计文件

### 目标

用户要求保留类似当前 `removed.csv` 的复核产物，但 `reconcile` 发生在落盘后，审计文件应成为账本的一部分，而不是临时输出目录里的中间文件。

### 落点

新增目录：

```text
~/.ft/audit/reconcile/
```

每次运行输出一个带时间戳的审计文件：

```text
~/.ft/audit/reconcile/2026-06-13_15-04-05.csv
```

这样可以保留历史执行记录，便于复核和回滚分析。

### 字段

建议字段：

```text
run_at,
scope_from,
scope_to,
date,
amount,
currency,
counterparty,
description,
category,
account_name,
source,
bill_source,
record_file,
dedup_status
```

其中：

- `dedup_status` 取值为 `保留` / `去除`
- `record_file` 表示原始所在日文件，便于追查来源
- `scope_from` / `scope_to` 固化本次运行范围，便于后续审计

输出方式保持和当前 `removed.csv` 一致：每一对重复决策写两行，一行 `保留`，一行 `去除`。

### 空结果

若本次 `reconcile` 未发现任何重复项：

- 不创建审计文件
- 命令输出 `无重复项`

## 错误处理

### 输入参数错误

- `--month` 格式非法：报错退出
- `--from` / `--to` 格式非法：报错退出
- `--from > --to`：报错退出

### 数据错误

若扫描到账本中的非法记录，例如：

- `date` 为空或格式不合法
- `amount` 无法转 float
- 缺少必需字段

则立即失败，不写回任何文件。

原因：

- `reconcile` 是修改历史账本的命令
- 遇到脏数据时宁可中止，也不要做部分回写

### 原子性

回写策略采用“全部决策完成后再写文件”的两阶段方式：

1. 先在内存中完成全量决策和目标文件的新内容构建
2. 再逐个写回文件并重建 snapshot

避免在中途失败时留下半更新状态。

## 代码结构建议

### `dedup.py`

保留现有 dedup 规则核心，但重构为两层：

1. 纯规则层：输入记录列表，输出保留/删除/配对结果
2. 账本协调层：负责按文件读取、筛范围、回写、产出审计文件

建议新增入口函数：

```python
def reconcile_records(records: list[dict]) -> tuple[list[dict], list[dict]]:
    ...
```

或新增模块：

```text
src/ft/reconcile.py
```

推荐新增 `reconcile.py`，原因是：

- `dedup.py` 保持“纯匹配规则”单一职责
- `reconcile.py` 承担 CLI 参数、文件扫描、审计输出、snapshot 重建
- 后续加入“转账识别”时更容易继续扩展 `reconcile.py`

### `append.py`

`do_append()` 改为接收 `list[str]`，循环读取多个 CSV。

建议行为：

1. 先读取所有输入 CSV 并完成前置校验
2. 再统一写入 records
3. 最后更新 snapshot 一次

避免“前两个文件已写入，第三个文件报错”这种半成功状态。

## 文档更新

需要同步更新：

1. `SKILL.md`
   新导入流程改为 `convert -> append -> reconcile`
2. `README.md`
   更新消费账单导入示例
3. 命令速查
   删除 `merge`，新增 `reconcile`
4. 任何提到 `merged.csv` / `removed.csv` 的描述

## 测试

### CLI

1. `ft append a.csv b.csv` 能正确解析多个输入
2. `ft reconcile --month 2026-06`
3. `ft reconcile --from 2026-06-01 --to 2026-06-30`
4. `--month` 与 `--from/--to` 互斥校验

### append

1. 多文件导入成功时，记录正确写入对应日文件
2. 任一输入文件校验失败时，不写入任何 records 文件
3. 多文件导入后 snapshot 正确更新一次

### reconcile

1. 全量 reconcile：能删除跨源重复记录并生成审计文件
2. 按月份 reconcile：只影响该月内重复项
3. 按时间范围 reconcile：范围外重复项不受影响
4. 去重后日文件为空时，文件被删除
5. 无重复项时，不生成审计文件
6. reconcile 完成后 snapshot 与 `ft verify --fix` 结果一致

### 回归

1. `convert` 的退款核销行为不变
2. `convert` 的 `counterparty` 标准化行为不变
3. 股票导入流程不受影响

## 风险与取舍

1. 按时间范围 reconcile 不跨范围匹配，意味着边界日附近的重复项如果一半在范围内、一半在范围外，本次不会删除。
   这是刻意选择，换取“范围运行只改范围内数据”的可预期性。

2. 审计文件改为写入 `~/.ft/audit/reconcile/` 后，会把复核记录纳入 git 管理。
   这是有意的，便于账本变更和审计结果一起提交。

3. `append` 不做去重，意味着用户在 `reconcile` 前会短暂看到重复数据。
   这是接受的，因为职责被明确拆开，且 `reconcile` 是显式、可审计、可全量重跑的整理步骤。

## 最终命令示例

```bash
# 1. 各账单先转换
ft convert alipay.csv -s alipay -o alipay_out.csv
ft convert wechat.xlsx -s wechat -o wechat_out.csv
ft convert icbc.pdf -s icbc --password 123456 -o icbc_out.csv

# 2. 一次导入多份 converted CSV
ft append alipay_out.csv wechat_out.csv icbc_out.csv

# 3. 导入后统一去重
ft reconcile --month 2026-06

# 4. 确认无误后提交
ft commit -m "import June bills and reconcile duplicates"
```
