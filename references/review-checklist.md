# 账单审核检查清单

适用于 `ft convert` 后的转换审查（步骤 ②）和 `ft merge` 后的合并审查（步骤 ⑤）。步骤 ③ AI 修正也按此检查项执行修复。

**审查方式：** 每个转换后的 CSV 文件独立审查，每文件分配一个 subagent。subagent 接收文件路径和账单类型，独立读取 CSV + `_refunds.csv` 进行检查。

## 转换审查（步骤 ②）

查看 `ft convert` 输出的 CSV + `_refunds.csv`，按优先级检查：

### P0 — 金额影响（阻塞性）

- 退款配对数学正确性：全额退款 支出+退款=0、部分退款净额正确、多笔退款链累加=原始金额
- **支付宝 gross refund 检测**：同时存在净退款和全额退款时（如 铁路12306 +83.5 和 +1025.5），全额退款等于原始支出金额的应自动核销（`_pair_refunds` 孤退款处理器已实现"原始全额匹配"），不应作为 income 残留在主 CSV
- 部分退款净额在 CSV 中缺失 → 补加
- 孤退款收入混在 CSV 中 → 删除（已在退款 CSV 中处理）
- 重复支出行 → 检查并去重
- 空 counterparty 的消费支出 → 补充 merchant 信息
- **convert 抛出 ValueError**（非警告）→ 检查 `mapping.yaml` 是否有未覆盖的 `payment_method` 模式。常见遗漏：`工商银行储蓄卡(3697)*`、`余额`、`余额宝*`、`中国建设银行储蓄卡(2820)*`（全称前缀）、`工商银行信用卡(9166)*`、`云闪付-*`。注意 `fnmatch("x", "")` → False，`match: ""` 只匹配空字符串

### P1 — source 正确性

- 支付宝 → `支付宝`
- 微信 → `微信`
- ICBC 借记卡 → `银行卡`
- ICBC 信用卡 → 从交易场所前缀推断（美团支付/财付通/京东支付等）
- 建行 → `建行储蓄卡`

### P2 — 数据脱敏

- 检查 CSV 中是否有完整卡号/账号（如 `8888086011314150`）
- 检查是否有真实姓名泄露
- 发现立即用占位符替换
- **描述字段编码异常**：URL 编码（`%E5%8D%A0`）、HTML 实体（`&middot;`）、乱码字符

### P3 — counterparty 规范化

- 品牌名正确提取（`美团App麦当劳` → `麦当劳`）
- O2O 前缀剥离（`美团App` → 去掉）
- 支付前缀剥离（`财付通-` → 去掉）
- 未命中的原样保留

## 合并审查（步骤 ⑤）

查看 `ft merge` 输出的 `merged.csv` 和 `removed.csv`，检查：

- **误删检查**：`removed.csv` 中每一条 `dedup_status=去除` 的行，都必须有对应的 `dedup_status=保留` 行在同一文件内（同一对）。该"保留"行必须在 `merged.csv` 中存在（去重逻辑是保留支付宝/微信版，删除银行版，因此保留行应在 merged 中、去除行不应在 merged 中）。
- **漏删检查**：检查 merged 中是否有同来源+同日+同金额+同counterparty的明显重复行（注意区分同日不同时的多笔独立交易）。
- **跨来源**：不同来源的相同交易保留是预期行为。

## 修复策略

### CSV 层修复（就地修改 CSV 文件）

```python
# 补加缺失的部分退款净额
main.append({...fields...})
main.sort(key=lambda r: r["date"])
```

### 代码层修复

1. 找出转换器中的 bug
2. 写 RED 测试（TDD），确认失败
3. 修复代码，GREEN
4. 重跑：`ft convert -s <type> -o <csv> <原始账单>`

### CCB 转换器常见修复
- `消费-` 前缀剥离：更新 `_extract_ccb_counterparty` 中的 `PAYMENT_PREFIXES` 子前缀列表，添加 `"消费-"`；注意连续剥除（while 循环）
- 证券转账账号脱敏：正则 `r"^(银行转证券|证券转银行|银转证|证转银)\\d+\\S*$"` → 提取纯名称

### ICBC 退款匹配常见修复
- 退货 sentinel 被 normalizer 吞掉：在 normalizer 前打 `_is_refund` 旗标
- ICBC orphan income 残留：`_pair_refunds` 后过滤 `category=income AND cp in (消费,财付通)`
- 退款 refund 的 `_raw_cp` 需在 normalizer 前保存，`_pair_refunds` 中 fallback 匹配

### 映射规则修复
```bash
# 修改 ~/.ft/mapping.yaml，格式：
- source: alipay
  match: "工商银行储蓄卡(3697)*"
  account: "工行借记卡"
  currency: CNY
```

## 输出格式

审查完成后输出结构化报告：

```
## 审查报告：<文件名>

### ✅ 通过检查
- 退款配对：X/X 对正确
- source 正确性：全部一致
- ...

### ⚠️ 发现的问题
| # | 严重程度 | 描述 | 修复 |
|---|---------|------|------|
| 1 | HIGH | XXX | 已修复/建议修复 |

### 修复汇总
- 已修复：XX 个
- 待处理：XX 个
```
