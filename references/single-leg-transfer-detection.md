# 单腿内部转账识别（reconcile 阶段）

## 背景：两种转账识别互补

`reconcile.py` 里有两类转账识别，缺一不可：

1. **配对型**（原有）：`_match_same_currency_exact` / `_match_fx_loan_repayment`——
   要求**两条腿都在 ft 里**、同额、10 秒内、含转账信号词 → 配成 transfer_out/in。
2. **单腿型**（`transfer_rules.py`，2026-07 新增）：对手方是**金融机构/自有钱包**
   （基金公司、购汇、余额宝/余利宝），另一条腿根本不在 ft 里，**永远配不上对**。
   靠 counterparty+description 规则单条识别。

排查"转账被误记为收支"时，配对型只能抓到很小一部分（真实数据里仅 ~64 条已配对），
大头是单腿型（真实数据 ~123 条，虚增收入 ¥307k + 虚增支出 ¥304k）。

## 单腿规则（都只看 counterparty + description，绝不看 account_name）

| 规则 | 命中判据 | 必须排除的负例 |
|---|---|---|
| `fund_redeem` 基金申赎 | cp 含 `基金销售` / `基金快速赎回` / `基金管理有限公司` | ① desc 含 `收益发放`（余额宝/基金分红=真收入）② cp 含 `搬家`（蚂蚁搬家公司=真消费） |
| `money_fund` 货基搬家 | desc 含 `余利宝`+`转入/转出` / `支付宝转入到余利宝` / `余额宝-单次转入` / `余额宝-转出` | desc 含 `收益发放` |
| `fx_purchase` 购汇 | desc 含 `个人购汇` / `购汇还款` / `预约购汇` / `跨境汇款` | — |
| `fx_cash_leg` 外汇现金腿 | desc **整体等于** `美元` / `港币` / `日元` / `欧元` | `美元消费` / `港币消费` 等真实境外消费 |
| `security_transfer` 银证转账 | cp+desc 含 `银转证` / `银行转证券` / `证转银` / `证券转银行` | — |
| `self_fund` 基金购买/赎回 | desc 含 `基金购买` / `基金赎回` | desc 含 `收益发放`；**不得绑定用户真实姓名**，即使真实账单 counterparty 显示姓名，也要按交易语义识别 |
| `wallet_transfer` 钱包/网商调拨 | `微信零钱提现`+`支付机构提现`；`网商银行`+`转出到网商银行` | `收益发放` / `账户结息` |
| `consumer_loan_repayment` 消费贷还款 | `花呗`+`还款`；`美团金融/美团金融服务/美团月付`+`月付`+`还款`；`京东` 且 desc 精确等于 `还款`；`京东白条`+`还款` | 京东正常消费/商城业务/购物退款 |

金额为负→transfer_out，为正→transfer_in。只对 category∈{income,expense} 的行生效。

**账户建模要点**：当用户确认“应建账户”时，先补 `accounts.yaml` 账户载体再应用历史 records：例如 `美团月付`/`京东白条` 建为 CNY loan，外汇现金链路可给同名借记卡补 USD/HKD/JPY cash 账户（项目允许同名不同币种）。

## 真实数据里踩过的假阳性陷阱（务必保留为单测反例）

1. **`长城基金管理有限公司 desc='...收益发放'`** —— cp 含"基金"但这是余额宝/基金**利息分红，是真收入**，绝不能标转账。→ 规则里 `收益发放` 优先短路返回 False。
2. **`蚂蚁搬家总部... desc='二维码收款'`** —— cp 含"蚂蚁"但是搬家公司**真实消费**。→ `搬家` 短路。
3. **`desc='充值'` 全是假阳性** —— 真实数据里 `desc='充值'` 的 40 条全是建行卡日常小额消费（cp='微信'/'扫二维码付款'，金额 ¥2~¥240，或给第三方平台/游戏充值），**没有一条是自有钱包充值**。绝不能用 `desc='充值'` 标转账。
4. **支付宝把基金申赎写成 `desc='消费'`** —— 不能靠 desc，必须靠 cp 精确匹配基金销售公司名。
5. **account_name 污染**（monthly-analysis.md 已强调）：账户名含"信用卡/储蓄卡"，用 `还款` 等词对 account_name 子串匹配会命中 3553 条正常刷卡消费。规则模块**只读 counterparty+description**。

## 落地方法（TDD + 用户确认，禁止静默批量改）

1. 先用 `execute_code` 对 `~/.ft/records/{cash,loan}/*.csv` **采样**每类交易的真实
   `(counterparty, description)` 组合分布，人眼确认假阳性率，**再**写规则。不要拍脑袋。
2. 规则写进 `src/ft/transfer_rules.py`（`classify_single_leg(row) -> (side, rule) | None`），
   单测 `tests/test_transfer_rules.py` 把正例**和上面每条陷阱反例**都固化。
3. reconcile 里在配对逻辑之后调 `_mark_single_leg_transfers(kept, used_transfer_ids)`，
   复用 `used_transfer_ids` 避免和配对结果冲突；结果进 audit（reconcile_status=`transfer_single_leg`）。
4. **落库前先只读预演**：`execute_code` 全量跑 `classify_single_leg`，导出待改清单
   CSV 给用户过目（按月汇总收支修正额），确认后再 `ft reconcile --from <起> --to <止>` 写盘。
   现有 `ft reconcile` **没有 --dry-run 开关**——外部 execute_code 预演是当前唯一预览手段
   （可考虑给 reconcile 加 --dry-run）。
5. 存量修复靠全量重扫：`ft reconcile --from 2023-06-01 --to 2026-07-31`，audit 留痕可回退，之后 `ft commit`。

## 规则外扩注意

用户原则：**只要是名下资产的位置转移（现金↔基金/外汇/货基/自有钱包）就是转账**。
但"微信个人转账/群收款"是模糊地带——混着真 AA 收支，无法从单条记录判定，**默认不动**。
新增规则前一律先采样验证假阳性，再加单测反例。
