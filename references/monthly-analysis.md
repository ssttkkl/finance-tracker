# 月度收支分析指南

## 核心原则（用户偏好）

**只按账单记录的 `category` 字段过滤，不要做额外的算法过滤。**

```python
# 最简月度收支汇总 — 用户明确要求的方式
for row in csv.DictReader(f):
    cat = row.get("category", "")
    if cat in ("transfer", "transfer_in", "transfer_out"):
        continue  # 只排除这三类
    # 其余全部计入真实收支，不做 is_fake_transaction 等额外过滤
```

**为什么不用额外过滤：** 用户明确说"他人转账要算的，只有自己转自己标注成 transfer in/out"。大额转给朋友（常杰 ¥34,400）、份子钱（¥1,314 新婚快乐）、年终奖到账等，都是真实收支，不应被算法排除。

**额外过滤的适用场景：** 仅在诊断"为什么某月数字异常大"时，才用下方的诊断规则做辅助分析，不作为默认汇总逻辑。

## 诊断用：额外过滤规则（非默认，仅诊断用）

当某月收支数字明显异常，需要排查原因时，可按以下规则分类：

| 交易类型 | 信号 | 性质 |
|---------|------|------|
| 银证转账 | 描述/对面含"银转证"、"证转银" | 自己银行↔证券，可排除 |
| 基金赎回 | 描述/对面含"基金"+"赎回/快速赎回" | 投资变现，可排除 |
| 余额宝/余利宝 | 描述含"余额宝转入/转出"、"余利宝" | 货币基金搬家，可排除 |
| 转账支取 | 描述含"转账支取" | 跨行转出，可排除 |
| 花呗自动还款 | 描述含"花呗自动还款" | 信用卡自还，可排除 |
| 自转自 | 工行借记卡 income + 对面"黄文龙" | 同一人账户间，可排除 |
| 微信时间格式转出 | 工行借记卡 expense + 对面"微信" + desc=\d{2}:\d{2}:\d{2} | 信用卡还款模式，可排除 |

**不可排除的（用户明确要求保留）：**
- 他人转账（转给别人/从别人收到）→ 真实收支
- 年终奖/奖金 → 真实一次性收入
- 大额微信"无卡付" → 可能是个人转账，需逐笔确认
- 退货退款 → 部分是真实退货，部分是重复记录

## 跨渠道重复记录（reconcile 未捕获）

以下是 reconcile 的 dedup 逻辑无法捕获的重复模式，会导致收支同时虚增。

### dedup 匹配条件（dedup.py）

三个条件必须**同时**满足：
1. `(分钟截断, 金额, 币种)` 相同 → 分到同一组
2. `account_name` **完全相同**
3. 时间差 ≤ 10秒 **且** `_cross_verify`（counterparty 或 description 互为子串）

### dedup 失效的典型场景

| 场景 | 铁路12306 | 中国铁路网络 | 失效原因 |
|------|-----------|------------|---------|
| 12306 app vs 银行账单 | counterparty=`铁路12306` | counterparty=`中国铁路网络有限公司` | 不互为子串 |

| 场景 | counterparty A | counterparty B | 失效原因 |
|------|---------------|---------------|---------|
| 脱敏名 vs 真名 | `冯海洋` | `f***8` | 脱敏截断后不互为子串 |
| 平台名 vs 商家名 | `B站` | `上海动魂文化传媒有限公司` | 完全不同 |
| 平台名 vs 公司名 | `河南湖震商贸有限公司` | `百亿**选` | 完全不同 |
| 支付渠道名 vs 商家名 | `飞猪` | `阿斯兰航空服务（上海）有限…` | 不互为子串 |

### 已知重复类型（2023-06 ~ 2026-06）

1. **铁路12306 vs 中国铁路网络有限公司** — 44对，重复金额 ¥14,843
   - 铁路12306: bill_source=`alipay`（支付宝账单中的12306消费）
   - 中国铁路网络: bill_source=`icbc_credit`/`ccb_debit`（信用卡/借记卡账单中的支付宝扣款）
   - 同一笔火车票在两个账单源各记一次

2. **同账户跨源重复** — 226组，前20组净增 ¥60,346
   - 同一交易在支付宝和信用卡/借记卡各记一次
   - counterparty 名称不同（脱敏/平台名/公司名）导致 dedup 失效

3. **大额微信"无卡付"** — 12笔，合计 ¥-167,430
   - 从银行卡转到微信或转给他人的大额资金调拨
   - 不是消费，但被记为 expense
   - 需逐笔确认是自转自（排除）还是转给别人（保留）

