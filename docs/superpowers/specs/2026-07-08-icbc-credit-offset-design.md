# 工行信用卡消费冲减识别设计

> 目标：覆盖工行信用卡真实账单中所有会降低真实消费金额的回补/返还场景，做到 **零遗漏入口识别**，并把自动核销的最高优先级定义为：**最终净额正确、账户余额正确、消费统计正确**。在满足这三点时，允许对同类多候选消费做保守近邻归并，不强求严格回链到唯一原单。

## 1. 背景

当前 `finance-tracker` 已能识别工行信用卡中的多类消费冲减，并把 `merchant_refund` 送入 `_pair_refunds()`。但当前 merchant refund 的 `strong` 判定仍然过于强调“唯一原单”，导致大量真实账单中的同平台多候选退款只能停留在 weak/pending。

结合真实工行信用卡 PDF 回放，用户重新明确了自动核销的优先级：

1. 最终净额正确
2. 账户余额正确
3. 消费统计正确
4. 在满足以上三点时，允许同类多候选做保守近邻归并
5. 不强求严格回链到唯一原单

因此，本设计要把 merchant refund 的自动核销目标，从“唯一原单安全”调整为“净额安全优先”。

## 2. 真实风险定义

用户明确指出，真正必须防止的不是“退错到同品牌另一单”，而是以下四类错误：

1. **漏掉退款**
   - 导致净支出偏高
2. **核销到不同消费类型**
   - 例如把交通退款核销到餐饮消费
3. **核销到不同账户**
   - 例如退款落在不同资金账户簇，却被错误并单
4. **错误金额核销**
   - 例如部分退款冲超原消费剩余额

本设计的所有 strong 规则，都必须直接围绕这四类风险展开。

## 3. 总体策略

### 3.1 零遗漏入口识别

凡是会降低真实消费金额的记录，都必须先识别进统一 `offset_candidates`。即便后续不能自动核销，也不能漏掉。

### 3.2 自动核销以“净额安全”优先

对 merchant refund，是否 `strong` 不再以“候选是否唯一”为核心，而是以以下三点为核心：

- 是否保持最终净额正确
- 是否保持账户余额正确
- 是否保持消费统计正确

### 3.3 只有风险可控时才允许多候选近邻归并

如果候选虽然不唯一，但都落在：

- 同一消费类型簇
- 同一账户簇
- 同一币种
- 且退款金额不冲超

则允许按最近且可覆盖金额的消费做自动归并。

### 3.4 非商户型 offset 不回链原消费

以下类别即便 strong，也不并回原消费，只保留为消费冲减型 income：

- `benefit_rebate`
- `campaign_cashback`
- `fee_reversal`

这样能保证统计正确，同时避免无意义错配。

## 4. 统一 offset 分类模型

### 4.1 offset_type

所有工行信用卡消费冲减候选统一归为四类：

1. `merchant_refund`
   - 商户退款/退货
   - 例：`退货`、`退款`

2. `benefit_rebate`
   - 刷卡金类返还
   - 例：`刷卡金入账`、`刷卡金退款`

3. `campaign_cashback`
   - 活动返现类返还
   - 例：`返现`、`Rebate`、`HKMetroRebate`

4. `fee_reversal`
   - 费用返还/减免
   - 例：`减免年费`

### 4.2 分类优先级

同一条记录若同时含多个信号，必须按如下顺序判定：

1. `fee_reversal`
2. `benefit_rebate`
3. `campaign_cashback`
4. `merchant_refund`

原因：

- `减免年费` 可能结构上被 `退货` 包住，但本质不是商户退款
- `刷卡金退款` 不能误入 `merchant_refund`
- `返现` / `Rebate` 不应被 `退货` 抢走

## 5. 入口识别规则（zero-miss）

### 5.1 merchant_refund 入口

命中任一：

- 文本含 `退货`
- 文本含 `退款`

前提：未命中更高优先级类型。

### 5.2 benefit_rebate 入口

命中任一：

- `刷卡金入账`
- `刷卡金退款`

### 5.3 campaign_cashback 入口

命中任一：

- `返现`
- `Rebate`
- `美国运通人民币卡...返现`
- `HKMetroRebate`

### 5.4 fee_reversal 入口

命中任一：

- `减免年费`
- 已知年费返还/减免文本

## 6. merchant_refund 候选与匹配来源

仅 `merchant_refund` 尝试修改原消费。

### 6.1 候选收集顺序

1. `refund_raw_cp_match`
2. `refund_cp_match`
3. 平台域匹配
4. `refund_desc_fallback`
5. `refund_gross_candidate`

### 6.2 fallback 始终不能 strong

以下来源一律不能直接 strong：

- `refund_desc_fallback`
- `refund_gross_candidate`
- OCR 脏商户文本

## 7. 新的 strong 判定：净额安全优先

### 7.1 非商户 offset 的 strong

以下类别只要语义稳定，默认 `strong`：

- `benefit_rebate`
- `campaign_cashback`
- `fee_reversal`

动作统一为：

- `keep_as_offset_income`

因为它们不会修改原消费，不存在错并原单风险。

### 7.2 merchant_refund 的 strong

`merchant_refund` 不再要求“唯一原单”，而是要求 **净额安全**。只有同时满足以下条件，才允许 `strong`：

1. `offset_type == merchant_refund`
2. 匹配来源不是 fallback
   - 非 `refund_desc_fallback`
   - 非 `refund_gross_candidate`
