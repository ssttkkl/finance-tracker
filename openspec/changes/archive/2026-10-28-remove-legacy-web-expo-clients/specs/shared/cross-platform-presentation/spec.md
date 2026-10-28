## REMOVED Requirements

### Requirement: Legacy React UI is migration reference only

**Reason**: The React Web implementation has been removed, so `web/src` can no longer serve as a repository migration reference.
**Migration**: Use `compose/webApp` and `compose/qa/web/` for current Web implementation and browser QA. Historical React behavior remains available only through Git history and archived change evidence.

## MODIFIED Requirements

### Requirement: First-phase Native screens preserve Web state and action contracts

第一阶段 Native MUST 覆盖登录、工作区选择、收支账本浏览、手工记账、凭证查看、账单选择/扫描、账户映射、流水核对、关系审查和导入完成任务。每个任务 MUST 支持与 Compose Web 对应的正常、加载、空、错误、禁用和成功状态；写入仍 MUST 由服务端确认，金额和数量 MUST 以字符串语义传输。

#### Scenario: A viewer opens a write action

- **WHEN** `viewer` 在 Web Compact 或 Native 打开记账或导入入口
- **THEN** 两端 MUST 以相同的只读/无权限语义禁用或拦截写操作
- **AND** 不得创建本地成功状态或提交不完整数据

#### Scenario: Import reaches relation review

- **WHEN** 用户完成账单扫描和账户映射并进入关系审查
- **THEN** Web 与 Native MUST 展示相同的关系状态、候选含义、拒绝/撤销动作和确认导入结果
- **AND** 取消文件选择、密码错误、请求错误和导入成功 MUST 分别落入可识别状态，不得显示空白页面

### Requirement: Platform differences and unsupported surfaces are explicit

平台差异 MUST 登记在共享合同或对应 OpenSpec 文档中，并同时声明两端必须保持的业务不变量。未提供或尚未实现的 target surface MUST 被明确标记为缺口，不得渲染成看似可用但无法完成任务的伪页面。

#### Scenario: Date and file controls use native surfaces

- **WHEN** Web 使用 popover、HTML file input 或表格，而 Native 使用 sheet、系统文件选择器或列表
- **THEN** 两端 MUST 保持相同的可选值、初始值、标签、确认语义和错误处理
- **AND** 这种控件表面差异 MUST 记录为允许的平台差异

#### Scenario: A user opens an unsupported Native surface

- **WHEN** Native 导航指向当前 target 尚未提供的分类、投资或工作区管理能力
- **THEN** 客户端 MUST 明确显示暂不可用或保持未提供入口
- **AND** 不得伪造成功、写入不完整数据或静默跳到语义不同的页面
