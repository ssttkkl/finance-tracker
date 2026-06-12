# 统一中间 CSV 格式（8+1字段，无 platform）

三步管道（convert → merge → append）各步骤间传递的中间数据格式。

## 字段定义（9字段）

| 字段 | 必填 | 说明 |
|------|------|------|
| `date` | 是 | `YYYY-MM-DD HH:MM:SS` 精确到秒 |
| `amount` | 是 | 带符号浮点数。负=支出，正=收入 |
| `currency` | 是 | `CNY` / `USD` / `HKD` |
| `counterparty` | 否 | **标准化商户名** — 品牌匹配→O2O剥离→原样三级规范化 |
| `description` | 否 | 商品说明/品牌提取残留信息，截80字 |
| `category` | 是 | `income` / `expense`（convert 只按金额正负分类） |
| `account_name` | 否 | 目标账户名（需与 `accounts.name` + `currency` 精确匹配） |
| `source` | 是 | **支付源**（怎么付的）：支付宝/微信/美团支付/京东支付/微信支付/银行卡等 |
| `bill_source` | 否 | 账单来源：alipay/wechat/icbc_credit/icbc_debit/ccb_debit |

注意：`platform` 字段已于 2026-06-13 移除，由 `counterparty` 三级规范化替代。

## 示例

### 支付宝账单 → 统一CSV
```csv
date,amount,currency,counterparty,description,category,account_name,source,bill_source
2026-06-06 23:46:23,-31.00,CNY,t***4,镭风HD6450显卡,expense,网商储蓄卡(4164),支付宝,alipay
2026-06-06 14:38:38,-100.00,CNY,DeepSeek,DeepSeek-API服务,expense,工行信用卡(1200),支付宝,alipay
2026-06-04 18:33:15,-8.70,CNY,淘宝闪购,LINLEE柠檬茶,expense,工行信用卡(1200),支付宝,alipay
```

### 微信账单 → 统一CSV
```csv
date,amount,currency,counterparty,description,category,account_name,source,bill_source
2026-06-09 12:24:54,-4.50,CNY,便利蜂,便利蜂购物,expense,工行信用卡(1200),微信,wechat
2026-06-09 12:20:50,-0.75,CNY,美团,先骑后付,expense,微信零钱,微信,wechat
2026-05-23 19:20:01,-56.00,CNY,群收款,群收款,expense,建行储蓄卡(2820),微信,wechat
```

### 信用卡账单 → 统一CSV
```csv
date,amount,currency,counterparty,description,category,account_name,source,bill_source
2026-01-01 13:00:40,-16.30,CNY,霸王茶姬,美团App霸王茶姬,expense,工行信用卡(1200),美团支付,icbc_credit
2026-01-21 21:13:04,-22.00,CNY,高德,,expense,工行信用卡(1200),支付宝,icbc_credit
2026-02-22 12:42:49,-100.00,CNY,瑞幸咖啡,财付通-瑞幸咖啡,expense,工行信用卡(1200),微信支付,icbc_credit
```

## counterparty 规范化说明

convert 阶段自动调用 `_normalize_counterparty(raw_cp, raw_desc, source)` 完成三级改改：

1. **品牌匹配** — 匹配 44 条规则→cp=品牌名，剩余文本(门店/商品)→description
2. **O2O 前缀剥离** — 去掉 美团App/饿了么/大众点评/高德团购→cp=商铺名
3. **原样保留** — 无匹配

参考 SKILL.md 中「counterparty 规范化」节获取完整规则和示例。

## source（支付源）— 各账单的取值

| 账单类型 | source 值 |
|----------|-----------|
| 支付宝 | 全部为 `支付宝` |
| 微信 | 全部为 `微信` |
| 信用卡（交易场所含前缀） | `美团支付` / `京东支付` / `微信支付` / `支付宝` / `网银在线` / `Apple Pay` / `拼多多支付` / `抖音支付` / `携程` |
| 信用卡（无前缀/直接刷卡） | `银行卡` |

信用卡的支付源从交易场所列的前缀推断（PAYMENT_SOURCE_RULES 常量）：

| 前缀匹配 | source 值 |
|---------|-----------|
| "美团支付" in text | 美团支付 |
| "京东支付" in text | 京东支付 |
| "财付通(银联云闪付)" in text | 银联云闪付 |
| "财付通" in text | 微信支付 |
| "支付宝" in text | 支付宝 |
| "网银在线" in text | 网银在线 |
| "Apple.com/bill" in text | Apple Pay |
| "拼多多支付" in text | 拼多多支付 |
| "程支付" in text | 携程 |
| "抖音支付" in text | 抖音支付 |
| 无匹配 | 银行卡 |

## 字段映射（各来源 → 统一CSV）

### 支付宝
| 原始列 | 目标字段 | 处理 |
|--------|---------|------|
| `交易时间` | `date` | 完整保留到秒 |
| `金额` + `收/支` | `amount` | 支出取负，收入取正，0元跳过 |
| `交易对方` | `counterparty` | → 传入 `_normalize_counterparty()` 规范化 |
| `商品说明` | `description` | → 传入 `_normalize_counterparty()`，接收规范化后的 enriched desc |
| `收/付款方式` | → mapping → `account_name` | 传给 mapping 规则 |
| 金额正负 | `category` | 负→expense，正→income |
| 账单类型 | `source` | 固定 `支付宝` |
| 无币种列 | `currency` | 默认 CNY |

### 微信
类似支付宝。`商品`列空或为`/`时用`交易类型`列回填。`source`固定`微信`。

### 信用卡（ICBC PDF）
`交易场所`列描述 → `_infer_payment_source("icbc", …, …)` → `source`
`交易场所`列描述 → `_normalize_counterparty("", description, "icbc")` → `counterparty`/`description`