3. 商户文本可信
4. 候选消费都属于同一消费类型簇
5. 候选消费都属于同一账户簇
6. 币种一致
7. 退款金额不超过被选消费当前剩余额
8. 在安全时间窗内
9. 若候选不唯一，则采用“最近且金额可覆盖”的保守近邻归并

### 7.3 消费类型簇

自动核销不能跨消费类型。当前真实样本里，至少要稳定覆盖这些簇：

- `railway_travel`：`中国铁路网络有限公司`
- `ecommerce_jd`：京东 / 网银在线京东体系
- `local_life_meituan`：美团 / 北京象鲜科技有限公司 / 美团相关本地生活
- `device_service`：`自助侠`
- `rideshare_travel`：携程 / 去哪儿等稳定旅行域
- `group_buy_food`：拼多多 / 抖音平台内可明确识别的同域消费

规则要求：

- 若同一个 refund 的候选落在多个类型簇中，一律不能 strong
- 只有全部候选都落在同一类型簇时，才允许近邻归并

### 7.4 账户簇

工行信用卡真实账单里，很多退款只暴露支付通道而不是独立资金账户，因此不能机械要求 `payment_method` 文本完全一致。

本设计使用“账户簇”概念：

- 只要消费与退款都来自同一张工行信用卡账单、同一卡号范围内
- 且支付方式属于同一信用卡消费通道簇
- 就视为同账户簇

例如以下可视作同一信用卡账户簇内的通道：

- `银行卡`
- `支付宝`
- `微信支付`
- `京东支付`
- `美团支付`
- `网银在线`
- `拼多多支付`
- `携程`
- `抖音支付`

若未来账单字段能明确识别出不同卡或不同资金账户，则必须收紧；当前设计仅在“同卡号 + 同信用卡账单上下文”内放宽。

### 7.5 多候选近邻归并

当候选数大于 1 时，不再直接降级 weak。若同时满足：

- 候选都在同一消费类型簇
- 候选都在同一账户簇
- 币种一致
- 退款金额不冲超

则按以下顺序选择自动核销对象：

1. 优先最近消费
2. 若最近消费剩余额可覆盖退款金额，则选它
3. 若最近消费不可覆盖，则选次近且可覆盖的消费
4. 若不存在任何可覆盖候选，则 weak

这是“保守近邻归并”：

- 不追求唯一原单
- 但保证净额、账户和统计口径不出错

## 8. weak 条件

以下任一命中即 weak：

1. 候选跨消费类型簇
2. 候选跨账户簇
3. 退款金额冲超全部可选消费剩余额
4. 商户文本脏 / OCR 截断 / 不可信
5. 只靠 `refund_desc_fallback`
6. 只靠 `refund_gross_candidate`
7. 类型簇不稳定
8. 账户簇不稳定

weak 的动作统一为：

- 进入 pending / AI 审查

## 9. 输出行为

### 9.1 merchant_refund

- `strong`
  - 全额退款 → 核销原消费
  - 部分退款 → 净额化原消费
  - 写入 tracking / refunds trace
- `weak`
  - 保留候选信息
  - 进入 pending

### 9.2 benefit_rebate / campaign_cashback / fee_reversal

- `strong`
  - 保留为 `income`
  - 增加消费冲减语义标签
  - 统计时抵减消费总额
- `weak`
  - 进入 pending

## 10. 建议内部字段

- `offset_type`
- `offset_strength`
- `offset_action`
- `offset_signal`
- `match_strength`
- `pending_required`
- `candidate_count`
- `offset_cluster`
- `account_cluster`

其中新增重点是：

- `offset_cluster`：消费类型簇
- `account_cluster`：账户簇

## 11. 验证标准

### 11.1 零遗漏验证

以下文本必须全部命中某个 `offset_type`：

- `退货`
- `退款`
- `刷卡金退款`
- `刷卡金入账`
- `返现`
- `Rebate`
- `减免年费`

### 11.2 自动核销安全验证

所有 `strong-merchant` 必须满足：

- 不跨消费类型簇
- 不跨账户簇
- 不冲超剩余额
- 不依赖 fallback
- 时间窗安全

### 11.3 真实样本回放重点

至少验证：

1. `中国铁路网络有限公司` 多候选退款可按近邻安全归并
2. 京东多候选退款在同类购物簇内可安全归并
3. 美团多候选退款在同类本地生活簇内可安全归并
4. `自助侠` 小额重复退款可安全归并
5. 一旦候选跨类型簇，不能误 strong
6. 一旦退款金额冲超，不能误 strong
7. 福利返还类全部保留为 offset income，不漏

## 12. 实施边界

本设计只覆盖：

- 工行信用卡 `convert` 阶段的消费冲减识别、分类、strong/weak 判定与配对

不扩大到：

- 工行借记卡 reversal 规则重写
- 微信/支付宝主链路重构
- reconcile 口径改造

## 13. 推荐实现顺序

1. 保持现有零遗漏入口识别
2. 增加 merchant refund 的消费类型簇识别
3. 增加账户簇识别
4. 把 `candidate_count != 1` 的强限制放宽为“同簇近邻可归并”
5. 增加真实样本回放测试，验证不跨类、不跨账户、不冲超

## 14. 自检结论

结合真实账单回放，当前最适合放宽 strong 的场景不是“任意多候选退款”，而是：

- 同平台 / 同商户域
- 同消费类型簇
- 同信用卡账户簇
- 不冲超
- 近时间窗

因此，最终推荐方案是：

- **入口识别继续零遗漏**
- **福利返还类继续直接 strong 为 offset income**
- **merchant refund 改成净额安全优先 strong，不再死卡唯一原单**
- **真正不安全的跨类、跨账户、冲超、脏文本样本继续 weak**
