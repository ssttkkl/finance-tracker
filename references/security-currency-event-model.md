# 证券多本位币与经济事件建模

适用于修改 `ft stock` 手工命令、security CSV 重放、`verify` 或审查跨币种证券事件。

## 核心区分

`accounts.yaml.base_currencies` 表示该账户**允许持有和结算**的现金币种；它不是任意证券事件的币种白名单。不要因为账户同时支持 USD/HKD/CNY，就把同一只证券的成本、股息或手续费任意改为其中另一币种。

## 事件规则

- 买入/卖出：结算币种必须是账户配置的 base currency；同一非货币 ticker 存在非零仓位时，新交易的成本/结算币种必须与已有 `cost_currency` 一致。冲突应在写入 CSV 与 snapshot 前抛 `ValueError`。
- 现金股息：先按证券的实际派发/结算币种记入。对于已有非零证券仓位，手工 cash dividend 的币种应与该持仓 `cost_currency` 一致；不能把券商换汇混入 dividend。
- 换汇：若券商或用户将现金换为另一币种，必须单独记一笔 `swap`（例如 `USD → HKD`），保留原始股息与换汇两项审计事实。
- 多币种现金：允许账户持有多个已配置 base currency；不得跨币种相加、隐式套汇或用账户默认 currency 覆盖 position 自己的币种。
- 零数量 checkin：必须明确是关闭 position 的语义。直接命令、CSV append、replay、repair 与 verify 对零股票和零现金必须一致，且不得留下无法重放的成本币种冲突或孤立零 position。

## 实施与测试

1. CLI 的 `--currency` 不得硬编码 `CNY/USD/HKD` choices。省略时从账户配置动态解析默认值；显式值大小写规范化后校验属于该账户的 `base_currencies`。缺失 `base_currencies` 时仅回退账户 `currency`。
2. 账户必须在 `accounts.yaml` 存在，且类型为 `security` 或 `crypto`；禁止在 snapshot 隐式创建未知账户。
3. 写入前校验失败必须保证 records 与 snapshot 都没有变化。
4. 覆盖至少：扩展本位币（USDT/USDG）、未知或错误账户、未配置币种、同 ticker USD/CNY 冲突、合法独立 FX swap、股息后 FX swap、零 ticker/cash checkin 的重放一致性。
5. 修改后运行聚焦 stock/CLI 测试、全量 `pytest`、`git diff --check`；对真实账本写入前先在账本外副本重放。
