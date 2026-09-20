## Why

导入真实微信和支付宝账单后，部分平台退款没有与原始支出建立 `refund_offset`，因此在收支账本中被展示为独立收入。根因包括单笔原始支出只能容纳一笔退款、支付宝投资退款未被硬证据路径接受，以及微信红包退回被分类为 `transfer_reversal` 后完全跳过关系扫描。

## What Changes

- 允许同一笔原始支出按剩余金额接受多笔有明确平台订单证据的部分退款，并保持确定性和金额上限。
- 允许支付宝以精确交易订单证据识别投资出账的退款，不改变其原有 `investment_out` 流水类型。
- 为微信个人转账、红包或群收款退回增加专用的精确配对路径；流水仍保持 `transfer_reversal`，关系以 `refund_offset` 的 `p2p_return` 子类型表达。
- 确保已确认的全额平台退款从收支列表隐藏，部分退款只抵扣原始支出的剩余金额。
- 增加针对真实失败模式的单元、集成和浏览器回归证据，保留原始流水、来源快照和关系证据。

## Capabilities

### New Capabilities

- None.

### Modified Capabilities

- `transaction-relations`: 扩展退款冲销的候选范围、部分退款容量和平台退回子类型。
- `cash-record-classification`: 明确 `transfer_reversal` 的分类语义与关系层抵扣语义可以并存。
- `cash-ledger-browser`: 已确认的平台全额退回不得作为独立收入展示，详情仍需可核对关联流水。

## Impact

- 关系扫描领域逻辑、平台退款匹配器及关系提案证据。
- 收支投影和 Web 查询的既有 `refund_offset` 路径；不新增数据库表、不修改金额字段、不改变导入幂等身份。
- 受影响的 SQLite/真实 PostgreSQL 关系契约测试，以及导入后收支账本浏览验证。
- 当前运行时未提供仓库要求的 `grill-me` `/grilling` 会话入口；本变更依据用户明确的修复与合入授权、已完成的真实账单复现和代码调查记录范围。该限制不改变本次验收标准。
