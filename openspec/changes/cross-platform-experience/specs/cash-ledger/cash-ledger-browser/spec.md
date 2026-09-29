## ADDED Requirements

### Requirement: Native cash ledger mirrors the Web presentation contract

收支账本的 Native 渲染 MUST 复用 Web Compact、Regular 或 Wide 对应的页面区域和语义标识：页面标题、工作区上下文、筛选、列表/记录区、空状态、错误状态和主要操作 MUST 保持相同顺序与业务含义。Native 可以把 Web 表格渲染为触控列表，但不得改变投影、金额、来源或证据详情语义。

#### Scenario: View the cash ledger on a phone

- **WHEN** Native 窗口宽度小于 `600`
- **THEN** 页面 MUST 以单列方式展示 Web Compact 的标题、筛选、现金流水列表和新增/导入操作
- **AND** 每条流水 MUST 可打开与 Web 证据详情等价的凭证信息

#### Scenario: View the cash ledger on a tablet

- **WHEN** Native 窗口宽度为 `600` 或以上
- **THEN** 页面 MUST 按 Web Regular 或 Wide 的结构展示导航、筛选、记录区和操作区
- **AND** 不得因为使用 Native 列表而隐藏 Web 已展示的账户、分类、金额、来源或错误反馈
