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

## ⚠️ 关键陷阱：关键词匹配假阳性极高，不能匹配 account_name

排查"转账被误记为收支"时，**绝不能把 `account_name` 拼进匹配文本**。典型翻车：用 `'还款'` 关键词对 `counterparty+description+account_name` 做子串匹配，命中 **3553 条**——但绝大多数是**正常刷卡消费**，因为 `account_name` 含"工行信用卡"三个字（高德打车、便利蜂、麦当劳的消费都被误抓）。真正的信用卡还款只有极少数（`counterparty=京东, description=还款` 那种十几条）。

正确做法：
- **精确匹配 `description.strip()`** 等于 `充值/提现/群收款/转账/转账备注:微信转账` 等词，而不是子串包含。
- 匹配 `counterparty` 而非 `account_name`（账户名含"信用卡/储蓄卡"会污染任何"还款"类关键词）。
- 理财/基金/余额宝申赎：匹配 `counterparty` 含基金公司名 + `description` 含"买入/申购/赎回/转入/转出"。
- 任何批量分类前，先按关键词分桶打印每桶的 `(counterparty, description, source)` **组合样本**，人眼确认假阳性率，再决定是否改。不要拿关键词命中数直接批量改 category。

## 污染规模基线（2023-06 ~ 2026-07，~10,776 条）

一次全量扫描的量级参考：
- 正确标为 transfer/transfer_in/transfer_out 的记录：**仅 ~64 条**。
- 高置信度"转账被误记为收支"（精确匹配 description）：**~523 条**，其中误记为收入 ~287 条（虚增收入 ¥285k）、误记为支出 ~236 条（虚增支出 ¥367k）。
- 分层：理财基金申赎（余额宝/理财通/基金买赎）、购汇换汇（境内→美股账户，单笔大额）、充值提现（银行卡↔钱包）、微信个人转账/群收款（**混着真收支与 AA 代付，需同额一进一出配对识别，不能一刀切**）。

## report.py 的结构性缺陷（截至本次排查）

`src/ft/report.py` 纯按 `category` 字段汇总，存在两处硬缺口：
1. **没有"净收入"指标**。只有 `report_income`（纯 income 求和）和 `report_expense`（纯 expense 求和），没有 `income − expense`，也没有按月对比时间序列。
2. **收支求和不排除 transfer 污染**。`report_expense/income` 直接把所有 expense/income 求和，转账混在里面 → 月度数字虚高。

建议落地路线（TDD，每步用户确认）：
1. 加"转账识别规则表"（counterparty/description → transfer）+ 同日同额一进一出的配对逻辑，单测覆盖三类边缘：真收支 / 内部转账 / AA 代付。
2. 改 report：收支求和排除 transfer*，新增 `净收入 = income − expense`。
3. 加 `ft report --monthly`：按月输出收入/支出/净收入/转账趋势。
4. 一次性数据修复：523 条高置信度误记逐条经用户确认后改 category（禁止静默批量改）。

## 需注意的结构性缺陷

- **信用卡双重计数**：信用卡消费（记在信用卡上）+ 银行还款（记在借记卡上）会重复统计同一笔支出。要彻底解决需识别银行端的"信用卡还款"交易并排除。目前上述规则中的"微信大额转出"部分覆盖了这个场景，但不完全。
