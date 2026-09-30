# cash-ledger/cash-ledger-browser Specification Delta

## MODIFIED Requirements

### Requirement: Cash Ledger uses Finance UI without changing ledger semantics

Cash Ledger MUST 使用已登记的 Finance Button、TextField、Select、DataTable、FilterBar 和 Amount 组件；页面的筛选、分页、查看详情、新增入口、权限和金额语义 MUST 与现有账本合同保持一致。页面 MUST 不直接暴露 Material3 或实现层术语。

#### Scenario: Viewer opens the ledger

- **WHEN** 只读使用者打开 Cash Ledger
- **THEN** 页面 MUST 显示可用的筛选、表格和金额信息
- **AND** 写入操作 MUST 维持既有禁用或拦截语义

### Requirement: Cash Ledger exposes complete observable states

Cash Ledger MUST 为表格、筛选和主要操作提供正常、加载、空、错误、禁用和成功状态；状态 MUST 通过稳定语义 ID 暴露给 Web 与 Native 测试。

#### Scenario: Ledger request fails

- **WHEN** 账本查询返回可识别错误
- **THEN** 页面 MUST 显示可操作错误状态和重试入口
- **AND** 不得显示空白表格或伪造空结果

### Requirement: Cash Ledger follows logical responsive classes

Cash Ledger MUST 按 `compact`、`regular` 和 `wide` 逻辑窗口宽度组织区域；compact MUST 单列且无页面级横向滚动，wide MUST 保留导航、筛选、表格和详情/操作区域关系。

#### Scenario: Ledger renders at compact width

- **WHEN** 可用宽度为 `390` px
- **THEN** 筛选和记录内容 MUST 使用单列或可折叠区域
- **AND** 文本、金额和操作 MUST 不溢出或遮挡
