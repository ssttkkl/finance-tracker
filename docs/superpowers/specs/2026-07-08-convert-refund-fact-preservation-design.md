# Convert 退款事实保留与关系输出设计

> 目标：把 `convert` 的退款处理从“强样本直接物理核销”改成“保留付款/退款事实行 + 输出结构化关系建议”，让后续 `reconcile` 能同时看到支付宝/微信/银行卡等多源退款事实，并由强证据源主导跨账单重复消解。最终账本层保留退款记录及其关系字段，但不保留多账单重复镜像记录。

## 1. 背景

当前 `finance-tracker` 的 `convert` 对退款配对后的 strong 样本会直接修改主输出：

- 全额退款：移除原消费与退款两条记录
- 部分退款：把原消费净额化，只保留净额结果
- 退款事实通常只保留在 `*_refunds.csv` 追踪文件中

这种设计在“单源内完成退款核销”时没有问题，但在存在后续 `reconcile` 的总流水线里会出现结构性缺陷：

1. **退款事实丢失**
   - 支付宝/微信在 convert 后主输出里不再保留退款行
   - 银行卡侧即使还有镜像退款，也无法再与强源退款对齐

2. **强源无法主导跨源裁决**
   - 支付宝/微信往往有更完整的订单/商品/状态信息
   - 银行卡只看到平台名、金额、时间
   - 如果银行卡或支付宝任一侧在 convert 阶段就把退款“吞掉”，后续 reconcile 就失去跨源完整视野

3. **convert 承担了过重责任**
   - convert 本应负责标准化和高置信关系识别
   - 不应在证据不完整时提前做最终记账裁决

因此，需要把 `convert` 的退款输出语义改成：

- **保留事实行**
- **输出关系建议**
- **最终是否真正 merge/net，由 reconcile/apply 决定**

## 2. 设计目标

本次设计目标：

1. `convert output.csv` 改成**半成品事实账本**
2. 强/弱退款样本都保留原始付款/退款两条事实行
3. `convert` 对已识别关系输出结构化字段，而不是直接物理核销
4. 后续 `reconcile` 能同时看到：
   - 支付宝付款/退款
   - 微信付款/退款
   - 银行卡付款/退款
5. 强证据源（尤其支付宝/微信）可以在 `reconcile` 阶段主导跨账单重复消解
6. 最终账本层**保留退款记录及其关系字段**，但**不保留多账单重复镜像记录**
7. 现有 pending / AI apply 工作流的关系动作语义尽量复用，不再发明第二套关系表示

## 3. 非目标

本次不做：

1. 不重写整套 reconcile 去重策略
2. 不一次性引入全新的账本存储模型
3. 不改变“退款入口识别”本身的规则体系
4. 不取消 `tracking_pairs`；它可以继续保留作审计/调试
5. 不要求本次立刻把所有历史导入文件自动迁移到新格式

## 4. 核心设计

## 4.1 output.csv 的新语义

`convert` 的 `output.csv` 不再表示“最终可直接记账结果”，而改为：

> **半成品事实账本**：保留原始事实行，同时带有自动推断出的 offset 关系建议。

也就是说：

- 原消费行保留
- 退款行保留
- 若 convert 已判断两者有关联，则把“建议关系”写到字段里
- 不再在 convert 阶段直接删除行或把金额净额化

## 4.2 强/弱语义重定义

现有语义：

- `strong`：convert 直接核销
- `weak`：进入 pending

新语义改为：

- `strong`：高置信关系建议
- `weak`：低置信关系建议，仍需 pending / AI / reconcile 进一步裁决

也就是说，`strong` 不再意味着“立刻 apply”。

## 4.3 proposed_action 写入主输出

`convert` 在识别出退款关系后，直接把动作建议写入 `output.csv`。

推荐复用现有 AI working / apply 语义：

- `leave_as_is`
- `merge_refund_into:<record_id>`
- `net_with:<record_id>`

其中：

- 消费行通常保持 `leave_as_is`
- 退款行写成：
  - `merge_refund_into:<expense_record_id>` 或
  - `net_with:<expense_record_id>`

