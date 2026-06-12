# 消费账单转换器开发提示词

## 背景

为 `ft`（finance-tracker）新增一个消费账单源，实现 原始账单 → 统一 CSV → 合并去重 → 落库。

## 完整流水线

```
① ft convert -s <source> -o <csv> <原始账单>       # 账单 → 统一 CSV
② AI 审查 (resources/review-checklist.md)           # 逐条审查转换质量 + AI 修正
③ ft merge <csvs> -o <dir>                          # 多源合并 + 去重
④ AI 合并审查                                        # 检查去重结果
⑤ ft append <merged.csv>                            # 落库 + 更新快照
```

## 架构

### 代码位置

| 文件 | 用途 |
|------|------|
| `src/ft/convert.py` | **核心调度**：`do_convert()` 入口 + 共享逻辑（`_normalize_counterparty`、`_pair_refunds`、`_infer_platform`、`_infer_payment_source`、`_strip_payment_prefix`） |
| `src/ft/importers/{source}.py` | **源解析器**：读原始数据 → 结构化 dict 列表 |
| `src/ft/mapping.py` | `mapping.yaml` 的加载/匹配规则引擎 |
| `src/ft/merge.py` | 多源合并+去重 |
| `src/ft/append.py` | 落库到 `records/` |
| `src/ft/accounts.py` | 账户管理 |
| `src/ft/models.py` | `CSV_FIELDS` 等常量 |

### 统一 CSV 9 列格式（models.CSV_FIELDS）

```
date,amount,currency,counterparty,description,category,account_name,source,bill_source
```

| 字段 | 示例 | 说明 |
|------|------|------|
| date | `2026-01-15 12:30:00` | `YYYY-MM-DD HH:MM:SS` 格式 |
| amount | `-29.90` 或 `5000.00` | 负数=支出，正数=收入 |
| currency | `CNY` | 币种代码（三字母） |
| counterparty | `麦当劳` | 交易对方（品牌匹配后） |
| description | `麦当劳(双安店)` | 商品说明/原始描述 |
| category | `expense` / `income` / `transfer` | 收支类别 |
| account_name | `工行借记卡` | 映射后的账户名（见 mapping.yaml） |
| source | `支付宝` / `微信` / `银行卡` / `建行储蓄卡` / `微信支付` | 支付源，自动推断 |
| bill_source | `alipay` / `wechat` / `icbc_credit` / `icbc_debit` / `ccb_debit` | 账单来源类型 |

## 实现步骤

### 第一步：创建解析器

路径：`src/ft/importers/{source}.py`

**解析器函数签名**（参考 `read_ccb_debit`、`_read_alipay_raw`、`_read_icbc_raw`）：

```python
def read_{source}(path: str) -> tuple[list[dict], list[dict]]:
    """解析原始账单，返回 (records, tracking_pairs)
    
    tracking_pairs 一般为 []，退款配对由 convert.py 的 _pair_refunds 统一处理。
    """
```

每条 record dict 必须包含：

```python
{
    "date": "2026-01-15 12:30:00",      # YYYY-MM-DD HH:MM:SS 格式
    "amount": -29.90,                    # float，负数=支出
    "currency": "CNY",                   # 币种
    "payment_method": "工商银行(3697)",   # 支付方式（用于 mapping 匹配）
    "card_number": "3697",               # 卡号后4位（可选，信用卡路由用）
    "counterparty": "麦当劳",            # 交易对方（裸名，不做品牌匹配）
    "description": "麦当劳(双安店)",      # 商品描述
    "category": "expense",               # expense / income / transfer
}
```

**核心逻辑要点**：

1. **编码嗅探**：支付宝等 CSV 可能为 GBK/GB18030，参考 `alipay.py` 中的 `_detect_encoding()`
2. **日期解析**：统一转 `YYYY-MM-DD HH:MM:SS`
3. **金额清洗**：去除千分位逗号，转 float
4. **取 counter_party 裸名**：从原始字段提取纯交易对方名（去除支付源前缀等），后续 `_normalize_counterparty()` 统一处理品牌匹配
5. **取 payment_method**：提取原始支付方式字符串（如 `"工商银行储蓄卡(3697)"`），供 mapping.yaml 匹配路由到账户

### 第二步：接入 do_convert

在 `src/ft/convert.py` 的 `do_convert()` 中添加 `elif source == "{source}":` 分支：