4. **同日一进一出对子** — 117对，净额=0
   - 买+退同日发生，收支各虚增相同金额
   - 净影响为0，但拉高了毛收入和毛支出

## 排查脏数据的 Python 模板

```python
import csv
from pathlib import Path
from collections import defaultdict

records_dir = Path("records")

def find_duplicates():
    """找出 reconcile 未捕获的重复记录"""
    all_rows = []
    for typ in ["cash", "loan"]:
        for csv_file in sorted((records_dir / typ).glob("*.csv")):
            with open(csv_file, encoding="utf-8") as f:
                for row in csv.DictReader(f):
                    cat = row.get("category", "")
                    if cat in ("transfer", "transfer_in", "transfer_out"):
                        continue
                    all_rows.append({
                        "date": row.get("date", "")[:10],
                        "amount": float(row.get("amount", 0)),
                        "counterparty": row.get("counterparty", ""),
                        "description": row.get("description", ""),
                        "account_name": row.get("account_name", ""),
                        "bill_source": row.get("bill_source", ""),
                    })

    # 同日同额分组
    by_date_amt = defaultdict(list)
    for r in all_rows:
        key = (r["date"], round(r["amount"], 2))
        by_date_amt[key].append(r)

    # 同账户重复（同日同额、同一账户、counterparty不同）
    same_acct = {}
    for (date, amt), items in by_date_amt.items():
        accts = set(r["account_name"] for r in items)
        cps = set(r["counterparty"] for r in items)
        if len(accts) == 1 and len(cps) > 1 and len(items) >= 2 and abs(amt) > 50:
            same_acct[(date, amt)] = items

    # 一进一出对子（净额=0）
    zero_pairs = {}
    for (date, amt), items in by_date_amt.items():
        if len(items) == 2 and items[0]["amount"] + items[1]["amount"] == 0:
            zero_pairs[(date, amt)] = items

    return same_acct, zero_pairs
```

## ⚠️ 关键陷阱：关键词匹配假阳性极高，不能匹配 account_name

排查"转账被误记为收支"时，**绝不能把 `account_name` 拼进匹配文本**。典型翻车：用 `'还款'` 关键词对 `counterparty+description+account_name` 做子串匹配，命中 **3553 条**——但绝大多数是**正常刷卡消费**，因为 `account_name` 含"工行信用卡"三个字。

正确做法：
- **精确匹配 `description.strip()`** 等于 `充值/提现/群收款/转账/转账备注:微信转账` 等词，而不是子串包含。
- 匹配 `counterparty` 而非 `account_name`。
- 任何批量分类前，先按关键词分桶打印每桶的 `(counterparty, description, source)` **组合样本**，人眼确认假阳性率。

## 污染规模基线（2023-06 ~ 2026-06，~10,776 条）

- 正确标为 transfer/transfer_in/transfer_out 的记录：**仅 ~64 条**。
- 高置信度"转账被误记为收支"（精确匹配 description）：**~523 条**。
- 跨渠道重复（reconcile 未捕获）：226 组同账户重复 + 44 对铁路重复。

## 已实施修复：dedup_cross_source（2026-07）

`dedup.py` 新增 `dedup_cross_source()` 函数，在 reconcile 中于 `dedup_with_pairs()` 之后调用。

**修复逻辑：** 按 `(account_name, 日期, 金额, 类别)` 分组，只处理恰好2笔的组：
- **跨源2笔**（alipay/wechat + bank 各1笔）：保留 alipay/wechat（信息更丰富），删除 bank 重复
- **同源2笔**（同一来源重复记录）：保留信息量更多的，删除重复
- 3+笔的组不自动处理（可能含不同交易，留给人工审查）

**预期效果（2023-06 ~ 2026-06）：**
- 跨源2笔：1,626 组 → 去除 1,626 笔
- 同源2笔：223 组 → 去除 223 笔
- 合计去除 1,849 笔，收支各虚增约 ¥15-20k 被修正

**设计决策：**
- 只处理恰好2笔（高置信度），不处理3+笔（避免误伤不同交易）
- 保留优先级：alipay > wechat > bank（信息量递减）
- 同源时选 counterparty+description 最长的记录保留

## report.py 的结构性缺陷（截至 2026-07）

1. **没有"净收入"指标**。只有 `report_income` 和 `report_expense`，没有 `income − expense`。
2. **收支求和不排除 transfer 污染**。

建议落地路线（TDD，每步用户确认）：
1. 加 `净收入 = income − expense`。
2. 加 `ft report --monthly`：按月输出收入/支出/净收入趋势。
3. 一次性数据修复：跨渠道重复逐条经用户确认后删除（禁止静默批量删）。
