## 1. 思考与计划

- [x] 1.1 使用当前 SQLite 账本核对截图所示跨境汇款与工银亚洲内部调拨，确认现有候选索引和同账户闸门的排除原因。
- [x] 1.2 建立关系匹配范围、隐私边界、唯一性和待配对规则，并完成 OpenSpec delta 规格与设计。

## 2. 失败测试

- [x] 2.1 为工行跨境汇款的转账分类，以及至工银亚洲的短时同币种到账、次日到账、非跨境拒绝和多候选待配对添加领域回归测试。
- [x] 2.2 为工银亚洲同一规范账号的跨币种调拨、同币种精确调拨、金额不等和候选歧义添加领域回归测试。
- [x] 2.3 为 SQLite 与 PostgreSQL 添加关系服务契约，验证完整账户标识归属只在跨境桥接中启用且关系不保留账号原文。

## 3. 实现

- [x] 3.1 在有界候选索引中增加工银亚洲转账入账和跨境汇款出账的候选池。
- [x] 3.2 实现工银亚洲规范账号内部调拨匹配器及双向最近唯一性、子类型和待配对行为。
- [x] 3.3 将工行跨境汇款按方向分类为普通转账，实现至工银亚洲匹配器，并仅在该路径启用规范账号前缀归属。
- [x] 3.4 接入 Phase C，复用既有端点占用、幂等和投影保护，不引入任何持久化过程证据。

## 4. 审查与验证

- [x] 4.1 进行范围与工程复核，检查跨币种误配、账户隔离、来源行纯度、隐私和候选复杂度。
- [x] 4.2 运行新增领域测试、受影响关系测试、SQLite/PostgreSQL 契约、构建、语法检查和 `git diff --check`。
- [x] 4.3 使用本机 `~/.ft` 只读验证新规则候选数量和截图两对流水的预期结果；获得明确授权后才重扫真实关系。
- [x] 4.4 同步主规格并进行 OpenSpec 严格校验；记录发布、回滚和已豁免的 10 万事实性能门禁。

## 验证与复核记录

- 当前 `HEAD`：`a586ea1`；比较基线：当前工作树中的既有未提交变更。未覆盖、重置或清理范围外改动。
- 产品与工程复核：仅工行借记卡、正式类型为 `transfer_out`、且原始文本明确含「跨境汇款」的出账可进入工银亚洲桥接；「购汇」「个人购汇」「预约购汇」「外汇」「汇兑」保持 `fx_out` / `fx_in`。工银亚洲内部调拨才允许跨币种，并标记 `currency_exchange`；跨境汇款桥接要求同币种及金额精确相等。候选以日期桶索引、有界时间窗和双向最近唯一性筛选，歧义仅生成待配对关系。关系记录不保存账号原文或匹配过程信号，`source_payload` 继续只保存完整原始业务行。
- 本机账本只读预演：历史工行借记卡 `fx_out` 跨境汇款与工银亚洲 `transfer_in` 到账相差 7 秒、金额和币种一致；按新分类重放后生成 1 条已接受的 `transfer_pair.icbc_debit.icbc_asia.cross_border.v1`。未改写、重扫或重建 `~/.ft`；历史事实仍须重新导入后才会取得新分类。
- 已执行：`uv run pytest tests/test_convert.py tests/test_convert_normalize.py tests/test_icbc_refund_pairing.py tests/test_transaction_relations_transfer.py tests/test_transaction_relations_open_leg.py tests/test_transaction_relations_projection.py tests/contract/test_dual_backend_counterparty_account_transfer_matching.py tests/contract/test_dual_backend_record_type.py tests/contract/test_dual_backend_icbc_refund_pairing.py -q`，结果 `297 passed, 24 skipped`。
- 已执行真实 PostgreSQL 后端矩阵：`FT_TEST_POSTGRES_URL=… uv run pytest tests/test_transaction_relations_transfer.py tests/test_transaction_relations_open_leg.py tests/test_transaction_relations_projection.py tests/contract/test_dual_backend_counterparty_account_transfer_matching.py tests/contract/test_dual_backend_record_type.py tests/contract/test_dual_backend_icbc_refund_pairing.py -q`，结果 `89 passed`。
- 已执行：`uv run python -m compileall -q src`、`uv build`、`git diff --check`、`openspec validate match-icbc-asia-internal-and-cross-border-transfers --strict`、`openspec validate --all --strict` 和 `openspec doctor`，均通过。
- 发布与回滚：重新导入或重建账本后，新的工行跨境汇款会进入普通转账配对；如需回滚，移除该来源专用桥接规则并将其生成关系标记为 superseded，原始流水不变。用户明确豁免本变更的 10 万事实财富性能门禁；关系候选路径仍以受影响关系矩阵验证。
