# 三步导入流水线设计

## 动机

当前 `ft import` 是一步到位：账单文件 → 直接落库，中间结果不可查看、不可修正。

三步流水线将导入拆成独立阶段，每一步产出可查看可修改的 CSV：

```
convert  →  merge  →  load
账单→CSV   合并去重    CSV落库
```

## 中间 CSV 格式

```csv
date,amount,currency,counterparty,description,category,account_name,source
2026-06-06 23:46:23,-31.00,CNY,t***4,镭风HD6450显卡,expense,网商银行储蓄卡(4164),alipay
```

| 字段 | 说明 |
|------|------|
| `date` | `YYYY-MM-DD HH:MM:SS` 精确到秒 |
| `amount` | 带符号浮点数，负=流出，正=流入 |
| `currency` | CNY / USD / HKD |
| `counterparty` | 交易对方 |
| `description` | 商品说明 / 备注 |
| `category` | 自动标注可修改：income / expense / transfer / 空 |
| `account_name` | 自动匹配可修改：目标账户名 |
| `source` | 来源账单：alipay / wechat / icbc_debit / icbc_credit |

## YAML 映射规则

`~/.ft/mapping.yaml`

```yaml
rules:
  # ── 支付宝 ──
  - source: alipay
    match: "工商银行信用卡(1200)&*"
    account: "工行信用卡(1200)"
    currency: CNY
  - source: alipay
    match: "网商银行储蓄卡(4164)&*"
    account: "网商储蓄卡(4164)"
    currency: CNY
  - source: alipay
    match: "账户余额"
    account: "支付宝余额"
    currency: CNY
  - source: alipay
    match: "建设银行储蓄卡(2820)&*"
    account: "建行储蓄卡(2820)"
    currency: CNY
  - source: alipay
    match: "花呗*"
    account: "花呗"
    currency: CNY

  # ── 微信 ──
  - source: wechat
    match: "零钱"
    account: "微信零钱"
    currency: CNY
  - source: wechat
    match: "工商银行信用卡(1200)*"
    account: "工行信用卡(1200)"
    currency: CNY
  - source: wechat
    match: "建设银行储蓄卡(2820)*"
    account: "建行储蓄卡(2820)"
    currency: CNY

  # ── 工行 ──
  - source: icbc_debit
    match: "*"
    account: "工行借记卡"
    currency: CNY
  - source: icbc_credit
    match: "*"
    account: "工行信用卡(1200)"
    currency: CNY

default: error      # convert 阶段：支付方式未匹配规则时的行为。error=报错, skip=跳过
```

### account_name 的三种来源

- 优先级：长规则优先（精确匹配 > 前缀匹配 > 通配）
- `default: error` 未匹配就报错，可改为 `skip` 静默跳过

| 阶段 | 来源 | 说明 |
|------|------|------|
| convert | mapping.yaml | 原始 `payment_method` → 规则匹配 → `account_name` |
| merge | CSV 字段 | 保持上一阶段的值不变 |
| load | `accounts` 表 | CSV 的 `account_name` + `currency` → 查 accounts 表 |

convert 阶段 mapping.yaml 匹配失败 → 按 `default` 处理。  
load 阶段 `accounts` 表匹配失败 → 报错（需先 `ft acct add`）。

## 命令设计

### `ft convert` — 账单 → 统一CSV

```
ft convert 支付宝.csv --source alipay -o alipay.csv
ft convert 微信.xlsx --source wechat -o wechat.csv
ft convert 工行.pdf --source icbc --password xxx -o icbc.csv
```

**做的事情：**
1. 解析原始账单（保持现有的 alipay/wechat/icbc 解析逻辑）
2. 用 mapping 规则匹配 `(source, payment_method)` → `(account_name, currency)`
3. 自动推断 `category`：
   - 含购汇/跨境/外汇关键词 → `transfer`
   - 退款 → `expense`（正数冲减）
   - 普通支出/收入 → `expense` / `income`
   - 信用卡还款 → `transfer`
4. 输出 UTF-8 CSV

> 跨币种购汇仅输出账单上实际出现的一条记录（如 CNY 流出）。USD 入账那端由券商账单处理，在 merge 阶段与购汇记录配对。

### `ft merge` — 合并去重

```
ft merge alipay.csv wechat.csv icbc.csv -o merged.csv
```

**做的事情：**
1. 读取多个 CSV
2. 去重：`(datetime, amount, currency, account_name)` 四元组完全匹配视为同一笔
3. 按 datetime 排序
4. 输出去重后的合并 CSV

### `ft load` — CSV 落库

```
ft load merged.csv
```

**做的事情：**
1. 逐行读取 CSV
2. 匹配账户：`accounts` 表 WHERE `name=account_name AND currency=currency AND is_active=1`
3. 匹配不到时按 `mapping.yaml` 的 `default` 策略处理：
   - `error`：报错终止（默认）
   - `skip`：跳过该行并计数
4. 写入 `transactions` 表（通过 `insert_txn` 自动派生 currency）
5. 打印统计：新增N条 跳过M条 找不到账户K条

## 文件组织

| 文件 | 说明 |
|------|------|
| `src/ft/convert.py` | convert 命令核心逻辑 |
| `src/ft/merge.py` | merge 命令核心逻辑 |
| `src/ft/load.py` | load 命令核心逻辑 |
| `src/ft/mapping.py` | YAML 规则解析 + 匹配 |
| `~/.ft/mapping.yaml` | 用户规则配置 |
| `src/ft/cli.py` | 挂接 convert / merge / load 子命令 |

原导入器（alipay/wechat/icbc）的**解析逻辑保留不变**，`convert` 调用它们解析原始账单，然后映射 + 输出 CSV 而非直接 insert 数据库。

## 实施顺序

1. `mapping.py` — YAML 规则解析 + glob 匹配
2. `convert.py` — 调用现有解析器 + 映射 + 输出 CSV
3. `merge.py` — 合并 + 去重 + 排序
4. `load.py` — 读取 CSV + 账户匹配 + 落库
5. `cli.py` — 挂接子命令
6. 旧 `import` 命令保留但标记为 deprecated
7. 测试
