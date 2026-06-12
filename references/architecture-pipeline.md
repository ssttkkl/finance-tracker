# Finance Tracker 数据处理管線

## 整体架构

```
原始文件 ─→ 提取 ─→ 标准化 ─→ 分类(借记卡) ─→ 本来源去重 ─→ 入库 ─→ reconcile(跨来源去重) ─→ 报告
```

## 三个阶段的职责

### ① 提取 (Extract)
| 来源 | 工具 | 产出 |
|------|------|------|
| 支付宝 CSV | 编码检测(GBK/UTF8) + csv.reader | {date, amount, category, counterparty, description} |
| 微信 Excel | openpyxl + 行解析 | 同上 |
| 工行 PDF | qpdf 解密 → mutool draw 提取文本 → 正则解析 | 同上 + 自动判断借记/信用卡 |

### ② 去重 (Dedup)
- **本来源去重**: 导入时按 (date, amount, category) 去重，防止同文件重复
- **跨来源去重** (`ft reconcile`): 导入后统一执行，标记支付宝/微信中与信用卡重复的支出

### ③ 入库 (Import)
- 现金账户: 借记卡(工资/收支) + 支付宝(非信用卡) + 微信(非信用卡)
- 贷款账户: 信用卡(消费+还款)
- 借款账户: 手动 checkin
- 证券账户: 手动 checkin

## 不在此架构中的功能
- 余额快照 (`ft checkin`) — 手动录入，与导入流程无关
- 银证转账 — 暂无自动导入，需手动 checkin
