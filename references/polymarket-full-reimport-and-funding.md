# Polymarket Activity 全量替换与交易所入金

适用：用户要求以 Polymarket 官方 Activity 全量替换账本，或提供交易所资金账单并说明“提币/提现”即 Polymarket 入金。

## 先做只读预检

1. 统计现有 `account_name=Polymarket` 的证券行，按 `action` 分类；单独识别 cash-style transfer 审计行，不能误删。
2. 拉取完整 Activity 并分页，按 `type` 统计，禁止只看 `TRADE` 后声称“全量”。
3. 在 `~/.ft` 仓库外备份 `records/security/`、`snapshot.yaml`、原始 Activity JSON 和待导入 CSV。
4. 在临时 records 副本重放，确认非 Polymarket 账户不变；通过后才写真实账本。

## Activity 建模

- `TRADE`：标准 `swap`。BUY = `USD → pm:<slug>:yes|no`；SELL 反向；保留 API fill/activity id 与 tx hash。
- `REDEEM`：不能静默跳过。API 有时不提供 outcome；按时间顺序重放此前 TRADE，在同一 slug 中找**唯一且精确等于 redeem USDC 金额**的正 token 仓位。仅唯一匹配时写 `swap(pm token → USD, shares=payout, price=1)`；无法唯一匹配则中止并报告，不猜方向。
- `YIELD`：不能静默跳过。写 `dividend(DIV → USD)`，金额采用 `usdcSize`（无该字段时 `size`），note 留 tx hash。
- 非上述类型：抛出带类型和必要上下文的错误，先设计映射，不可漏记。

## 交易所“提币”作为 Polymarket 入金

当用户明确交易所资金账单中的“提币/提现”就是进入 Polymarket：

1. 这不是消费或普通转出，应写 security `deposit(EXTERNAL → usd)`。
2. 用截图的日期、分钟、金额逐笔建行；必要时秒填 `00`，note 标明“交易所提币至 Polymarket（资金账单）”。
3. 若用户要求替换已有入金，先删除**仅** `account_name=Polymarket && action=deposit` 的旧行，再写全部已确认提币；不要碰 TRADE、REDEEM、YIELD 或其他账户行。
4. 交易所订单成交、交易账户内部转入不自动等同入金；只有用户明确指认的提币才写 deposit。

## 导入后的对账

1. `ft verify --fix && ft verify`。
2. 用 Positions API 比对非零 `pm:` 仓位，至少按 API 可见精度比较。
3. 若账本只剩 `<0.01` 的 token、Positions API 对应 size=0，可在末尾追加带原因的 `checkin` 零仓；不要删除官方 TRADE。
4. 报告写入/删除计数、各 action 数、仓位匹配结果与仓库外备份路径；默认不 commit。
