# 东方财富（DFZQ）对账单转换器字段偏移量

## 问题

东方证券 PDF 对账单的表格各行字段数不一致。部分行缺少"证券名称"列，部分行还缺少"成交数量"列。

## 根因

PDF 表格的 11 列标准格式为：

```
发生日期 | 买卖类别 | 证券代码 | 证券名称 | 成交数量 | 成交价格 | 总发生金额 | 手续费 | 印花税 | 过户费 | 资金余额
```

但以下情况的空字段被 mutool 跳过（不输出空文本）：
- **银行转证券 / 证券转银行 / 利息归本**：证券名称和成交数量均为空 → 缺 2 列
- **OTC 资金划出 / OTC 资金划入**：证券名称为空（成交数量不为空，为 "0"） → 缺 1 列

## 检测方法

`block[3]` 是纯数字时进入"无名称"分支：

1. **含小数点**（如 `"0.0000"`）→ 银行转账格式，缺 2 列
   - ticker → price → total_amount → fee → [stamp, transfer,] balance
2. **纯整数**（如 `"0"`）→ OTC 格式，缺 1 列
   - ticker → shares → price → total_amount → fee → [stamp, transfer,] balance
3. **有名称列**（含中文字符）→ 标准格式
   - ticker → name → shares → price → total_amount → fee → stamp → transfer → balance

## 验证方法

```bash
cd ~/.hermes/skills/finance/finance-tracker
find src/ft/__pycache__/ src/ft/importers/__pycache__/ -name "*.cpython-311.pyc" -delete

# 检查 DEPOSIT 金额非零
python3 -c "
import csv
with open('/tmp/dfzq_test.csv') as f:
    reader = csv.DictReader(f)
    bad = sum(1 for r in reader if r['action'] == 'DEPOSIT' and Decimal(r['amount']) == 0)
    print(f'零金额DEPOSIT: {bad}')
"

# 检查 commission ≠ amount
python3 -c "
import csv
with open('/tmp/dfzq_test.csv') as f:
    reader = csv.DictReader(f)
    bad = [(r['date'][:10], r['action'], r['amount'], r['commission'])
           for r in reader if r['action'] in ('DEPOSIT','WITHDRAW')
           and abs(Decimal(r['amount'])) > 0 and Decimal(r['amount']) == Decimal(r['commission'])]
    print(f'fee==amount的DEPOSIT/WITHDRAW: {len(bad)}')
    for b in bad[:5]: print(b)
"
```

## 修改的文件

`src/ft/importers/dfzq.py`
- 新增 `_is_numeric()` 辅助函数
- 在标准分支前添加 `has_name_col` 判断
- `not has_name_col` 分支内按 `'.' in block[3]` 二分为银行转账格式和 OTC 格式
