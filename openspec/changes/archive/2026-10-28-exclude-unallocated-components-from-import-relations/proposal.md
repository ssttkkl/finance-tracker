## Why

现金账单预览会把尚未填写分摊金额的支付宝支付组成项交给关系匹配器，导致金额转换异常并返回 HTTP 500。这样用户无法进入现有的分摊填写流程，也无法继续核对其他有效流水。

## What Changes

- 保留待分摊流水及其 `requires_allocation` 状态，让使用者可以在预览中补齐各支付组成项金额。
- 金额未补齐的流水暂不参与关系匹配；填写精确分摊金额后，重新生成关系预览。
- 最终确认仍要求所有待分摊项金额有效且合计等于父流水金额。
- 修订支付宝组合支付规格，使其与当前 Compose 分摊交互及确认门禁一致。

## Capabilities

### New Capabilities

无。

### Modified Capabilities

- `import/statement-import`：明确未完成分摊行在预览、关系匹配和最终确认阶段的行为。

## Impact

- 影响 `src/ft/application/cash_ledger.py` 的账单关系预览，以及 Compose Web 和 Native 共用的导入 API。
- 不新增数据库表、迁移、依赖或 UI 控件；不改变原始来源证据、金额精度、账户映射和关系规则。
- 不修改旧 SQLite 库；回滚代码会恢复到未分摊流水触发预览异常的状态。

## 后续现金导入重设计的同步边界

`cash-import-lifecycle-redesign` 已声明本变更部分导入条款的后续取代，具体范围见 [取代清单](../2026-10-28-cash-import-lifecycle-redesign/design.md#active-changes-取代与同步顺序)。新变更尚未实施时不改变当前行为；新合同生效后，本变更不得重新同步已经取代的旧流程或规则，须先清理或合并相应 delta。原未完成验证及用户演示仍按原记录保留，不因此自动完成。