这样可以保证：

- convert 输出和 pending / apply 使用同一套动作语义
- 后续 reconcile / AI apply 不需要重新理解另一套关系系统

## 4.4 推荐新增字段

在 `output.csv` 中新增以下字段：

1. `record_id`
   - convert 阶段稳定生成的记录 ID
   - 用于 `proposed_action` 引用目标行

2. `offset_group`
   - 同一组消费/退款关系 ID，如 `refund_000123`
   - 方便后续 reconcile / UI / 审计查看整组关系

3. `offset_role`
   - 取值：`expense | refund | offset_income`
   - 本次退款主链路里至少需要 `expense` / `refund`

4. `offset_strength`
   - 取值：`strong | weak | ""`
   - 无 offset 关系的普通交易留空

5. `proposed_action`
   - 取值：`leave_as_is | merge_refund_into:<record_id> | net_with:<record_id> | drop`
   - convert 阶段主要会生成前两类关系动作

6. `offset_source`
   - 标记来源类型，如：`alipay_refund`, `wechat_refund`, `icbc_credit_refund`, `icbc_debit_refund`, `reversal`
   - 供 reconcile 判断强源/弱源时使用

7. `offset_rule_hint`
   - 保留当前 `rule_hint`
   - 便于后续调试、reconcile 优先级判断、AI 审查

8. `offset_match_type`
   - 取值：`full | partial | ""`
   - 表示原关系是全额还是部分退款

## 4.5 撤销交易与退款分开

`撤销交易` 与 `退款/退货` 虽然都属于冲减，但建议继续分开语义：

### 撤销交易
- 本质上更接近“原交易撤销”
- 同一来源内部配对的确定性通常更高
- 仍建议也保留两条事实行 + 关系字段
- 不建议继续在 convert 阶段物理删行

### 退款 / 退货
- 保留原消费行与退款行
- 对 strong / weak 都仅输出关系建议

这样可以让整个 offset 体系在输出语义上统一。

## 5. 各来源策略

## 5.1 支付宝 / 微信

保持现有退款识别与强弱判断逻辑，但修改输出行为：

- 不再把 strong 退款物理核销进消费
- 改为保留：
  - 原消费行
  - 原退款行
- 同时写入：
  - `record_id`
  - `offset_group`
  - `offset_role`
  - `offset_strength`
  - `proposed_action`

这样支付宝/微信这类强证据源在后续 reconcile 时仍然可见。

## 5.2 工行信用卡 / 建行借记卡 / 工行借记卡

同样改成“保留事实 + 输出关系建议”。

尤其是工行借记卡：

- 退款事实必须保留
- 不再在 convert 阶段提前净额化
- 让支付宝/微信在 reconcile 阶段有机会主导跨源消重

## 6. pending 行为调整

当前 pending 会：

- strong-only 场景直接输出最终 CSV
- weak 场景创建 pending session
- `ai_working.csv` 中只放 weak refund pairs

新设计下需调整为：

1. `output.csv` 无论 strong / weak 都保留事实行
2. strong pair：
   - 在 `output.csv` 中写关系字段
   - 不进入 AI 审查
3. weak pair：
   - 仍进入 pending / AI 审查
   - 但主输出中也保留对应事实行和弱关系元数据
4. `ai_working.csv` 可以只装 weak pair 对应的两条行
5. AI 的工作不是“恢复被 convert 吞掉的事实”，而是“修改建议关系”

## 7. apply / reconcile 行为预期

### 7.1 reconcile

`reconcile` 后续应能读取 `convert output.csv` 中的退款关系字段，并据此：

- 看见每个来源的付款事实
- 看见每个来源的退款事实
- 看见 convert 自动给出的建议关系
- 决定是否：
  - 采用强源关系
  - 删除银行卡镜像付款/退款
  - 保留单边事实
  - 覆盖或修改 convert 的原始建议

### 7.1.1 AI 消费时机

本设计明确规定：

