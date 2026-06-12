# 合并 platform → counterparty 设计

> 日期: 2026-06-13
> 状态: 设计完成
> 关联: convert.py, models.py, append.py, merge.py, 存量 records CSV

## 背景

当前 CSV 含 `counterparty`（原始账单交易对方）和 `platform`（规则提取的品牌/平台）两个字段，语义重叠。且原始账单经常将「跟谁交易」和「买了什么/哪家门店」混在 counterparty 里，规范化应拆开。

## 目标

1. 去掉 `platform` 列，CSV 从 10 列减为 9 列
2. `counterparty` = 标准化商户名（品牌名、O2O 商铺名、或原样）
3. 从原始 counterparty 分离出的门店/商品细节迁移到 `description`
4. 存量数据一次迁移，新数据走 `_normalize_counterparty()`

## CSV 字段变更

```
改前（10列）：date, amount, currency, counterparty, description, category,
              account_name, source, platform, bill_source

改后（9列）： date, amount, currency, counterparty, description, category,
              account_name, source, bill_source
```

## counterparty 新语义

标准化商户名，三类来源（优先级递减）：

| 类别 | 来源 | 示例 |
|------|------|------|
| 品牌匹配 | 规则命中品牌/连锁 | cp=`麦当劳` |
| O2O 商铺 | 剥离渠道前缀后的商铺名 | cp=`渝八两重庆鸡公煲` |
| 无匹配 | 原样保留 | cp=`先享后付订单到期扣款` |

## 核心函数

### `_normalize_counterparty(raw_cp, raw_desc, source) → (counterparty, enriched_desc)`

处理顺序（逐级 fallthrough）：

1. **支付前缀剥离** — `_strip_payment_prefix()`，去掉"美团支付-""京东支付-"等
2. **品牌匹配** — `_match_brand()`，复用现有 PLATFORM_RULES 54条规则
   命中：cp=品牌名，残留门店/商品文本→拼到 description
   例外：先骑后付 → cp=`美团`（整个 cp 即触发词，无残留）
3. **O2O 前缀剥离** — `_strip_platform_prefix()`，去掉"美团App""饿了么""大众点评""高德团购"
   命中：cp=商铺名，渠道不进 desc（可从 source/bill_source 追溯）
4. **无匹配** — 返回 (raw_cp, raw_desc) 原样，一条信息不丢

### `_strip_platform_prefix(cp: str) -> str`

剥离的 O2O 中间商前缀：

```
美团App     → 去掉
饿了么       → 去掉
大众点评     → 去掉
高德团购     → 去掉
```

品牌匹配优先于前缀剥离——不会出现 cp="麦当劳" 后被误剥的情况。

## 转化效果示例

| 原始 cp | 原始 desc | → cp | → desc（附加） |
|---------|----------|------|----------------|
| `安尔雅家具京东自营旗舰店` | *(空)* | `京东` | `安尔雅家具` |
| `美团App麦当劳麦咖啡(北京武圣` | *(空)* | `麦当劳` | `麦咖啡(北京武圣)` |
| `美团支付-luckin coffee` | `订单付款` | `瑞幸咖啡` | `订单付款` |
| `美团App渝八两重庆鸡公煲` | *(空)* | `渝八两重庆鸡公煲` | *(空)* |
| `美团AppTimefor牛排火焰牛排饭…` | *(空)* | `Timefor牛排火焰牛排饭…` | *(空)* |
| `先骑后付` | *(空)* | `美团` | `先骑后付` |
| `先享后付订单到期扣款` | *(空)* | `先享后付订单到期扣款` | *(空)* |
| `北京屏芯科技有限公司` | `工资` | `北京屏芯科技有限公司` | `工资` |

## 存量数据迁移

### 迁移脚本

`scripts/migrate_drop_platform.py` — 一次性，扫描 `records/cash/` 和 `records/loan/`：

```
对每个 YYYY-MM-DD.csv：
  1. 读 CSV（当前 10 列）
  2. platform 非空 → cp_new = platform，desc 不动
     （手工拆分混合 cp 风险太高，宁可 desc 少点也不引入错误）
  3. platform 为空 → 跑 _normalize_counterparty(cp, desc, source)
     重写 → (cp_new, desc_new)
     （让新规则作用到存量，O2O 前缀剥离、品牌匹配全补上）
  4. 删 platform 列，写回 9 列 CSV
  5. git auto-commit
```

### 不考虑

- security 目录 — 没有 platform 列，不相关
- 手工数据拆分的准确性 — platform 非空直接覆盖，不做额外拆分

## 下游影响

### 需改动

| 文件 | 改动 |
|------|------|
| `models.py` | `CSV_FIELDS` 去掉 `platform`（10→9） |
| `convert.py` | 加 `_normalize_counterparty()`、`_strip_platform_prefix()`；`_read_alipay_raw`/`_read_wechat_raw` 不再写 platform；`_pair_refunds` 中用 platform 的地方同步改 |
| `append.py` | 写 CSV 列序按新 9 列 |
| `merge.py` | 去掉 platform 列的读写和比较 |
| `dedup.py` | 如有 platform 引用，去掉 |
| `cli.py` | `ft add` 如有 `--platform` 参数，去掉 |
| SKILL.md | 更新 CSV_FIELDS、命令表、账单字段节 |

### 不动

- `snapshot.py` — 不涉及 platform
- `stock.py` / `transfer.py` — 不涉及
- `accounts.py` / `acct.py` — 不涉及
- `report.py` — 不涉及（只读 snapshot）

### 测试

- 查找 `platform` 字符串，更新相关断言
- 加 `_normalize_counterparty` 和 `_strip_platform_prefix` 单元测试
- 存量迁移后跑 `ft verify --fix` 确保快照一致

## 边界约束

- **品牌匹配中的子串误伤**（如"北京东子"含"京东"子串）— `_infer_platform` 已有排除处理，`_normalize_counterparty` 复用同一逻辑
- **O2O 前缀剥离和品牌匹配的先后关系** — 品牌优先，避免"美团"误杀品牌名
- **先骑后付** — 特殊处理：整个 cp 就是触发词「先骑后付」，命中后 cp=`美团`，但无需从 cp 中移除任何残留（cp 原本就是两个汉字）
