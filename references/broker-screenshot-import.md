# 券商活动截图导入与现金校准

适用于 IBKR、嘉信等券商的“已成交/活动”截图录入 `ft`。

## 1. 先从截图取得可审计字段

- **先确认券商和账户归属**；不要根据标的或页面外观猜测。
- 每笔成交至少记录：日期时间、买/卖、ticker、股数、成交价、成交毛额、佣金、场所/订单号（若截图可见）。
- 截图文字较小或 OCR 结果不稳定时，优先用 macOS Vision 的 `VNRecognizeTextRequest` OCR；对于数字字段以原截图复核为准，禁止从 OCR 的缺字或格式错误推测数值。
- 金额要做算术核对：`股数 × 单价` 与截图成交额一致（存在显示精度时，以券商成交额为现金腿）。

## 2. 写入前去重与更正

1. 检索目标账户所有同日期/同 ticker 的 rows，而非仅搜索 ticker。
2. 截图已有的精确成交已存在：不重复追加；若现有记录只有占位时间，更新为截图时间，并补充来源订单号/场所。
3. 发现旧记录把佣金同时塞进现金成交额、又写入 `commission` 时，将现金腿修为**成交毛额**，并保留 `commission_asset=usd`（或实际扣费币种），避免 replay 双扣。
4. 截图覆盖范围以外的历史记录不可因“截图没有出现”就删除。

## 3. 记录活动项

- 买入：`swap,usd,<ticker>,<gross>,<shares>,<price>,<fee>,usd,USD,<broker>,...`
- 卖出：`swap,<ticker>,usd,<shares>,<gross>,<price>,<fee>,usd,USD,<broker>,...`
- 股息：`dividend,DIV,usd,0,<amount>,0,0,,USD,<broker>,<description>`。
- 截图显示的 JRN 扣款、利息支出等现金减少：`withdraw,usd,EXTERNAL,<amount>,0,1,0,,USD,<broker>,<description>`；不擅自把含义不明的 JRN 标为税款。

## 4. 现金余额以用户/券商当前数为准

当用户提供“当前现金应为 X”时：

1. 确认该数对应的时点；检查此后是否还有该账户交易。
2. 若在最后一笔交易之后，追加 `ft stock checkin --account <broker> --cash X`，然后重放验证。
3. 若 checkin 之后仍有交易，按后续成交和佣金反推 checkin 值，不能直接填 X。
4. `ft verify --fix && ft verify` 后，使用 `ft stock list` 复查该账户的现金和股票市值。

## 5. 同步外部账户

对 Polymarket、Kraken 等：先分别 `--dry-run`；确认新增数后顺序执行实际同步（不要并行写入，因为两个同步都会更新快照），随后 `ft verify`、查询持仓、提交。汇报时不得复述钱包地址。