```python
elif source == "{source}":
    from .importers.{source} import read_{source}
    rows, _ = read_{source}(path)
    bill_type = "{source}"
    # 退款配对（若适用）
    expenses = [r for r in rows if r["category"] == "expense"]
    refunds = [r for r in rows if r["category"] == "income" and "退货" in r.get("description", "")]
    others = [r for r in rows if not (r["category"] == "expense" or (r["category"] == "income" and "退货" in r.get("description", "")))]
    rows, tracking_pairs = _pair_refunds(expenses, refunds, others)
```

⚠️ `bill_type` 须与 `mapping.yaml` 中规则的 `bill_type` 一致。

### 第三步：注册 CLI

在 `src/ft/cli.py` 的 `cv.add_argument("-s", "--source", choices=[...])` 中添加新 source 名。

### 第四步：编写 mapping 规则

账单类型确定后，需在 `~/.ft/mapping.yaml` 中添加匹配规则（首次使用时）：

```yaml
- bill_type: "{source}"          # 与 bill_type 变量一致
  match: "支付方式名模式"          # fnmatch 模式
  account: "账户名"               # 映射到的账户（需提前 ft acct add）
  currency: CNY
```

`match` 字段使用 fnmatch 模式匹配 `payment_method`。常见模式：

| 场景 | match |
|------|-------|
| 精确名称 | `"工商银行储蓄卡(3697)*"` |
| 前缀 | `"微信零钱*"` |
| 空值 | `""` |

### 第五步：测试

**单元测试**（参考 `ccb_debit` 的测试模式）：

- 日期格式转换
- 金额正负号/千分位清洗
- counterparty 提取（不同前缀格式）
- payment_method 推断
- 空输入/无效行不崩溃
- 多页/不同年份数据

**集成测试**：执行完整 `ft convert` → 验证输出 CSV 列数/字段名。

## 共享逻辑说明

无需在解析器中处理，`convert.py` 自动执行：

### counterparty 规范化（`_normalize_counterparty`）

三级回退：
1. 去掉支付源前缀（`财付通-`、`支付宝-`、`美团支付-`）
2. 品牌匹配（滴滴、麦当劳、瑞幸等约 40 个品牌规则）
3. O2O 平台前缀剥离（`美团App`、`饿了么`）

解析器只需从原始数据提取裸 counterparty 字符串。

### 退款配对（`_pair_refunds`）

通用的按交易对方 + 金额 + 日期配对算法。解析器无需实现退款逻辑，convert.py 统一切换。

### 支付源推断（`_infer_payment_source`）

按账单类型自动推断 source 字段。解析器只需输出原始 `payment_method`。

### 账户路由

基于 `mapping.yaml` 规则引擎 + `match_payment_method()`，按 `bill_type` + `payment_method`（可选 `card_number`）自动匹配到账户。

## AI 审查与修正步骤

转换完成后，按 `references/review-checklist.md` 逐条审查 CSV：
- **P0** 金额影响（退款配对正确性、gross refund 检测、金额公式）
- **P1** source 正确性
- **P2** 数据脱敏（卡号、姓名、URL 编码）
- **P3** counterparty 规范化

## 运行验证

```bash
# 激活环境
cd ~/.hermes/skills/finance/finance-tracker
source .venv/bin/activate

# 跑全部测试
pytest -x -q

# 端到端：转换 → 审查 → 合并 → 落库
ft convert -s {source} -o /tmp/{source}_output.csv <原始账单>
# AI 审查（见 review-checklist.md）
ft merge /tmp/{source}_output.csv -o /tmp/merge_output/
ft append /tmp/merge_output/merged.csv
```

## 现有解析器参考

| 源 | 文件 | 输入格式 | 特点 |
|----|------|---------|------|
| alipay | `importers/alipay.py` (+ `convert.py: _read_alipay_raw`) | CSV | GBK 编码嗅探，退款配对 |
| wechat | `importers/wechat.py` (+ `convert.py: _read_wechat_raw`) | XLSX | openpyxl，退款配对 |
| icbc | `importers/icbc.py` (+ `convert.py: _read_icbc_raw`) | PDF (加密) | qpdf+mutool，区分信用卡/借记卡 |
| ccb-debit | `importers/ccb_debit.py` (+ `convert.py` 分支) | XLS (旧版) | xlrd，前缀剥除 |
