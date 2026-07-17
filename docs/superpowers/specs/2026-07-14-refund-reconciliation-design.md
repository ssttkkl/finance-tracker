# Reconcile 退款核销设计

## 目标

在 `ft reconcile` 中，在镜像去重完成后实际执行退款核销：强匹配自动处理，弱匹配经 pending 审查确认后处理。全额退款不在正式账本中保留记录；部分退款保留净额后的原消费记录。所有自动和人工结果必须可由 audit 追溯。

## 当前基础

`ft convert` 已识别退款关系，但只将关系写入正式 records 的下列元数据：

- `offset_group`、`offset_role`
- `offset_strength`、`offset_source`、`offset_rule_hint`
- `offset_match_type`
- 退款行的 `proposed_action=merge_refund_into:<expense_record_id>`

convert 不删除或净额化事实。当前 reconcile 会保留这些字段，并在 pending 中携带关联链路，但不会应用退款合并动作。

## 流程

```text
records
  -> 镜像去重、转账识别
  -> 将退款关系重绑到去重保留侧
  -> 从去重后的保留记录解析完整退款链
  -> strong 自动核销
  -> weak 退款链进入 pending，等待明确决定
  -> 写回 records、重建 snapshot、写 audit
```

退款核销必须在镜像去重后执行，避免将已删除的镜像候选作为退款原消费。

### 去重后的关系重绑

- 镜像去重必须提供删除记录 `record_id` 到保留记录 `record_id` 的规范化映射，并支持沿多个去重结果传递解析到最终保留侧。
- 若退款行或目标消费行被镜像去重删除，系统将这条退款关系的对应端重写为映射后的保留记录，而不是保留对已删除 ID 的引用。
- 重绑后的消费和退款继续参加 strong/weak 退款核销判断；因此“银行卡消费被微信消费去重替代”时，原来关联银行卡消费的退款可核销到保留的微信消费。
- 若同一保留退款重绑后指向多个不同消费，或重绑后金额、币种、账户、方向不再满足关系约束，不得自动核销；该链路降级为 weak 并进入 pending。
- 若一侧已删除且不存在保留侧映射，幸存记录保留为未核销事实，其退款关系元数据清空，且 audit 记录关系因去重失效。

### Strong 退款

- 仅处理消费和退款都仍在去重后保留集内的完整关系。
- 部分退款：保留原消费的 `record_id`，金额更新为 `expense.amount + refund.amount`；退款行删除。
- 全额退款：删除消费行和退款行，不写入零金额记录。
- 对保留的净额消费，清空所有 `offset_*` 字段，并将 `proposed_action` 重置为 `leave_as_is`，使后续 reconcile 不会重复核销该关系。
- `locked=1` 的任一关联行不自动修改；该关系按未处理保留。

### Weak 退款

- weak 退款关系本身必须触发 reconcile pending，即使没有弱镜像候选。
- pending 的 `ai_working.csv` 必须包含退款行、目标消费行以及同一退款组的关联行；不得只展示其中一侧。
- 审查者确认时，在退款行设置 `decision_action=merge_refund_into:<expense_record_id>` 并填写 `decision_reason`。
- 审查者拒绝时，设置 `leave_as_is` 并填写 `decision_reason`；两条事实和原有关系元数据保持不变。
- 确认合并后的部分和全额退款，采用与 strong 相同的净额化和删除规则。

### 与其他 pending 的关系

- 若同一 scope 同时有强退款和任意 pending 原因，强退款的 records 改动可以先写入，但其 audit 行必须保留在 `proposed_audit.csv`，由 `--continue-with-decisions` 一并落盘。
- 若同一 scope 同时有弱退款、弱镜像或未解决候选，所有必要的退款关联链均进入同一个 pending 会话。
- `--abort` 不应应用 weak 退款；已经按现有 mixed pending 语义写入的自动 strong 结果不回滚。

## 审计

每次退款核销为消费和退款各写一条 audit 行，至少包含：

- 原始 `record_id`、金额、文件位置和退款关系字段。
- `reconcile_status`，区分自动或人工、部分核销或全额核销。
- 对方记录 ID、对方文件、对方账户、对方币种和对方金额。
- 匹配规则（`offset_rule_hint`）和置信度（strong 或人工确认的 weak）。

因镜像去重而重绑、冲突降级或失效的退款关系也必须写 audit，记录原始关联 ID、最终关联 ID 及具体结果。

全额退款的 audit 是被删除记录存在过的唯一正式追溯证据。部分退款的 audit 必须保留核销前的原始消费金额，不能只记录修改后的净额。

## 实现边界

- 复用 `merge_refund_into:<record_id>` 作为人工确认动作，不引入新的工作 CSV 动作类型。
- 自动处理和人工确认处理应复用同一个退款结算逻辑，保证金额、字段清理和跨文件写回一致。
- 正式 records 保持现有 CSV schema，不新增会话字段。
- 不改变 convert 的候选识别和 strong/weak 分类规则；本次只消费其已落盘的关系元数据。
- 不改变镜像去重、转账识别和 `locked=1` 的既有语义。

## 验收与测试

必须覆盖以下行为：

1. 无 pending 时，strong 部分退款写回净额消费、删除退款并清理关系元数据。
2. 无 pending 时，strong 全额退款删除两条正式 records，并产生两条审计记录。
3. weak 退款即使没有弱镜像候选也创建 pending，且工作表含完整关联链。
4. weak 退款经 `merge_refund_into` 确认后，部分与全额退款分别正确写回。
5. weak 退款经 `leave_as_is` 拒绝后，两条记录及关系字段不变。
6. 退款与消费在不同 records 文件时，写回与删除仍正确。
7. 任一关联行 locked 时不发生自动强核销。
8. 同时存在自动强退款和其他 pending 时，continue 后 audit 同时保留自动与人工结果。
9. 退款或消费被镜像去重删除时，关系重绑到去重保留侧后仍可正确核销。
10. 重绑后产生多个目标或不再满足约束时，退款链进入 pending 而不自动核销。
11. 重绑缺少保留侧时，幸存事实不变、关系元数据被清理且 audit 记录失效原因。
12. 再次运行相同 scope 不重复处理已结算的退款，`ft verify` 可重放得到一致 snapshot。