- `convert` 输出的 `proposed_action` / `offset_strength` / `offset_group` 等关系字段，**默认不是在 convert 后立即交给 AI 审查消费**
- 它们的默认消费阶段是：**多份来源导入汇总后的 `reconcile` 阶段**

原因：

1. 单源 convert 视角不完整
   - 无法同时看到支付宝/微信/银行卡镜像付款与退款
   - 无法让强证据源覆盖弱证据源

2. 提前 AI 审会过早定案
   - 可能在银行卡侧先做了低质量关系裁决
   - 后续强源导入后已失去完整事实链路

3. 统一在 reconcile 消费更符合全局目标
   - convert 负责“识别事实 + 输出关系建议”
   - reconcile 负责“跨源统一裁决”
   - apply 负责“执行最终净额化”

因此，默认主流程应为：

1. 各来源分别 convert
2. `output.csv` 保留事实 + 关系建议
3. 汇总导入后进入 reconcile
4. reconcile 阶段统一由 AI 消费关系建议
5. 最终 apply 产出成品账本

仅在“明确无后续 reconcile”或“用户要求单源即时落账”的特殊模式下，才考虑 convert 后立即 AI 审查。

### 7.2 apply

最终 apply / 上层消费阶段才把：

- `merge_refund_into:<record_id>`
- `net_with:<record_id>`

真正执行为净额视图或统计视图。

但本设计要求：

- **退款记录本身在最终账本层保留**
- **退款关系字段在最终账本层保留**
- **仅跨账单重复镜像记录在 reconcile 后被删除/不保留**

也就是说：

- convert 输出事实 + 建议
- reconcile 输出去重后的事实账本 + 退款关系裁决
- apply / 上层读取输出净额投影视图

## 8. 文件级影响

预计至少涉及：

- `src/ft/convert.py`
  - 停止 strong 样本的物理核销输出
  - 为退款/撤销关系生成 record_id 和关系字段
  - 扩展 output.csv 列结构

- `src/ft/ai_apply.py`
  - 确认 `merge_refund_into` / `net_with` 语义可直接消费 convert 输出

- `src/ft/reconcile.py`
  - 后续需要读取 output 中新增的 offset 关系字段
  - 但第一阶段可以先只保持兼容，不立刻重写全部 reconcile 决策

- `tests/test_convert.py`
  - 原 strong 测试从“记录被吃掉 / 金额被净额化”改为“事实保留 + 关系字段正确”

- `tests/test_convert_pending.py`
  - 调整 pending 输入预期

- `tests/test_ai_apply.py`
  - 增加 convert 输出关系动作的 apply 覆盖

## 9. 验证标准

## 9.1 convert 输出语义

对 strong refund 样本：

- `output.csv` 中仍同时存在消费行与退款行
- 退款行带 `proposed_action=merge_refund_into:<expense_record_id>` 或 `net_with:<expense_record_id>`
- 两条行共享同一个 `offset_group`
- `offset_strength=strong`

对 weak refund 样本：

- 同样保留事实行
- `offset_strength=weak`
- 仍能进入 pending

## 9.2 跨源可见性

对“支付宝 + 银行卡”都出现的付款/退款镜像：

- convert 后两边主输出里都还能看见付款与退款事实
- reconcile 有能力用支付宝侧更强关系覆盖银行卡侧弱关系

## 9.3 apply 一致性

- 对仅有单源关系的样本，apply 后结果应与旧版 strong 物理核销结果一致
- 即：只是把“何时 apply”后移了，不改变最终可达账本结果

## 10. 设计结论

当前最合适的方案是：

- **把 convert output.csv 改成半成品事实账本**
- **保留付款/退款事实行**
- **将退款核销判断写成结构化关系字段，而不是立刻物理核销**
- **由 reconcile / apply 再决定最终净额化与跨源去重**

这个方案兼顾了：

- convert 阶段的自动识别能力
- reconcile 阶段的跨源全局视角
- 支付宝/微信等强证据源的主导权
- 银行卡弱证据源不提前破坏事实链路
