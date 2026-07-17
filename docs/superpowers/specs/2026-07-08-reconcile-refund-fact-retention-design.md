# Reconcile 保留退款事实并删除跨账单镜像设计

> 目标：在 `reconcile` 阶段延续 `convert` 已输出的退款事实与关系字段，只删除跨账单重复镜像，不再把退款记录本身从最终账本层抹掉。

## 1. 背景

当前 `convert` 已改成“保留事实 + 输出关系字段”：

- 消费行保留
- 退款行保留
- 关系通过 `record_id / offset_* / proposed_action` 输出

但现有 `reconcile` 仍按旧账本结构工作，有两个问题：

1. **关系字段会被丢失**
   - `src/ft/models.py` 里的 `CSV_FIELDS` 仍只有旧 11 列
   - `src/ft/reconcile.py` 的 `_normal_row()` / `_clean_row()` 只保留这 11 列
   - 一旦经过 `reconcile`，`record_id / offset_* / proposed_action` 会被裁掉

2. **审计层看不到退款关系元数据**
   - `proposed_audit.csv` / reconcile 审计文件没有 `record_id / offset_* / proposed_action`
   - 后续无法确认“这条退款为什么保留、删掉的是哪一侧镜像、原始关系建议是什么”

这会破坏当前目标：

- 最终账本层保留退款事实
- 最终账本层保留退款关系字段
- 仅删除多账单重复镜像

## 2. 设计目标

本次 `reconcile` 改造目标：

1. 保留 `convert` 输出的 `record_id / offset_* / proposed_action`
2. `reconcile` 去重后，保留下来的退款记录仍带完整关系字段
3. `reconcile` 仍负责删除跨账单重复镜像记录
4. 审计文件也保留关系字段，便于复盘去重裁决
5. 不把“存在退款关系”误当成“应该删除退款记录”
6. 不重写整套 dedup 算法，只做字段保真与语义收口

## 3. 非目标

本次不做：

1. 不重构 `dedup_with_pairs()` 的核心匹配规则
2. 不在本次把退款关系自动升级成最终净额账本
3. 不改变 pending / AI working 的基础协议
4. 不引入新的存储层或数据库
5. 不迁移历史旧文件；旧文件缺字段时按空值兼容即可

## 4. 最终账本语义

`reconcile` 后的记录层语义明确为：

### 4.1 保留什么

- 被选中保留的付款事实
- 被选中保留的退款事实
- 这些事实行上的：
  - `record_id`
  - `offset_group`
  - `offset_role`
  - `offset_strength`
  - `offset_source`
  - `offset_rule_hint`
  - `offset_match_type`
  - `proposed_action`

### 4.2 删除什么

- 被判定为跨账单重复镜像的付款记录
- 被判定为跨账单重复镜像的退款记录

### 4.3 明确不删除什么

- 不能因为一条记录 `offset_role=refund`
  就在 `reconcile` 中把它视为“待吞并垃圾行”直接删除
- 不能因为 `proposed_action=merge_refund_into:*`
  就在 `reconcile` 阶段物理净额化并移除退款事实

也就是说：

> `reconcile` 只做“跨源去重裁决”，不做“退款事实消失式核销”。

## 5. 字段模型扩展

## 5.1 `models.CSV_FIELDS`

将 `src/ft/models.py` 中的 `CSV_FIELDS` 扩展为至少：

```python
CSV_FIELDS = [
    "record_id",
    "date", "amount", "currency", "counterparty",
    "description", "category", "account_name", "source",
    "bill_source", "transfer_account", "locked",
    "offset_group", "offset_role", "offset_strength",
    "offset_source", "offset_rule_hint", "offset_match_type",
    "proposed_action",
]
```

说明：

- `record_id` 进入正式账本字段集，不再只存在于 AI working 侧
- 旧文件缺这些列时，`csv.DictReader` 读到的值默认为空，`_normal_row()` 可自动补空串兼容

## 5.2 `_normal_row()` / `_clean_row()`

无需额外特殊逻辑，只要 `models.CSV_FIELDS` 扩展后：

- `_normal_row()` 会自动把新字段纳入标准行
- `_clean_row()` 会在去掉 `_record_file` 等内部字段后保留这些关系字段

这意味着：

- 非 scoped 行写回时保留关系字段
- scoped locked 行写回时保留关系字段
- kept 行写回时保留关系字段

## 6. Reconcile 主流程语义

当前 `reconcile` 主流程可继续保持：

1. 读取 records 下 CSV
2. 归一化成 `state["entries"]`
3. 对 scoped active 行执行 `dedup_with_pairs()`
4. 对 `kept` 执行转账识别
5. 将保留行写回原文件

本次只收紧语义：

