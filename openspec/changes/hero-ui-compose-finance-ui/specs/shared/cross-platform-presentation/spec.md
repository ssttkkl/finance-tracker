# shared/cross-platform-presentation Specification Delta

## MODIFIED Requirements

### Requirement: Finance UI state and semantic IDs are shared across platforms

Cash Ledger 的 Web 与 Native 实现 MUST 共享 Finance 组件语义、文案、状态名称和稳定测试 ID；DOM 与 Compose 控件可以不同，但 Button、Input、Select、Table、Filter、Amount 的业务结果和确认时机 MUST 一致。

#### Scenario: Parity test addresses the filter

- **WHEN** 跨端测试打开 Cash Ledger 筛选
- **THEN** Web 与 Native MUST 通过同一个语义 ID 找到筛选入口
- **AND** 选择、清除、加载和错误结果 MUST 保持同一合同

### Requirement: Cash Ledger responsive invariants are explicit

Cash Ledger MUST 在 Web compact/regular/wide 和 Native phone/regular/wide 中登记相同的信息区域顺序、状态语义和操作关系；允许的平台控件差异 MUST 回写到设计或任务记录。

#### Scenario: Wide layout preserves table workflow

- **WHEN** 窗口宽度达到 `1024` px 或以上
- **THEN** 导航、筛选、表格、详情和操作区域 MUST 保持登记的顺序与关系
- **AND** 不得因平台控件差异改变用户完成查看或筛选任务的路径

### Requirement: Confirmed Finance pages preserve shared workflow and responsive structure

F-01、F-02、F-04 至 F-10 的 Compose Web 和 Native 页面 MUST 保持既有业务操作、状态、用户文案及稳定语义 ID，并采用已确认 Pen 稿登记的区域顺序；compact MUST 为无页面级横向滚动的单列，wide MUST 保持 Web 大屏的筛选、列表、详情与操作关系。

#### Scenario: User completes a page task on different window sizes

- **WHEN** 使用者在 compact、regular 或 wide 窗口打开任一已确认页面
- **THEN** 加载、空、错误和成功状态 MUST 具有相同的业务结果与恢复入口
- **AND** 主操作、筛选和详情 MUST 保持相同的可达性与确认语义
