# shared/cross-platform-presentation Specification Delta

## MODIFIED Requirements

### Requirement: Shared presentation semantics are addressable

跨端页面 MUST 为每个核心区域、操作和状态提供稳定的语义标识；Web 使用 `data-testid` 或等价语义属性，Native 使用 `testID` 或等价语义入口。标识 MUST 不依赖中文文案、数组位置或具体渲染控件。Cash Ledger 的 Finance 组件及其 Web/Native 实现 MUST 共享文案、状态、语义 ID、业务结果和确认时机；DOM 与 Compose 控件可以不同。

#### Scenario: A parity journey addresses a primary action

- **WHEN** 自动化测试需要打开收支账本的新增流水操作
- **THEN** Web 与 Native MUST 都能通过同一个语义标识找到该操作
- **AND** 修改按钮文案或把按钮替换为 Native 控件不得改变该标识

#### Scenario: 跨端测试定位收支账本筛选

- **WHEN** 跨端测试打开收支账本筛选
- **THEN** Web 与 Native MUST 通过同一个语义 ID 找到筛选入口
- **AND** 选择、清除、加载和错误结果 MUST 保持同一合同

### Requirement: Responsive classes map logical window sizes across platforms

系统 MUST 按逻辑窗口宽度而不是设备型号划分布局等级：小于 `600` 的宽度为 `compact`，`600` 至小于 `1024` 为 `regular`，`1024` 及以上为 `wide`。Web `compact` MUST 对应 Native phone，Web `regular` MUST 对应 Native tablet portrait 或窄窗口，Web `wide` MUST 对应 Native tablet landscape 或大窗口。收支账本 MUST 在 Web compact/regular/wide 与 Native phone/regular/wide 中保持相同的信息区域顺序、状态语义和操作关系。

#### Scenario: Android runs on multiple device forms

- **WHEN** Android 在手机、折叠屏、平板或自由窗口中改变可用逻辑宽度
- **THEN** Native MUST 根据当前窗口宽度切换布局等级，不得读取或分支具体设备型号
- **AND** 内容 MUST 保持单列、双列或大屏关系与对应 Web 等级一致，不能出现页面级横向滚动

#### Scenario: iPad uses a large window

- **WHEN** iPad 以 portrait、landscape 或分屏窗口显示第一阶段页面
- **THEN** Native MUST 根据当前窗口宽度使用 `regular` 或 `wide` presentation
- **AND** `wide` 页面 MUST 保留 Web 的导航层级、信息块顺序、筛选/详情/操作区域关系和相近密度

#### Scenario: 收支账本在不同窗口等级保持区域关系

- **WHEN** 使用者在 compact、regular 或 wide 窗口打开收支账本
- **THEN** 筛选、记录、详情和操作 MUST 按对应 Web 布局等级保持已登记的顺序与关系
- **AND** compact MUST 使用无页面级横向滚动的单列或可折叠区域，wide MUST 保留导航、筛选、表格和详情/操作区域关系

## ADDED Requirements

### Requirement: Confirmed Finance pages preserve shared workflow and responsive structure

F-01、F-02、F-04 至 F-10 的 Compose Web 和 Native 页面 MUST 保持既有业务操作、状态、用户文案及稳定语义 ID，并采用已确认 Pen 稿登记的区域顺序；compact MUST 为无页面级横向滚动的单列，wide MUST 保持 Web 大屏的筛选、列表、详情与操作关系。

#### Scenario: User completes a page task on different window sizes

- **WHEN** 使用者在 compact、regular 或 wide 窗口打开任一已确认页面
- **THEN** 加载、空、错误和成功状态 MUST 具有相同的业务结果与恢复入口
- **AND** 主操作、筛选和详情 MUST 保持相同的可达性与确认语义
