# 月度收支分析指南

用 `ft` 数据做月度收支统计时，原始 CSV 中的正负金额**不等于真实收入和支出**。大量资金调拨（银证转账、基金赎回、余额宝搬家、跨行转账、信用卡还款、自转自）被错误归类为 income/expense。需要以下过滤步骤。

## 核心排除规则

| 交易类型 | 信号 | 排除理由 |
|---------|------|---------|
| 银证转账 | 描述/对面含"银转证"、"证转银" | 银行↔证券资金调拨 |
| 基金赎回 | 描述/对面含"基金"+"赎回/快速赎回" | 投资变现，非收入 |
| 余额宝/余利宝 | 描述含"余额宝转入/转出"、"余利宝" | 货币基金搬家 |
| 跨行入账 | 描述含"电子汇入"、"银联入账" | 他行转来，非收入 |
| 转账支取 | 描述含"转账支取" | 跨行转出 |
| 信用卡被还款 | 工行信用卡(1200) + 手机银行 + income | 别人还信用卡 |
| 花呗自动还款 | 描述含"花呗自动还款" | 还款行为 |
| 自转自 | 工行借记卡 income + 对面"黄文龙" | 自己账户间转账 |
| 退货退款 | 描述为商品名（如 Mac mini）、对面为店铺名且有对应支出的 | 商品退货非收入 |
| 微信大额转发 | 工行借记卡 expense + 对面"微信" + 描述仅时间 | 信用卡还款/转账 |

## Python 实现模板

```python
import csv
from pathlib import Path
from collections import defaultdict
import re

records_dir = Path.home() / ".ft" / "records"

def is_fake_transaction(row):
    """判断是否为资金调拨，非真实消费收入"""
    desc = row.get("description", "")
    cp = row.get("counterparty", "")
    acct = row.get("account_name", "")
    cat = row.get("category", "")
    try:
        amt = float(row.get("amount", 0))
    except:
        return True

    # 银证转账
    for pat in ["银转证", "证转银"]:
        if pat in desc or pat in cp:
            return True

    # 基金赎回
    if ("基金" in desc or "基金" in cp) and \
       ("赎回" in desc or "快速赎回" in desc or "赎回" in cp or "快速赎回" in cp):
        return True

    # 余额宝/余利宝
    if "余利宝" in desc or "余利宝" in cp:
        return True
    if "余额宝" in desc and ("转入" in desc or "转出" in desc):
        return True

    # 跨行入账
    if cat == "income" and ("电子汇入" in desc or "银联入账" in desc):
        return True

    # 转账支取
    if "转账支取" in desc:
        return True

    # 工行借记卡微信大额转出（信用卡还款/转账）
    if acct == "工行借记卡" and cat == "expense" and cp == "微信" \
       and re.match(r'^\d{2}:\d{2}:\d{2}$', desc.strip()):
        return True

    # 花呗自动还款
    if "花呗自动还款" in desc:
        return True

    # 信用卡被还款入账
    if acct == "工行信用卡(1200)" and cat == "income" and "手机银行" in desc:
        return True

    # 余额宝在 counterparty
    if "余额宝" in cp and ("转出" in desc or "转入" in desc):
        return True
    if "余额宝" in desc and ("转出" in desc or "转入" in desc):
        return True

    # 自转自
    if acct == "工行借记卡" and cat == "income" and cp == "黄文龙":
        return True

    # 退货退款（商品名描述 + 支付宝 income + 店铺对面）
    if cat == "income" and cp and ("**店" in cp or "ap**" in cp):
        return True

    # 小额利息（<100元不计入收入）
    if ("利息" in desc or "收益" in desc) and amt < 100:
        return True

    return False


def monthly_summary():
    """按月汇总真实收支"""
    monthly = defaultdict(lambda: {"expense": 0.0, "income": 0.0})

    for typ in ["cash", "loan"]:
        for csv_file in sorted((records_dir / typ).glob("*.csv")):
            with open(csv_file, encoding="utf-8") as f:
                for row in csv.DictReader(f):
                    month = row.get("date", "")[:7]
                    amt = float(row.get("amount", 0))
                    cat = row.get("category", "")
                    if cat == "transfer":
                        continue
                    if is_fake_transaction(row):
                        continue
                    if amt > 0:
                        monthly[month]["income"] += amt
                    else:
                        monthly[month]["expense"] += abs(amt)

    return monthly
```

## 需注意的结构性缺陷

- **信用卡双重计数**：信用卡消费（记在信用卡上）+ 银行还款（记在借记卡上）会重复统计同一笔支出。要彻底解决需识别银行端的"信用卡还款"交易并排除。目前上述规则中的"微信大额转出"部分覆盖了这个场景，但不完全。