### 6.1 去重职责保持不变

`dedup_with_pairs()` 仍负责找：

- 同一事实的跨源重复观察
- 哪一条保留、哪一条删除

### 6.2 退款关系字段必须透传

所有写回路径都必须保留：

- `state["entries"]` 中未进入本次 scoped 的行
- `state["scoped_locked"]`
- `state["kept"]`

### 6.3 转账标记不能覆盖退款关系字段

`_mark_transfer()` / `classify_single_leg()` 只允许修改：

- `category`
- `transfer_account`
- `_transfer_rule`

不能清空、重置或误改：

- `record_id`
- `offset_group`
- `offset_role`
- `offset_strength`
- `offset_source`
- `offset_rule_hint`
- `offset_match_type`
- `proposed_action`

## 7. 审计文件扩展

## 7.1 `_audit_fields()`

审计字段增加：

```python
"record_id",
"offset_group", "offset_role", "offset_strength",
"offset_source", "offset_rule_hint", "offset_match_type",
"proposed_action",
```

推荐放在业务主字段之后、`record_file` 之前或附近，便于阅读。

## 7.2 审计输出语义

对于每个 dedup pair：

- 保留侧审计行保留原关系字段
- 删除侧审计行也保留原关系字段

这样能清楚看到：

- 被删的是哪条退款镜像
- 它原本关联到哪个消费
- 强弱关系来自什么来源与规则

对 `transfer_matched` / `transfer_single_leg` 审计行同样保留这些字段，避免转账标记流程把上下文裁掉。

## 8. Pending / AI continue 兼容性

`reconcile` pending 流程当前通过：

- `_create_reconcile_pending_session()`
- `build_ai_working_row()`
- `apply_reconcile_working_rows()`

处理 scoped 行。

本次要求：

1. `build_ai_working_row()` 的输入如果带 `record_id / offset_* / proposed_action`，这些字段应进入 working CSV 的只读基础字段或可保留字段
2. `continue_reconcile()` 写回 `final_rows` 时，不能因为 `models.CSV_FIELDS` 过旧而丢字段
3. AI 审查 `drop` 的对象是“跨源镜像观察行”，不是“所有 refund 行”

换言之：

- `ai_action=drop` 是对某一条 observation 的裁决
- 不是对退款业务事实这个概念本身的否定

## 9. 实现建议

## 9.1 最小改动顺序

1. 扩展 `src/ft/models.py` 的 `CSV_FIELDS`
2. 扩展 `src/ft/reconcile.py` 的 `_audit_fields()`
3. 跑现有 reconcile 流程，确认 `_clean_row()` 与写回逻辑自然保留新字段
4. 增加/修改测试，覆盖：
   - reconcile 后退款字段不丢
   - dedup 删除的只是镜像观察
   - transfer audit 仍带关系字段

## 9.2 推荐测试点

### 用例 A：单文件内仅退款关系，无跨源重复

输入：
- 1 条消费
- 1 条退款
- 退款带 `proposed_action=merge_refund_into:<expense_id>`

期望：
- reconcile 后两条都还在
- `offset_* / proposed_action / record_id` 全保留

### 用例 B：支付宝退款事实 vs 银行卡退款镜像

输入：
- 支付宝消费+退款
- 银行卡消费+退款镜像

期望：
- 去重后仅保留被选中的强源观察
- 保留侧退款记录仍带完整关系字段
- 删除侧出现在 audit 中，并保留自己的关系字段快照

### 用例 C：pending continue 写回

输入：
- reconcile pending session 中，AI 对弱源镜像行标记 `drop`

期望：
- 写回后保留行字段完整
- audit 中能看到 `ai_drop` 与关系字段

## 10. 验证标准

满足以下条件视为本次完成：

1. 任何包含退款关系字段的 records CSV 在经过 `reconcile` 后，新字段不会被抹掉
2. `reconcile` 删除的仅是跨账单重复镜像，不是退款事实本身
3. 审计 CSV 中能完整看到保留侧/删除侧的关系字段
4. `continue_reconcile()` 写回后的文件列结构与 `convert` 新输出结构兼容
5. 上层后续仍可基于最终账本层的退款事实与 `proposed_action` 做净额投影

## 11. 设计结论

本次 `reconcile` 不应把 `convert` 已恢复出来的退款事实再次压扁。

正确职责分层应是：

- `convert`：保留事实，输出关系建议
- `reconcile`：删除跨账单重复镜像，保留被选中事实及其关系字段
- `apply / 上层读取`：在不丢事实的前提下做净额投影或统计

这样才能同时满足：

- 退款永远留底
- 强源主导跨源裁决
- 多账单重复记录不留
- 上层仍可按需要恢复净额视图
