# 拆分单一 security 账户 → 多个券商账户

**触发场景：** 一个 security 账户（如 `IBKR`）里其实混着多个真实券商的持仓（同一只票分散在多家券商，股数对不上单一 App），需要按真实券商拆成独立账户。典型证据：某只股票原记录股数 = 券商A股数 + 券商B股数。

## 铁律：先闭合核对，再动手写

拆分前必须让**每只股票的股数在拆分后精确等于原记录**，且用 App 截图的市值/净值做自洽校验。数据准确性零容忍 —— 任何缺口（如某只票总数对不上、某账户没着落）都必须让用户澄清，不许猜归属。

### 步骤 1：收集每个券商 App 的持仓截图，逐张校验自洽性
每张截图先自校验，确认数据可信再采用：
```python
# 市值校验: shares × price ?= 截图市值
# 净值校验: Σ市值 + 现金 ?= 截图账户净值(允许 ~$0.1 舍入 + 实时价漂移)
```

### 步骤 2：从盈亏金额反推成本均价（截图常只给盈亏，不给均价）
```python
cost_total = market_value - gain_loss      # 盈亏为负=亏损
avg_cost   = cost_total / shares
# 例: SPCX 5股 现价165.86 市值829.30 盈亏-108.9
#     成本总额 = 829.30 - (-108.9) = 938.20  ->  均价 187.64
```
注意区分"盈亏金额"和"均价"——用户可能直接报盈亏数（负数），别当成均价。

### 步骤 3：全局闭合核对（关键门禁）
```python
# 各账户股数求和 == 原始单一账户记录; 任一缺口都要停下问用户
# 现金：以各 App 实际显示为准，原合并账户的现金(常是跨券商混同步的融资数)作废
# 若有已清仓的票(如 QLD)，用一笔卖出解释现金差: proceeds = shares × 清仓价
```
现金差通常无法用旧合并数精确对上（跨券商混同步），确认后以三个 App 实际现金为准。

## 执行顺序（每步都是暂存，最后统一 commit）

```bash
# 1. 建新券商账户
ft acct add 盈立证券 --type security --currency USD
ft acct add 嘉信证券 --type security --currency USD

# 2. 已清仓的票记一笔卖出（留审计痕迹，不要凭空抹掉）
ft stock sell --account IBKR --ticker qld.us --shares 51 --price 95.24

# 3. 逐账户 checkin 写股数+均价（⚠️ 见下方 checkin 陷阱）
ft stock checkin --account 盈立证券 --ticker spcx.us --shares 5 --avg-cost 187.64
ft stock checkin --account 盈立证券 --cash 4756.90 --currency USD
ft stock checkin --account 嘉信证券 --ticker avgo.us --shares 7 --avg-cost 391.16
# 保留原账户(如 IBKR)的票: checkin 校正为真实股数+均价(原混合均价作废)
ft stock checkin --account IBKR --ticker avgo.us --shares 5 --avg-cost 360.45
ft stock checkin --account IBKR --cash -2248 --currency USD

# 4. 复核 → 用户确认 → 提交
ft stock list          # 逐账户核对股数/均价/现金 vs 截图
ft commit
```

## checkin 陷阱：无法用它移除/归零持仓

`ft stock checkin` **要求 `--ticker` + `--shares` + `--avg-cost` 三者同时给**，否则报
`❌ 请指定 --ticker+--shares+--avg-cost 或 --cash`。
所以**不能用 `--shares 0` 把一只票从账户移除**（三缺一直接失败）。

搬走后原账户里的残留持仓（如 SPCX/MRVL 已搬到盈立，但仍留在 IBKR），只能**直接编辑 `~/.ft/snapshot.yaml`** 删掉对应 `ticker:` 块：
```
# 用 patch 删除 positions 下的整块(3行: ticker / avg_cost / shares)
# 残留块常带 bug 值(如 avg_cost: -101.86, 或 0.0)，可作唯一定位锚点
```
删完 `ft stock list` 复核该账户已干净。（`ft verify --fix` 是增量重建，不会自动删已无来源的持仓块。）

## 收尾核对
- 拆分后各账户合计 vs 截图净值：差异应只来自实时现价波动（系统用 yfinance 最新价，截图是当时价），成本/股数/现金必须精确对齐
- 顺手修掉搬迁过程中暴露的 bug 均价（如 SPCX 的 -101.86 → 正确 187.64）
