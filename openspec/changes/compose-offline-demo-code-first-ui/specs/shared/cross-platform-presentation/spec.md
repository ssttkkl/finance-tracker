## MODIFIED Requirements

### Requirement: Confirmed Finance pages preserve shared workflow and responsive structure

F-01、F-02、F-04 至 F-10 的 Compose Web 和 Native 页面 MUST 保持既有业务操作、状态、用户文案及稳定语义 ID；当前 Compose 实现 MUST 定义页面区域顺序、层级和样式。`compact` MUST 为无页面级横向滚动的单列，`wide` MUST 保持 Web 大屏的筛选、列表、详情与操作关系。页面行为和布局验收 MUST 记录在 OpenSpec 中，响应式截图 MUST 作为 QA 证据。

#### Scenario: 使用者在不同窗口尺寸完成页面任务

- **WHEN** 使用者在 `compact`、`regular` 或 `wide` 窗口打开任一 Compose 页面
- **THEN** 加载、空、错误和成功状态 MUST 具有相同的业务结果与恢复入口
- **AND** 主操作、筛选和详情 MUST 保持相同的可达性与确认语义
- **AND** 页面顺序、样式和响应式实现 MUST 与当前 Compose 代码一致

### Requirement: Compose authentication screens use the scoped Material 3 appearance

Compose 登录和注册界面 MUST 在现有 FinanceTheme 下使用 Material 3 描边输入框、主按钮、文字按钮和图标按钮，并保留 FinanceTheme 的颜色与字形。认证输入框和主按钮 MUST 局部使用 Material 3 小形状，桌面认证卡片 MUST 局部使用中等形状；不得改变 FinanceTheme 全局形状方案或认证既有字段、文案、校验、语义和提交流程。

#### Scenario: 登录与注册的空输入字段使用相同样式

- **WHEN** 使用者打开空的登录或注册表单
- **THEN** 描边输入框 MUST 在输入框内部显示标签
- **AND** 同一响应式等级下两种模式的首个输入框 MUST 从相同垂直位置开始
- **AND** 字段、按钮、形状、颜色、字形、字段顺序、标签、语义 ID 和无障碍含义 MUST 符合 Compose 实现及 OpenSpec 验收合同

#### Scenario: 输入框聚焦或已有内容

- **WHEN** 使用者聚焦输入框或输入框已有内容
- **THEN** 标签 MUST 浮到轮廓上方，轮廓和标签 MUST 反映当前焦点或校验状态
- **AND** 输入值 MUST 按既有字段类型保持可见或隐藏

#### Scenario: 显示字段帮助或校验反馈

- **WHEN** 字段需要显示帮助、校验成功或校验错误信息
- **THEN** 反馈 MUST 显示在对应描边输入框下方的说明区域
- **AND** 反馈 MUST 保留既有文案、实时区域语义、颜色含义及对应字段关系，且后续字段和提交操作在 `compact`、`regular`、`wide` 下不得重叠

#### Scenario: 认证请求忙碌或失败

- **WHEN** 认证请求正在处理或请求失败
- **THEN** 字段和操作 MUST 保留既有禁用、进度和请求错误行为
- **AND** 标签、文案、请求数据和认证结果 MUST 保持不变

### Requirement: Code-first UI changes use Compose demo review

UI changes MUST record the user task, information architecture, acceptance criteria, and redundant content to remove in the corresponding OpenSpec change. A-class or major UI changes MUST also record Hallmark's design direction and self-review before implementation. Pen files and standalone HTML prototypes MUST NOT be required deliverables; Compose implementation MUST be the source of truth for page structure and appearance, and screenshots MUST serve as QA evidence only. Every UI change MUST receive a final Hallmark audit. Major UI changes MUST be reviewed in the Compose demo after implementation and MUST NOT be marked complete until the user confirms the demonstrated result.

#### Scenario: Plan a major UI change

- **WHEN** a major UI change enters implementation planning
- **THEN** its OpenSpec design record MUST contain the user task, information architecture, content to remove, acceptance criteria, and Hallmark direction/self-review
- **AND** planning MUST NOT require a `.pen` file, Pen node mapping, or `prototype/index.html`

#### Scenario: Review a completed major UI change

- **WHEN** implementation is complete and the affected pages run in a Compose development/QA demo build
- **THEN** the change MUST be reviewed at the affected `COMPACT`, `REGULAR`, and `WIDE` layouts, with responsive screenshots and interaction evidence recorded in `tasks.md`
- **AND** the change MUST receive the final Hallmark audit and remain incomplete until the user confirms the demonstrated result

## REMOVED Requirements

### Requirement: 认证状态设计稿展示完整页面

**Reason**: The UI workflow no longer creates or maintains Pen design boards. The Compose implementation and its demo review replace those boards as the source of visual truth.

**Migration**: Validate authentication states in the shared Compose screens, use repository fixtures for loading, empty, error, and success states, and record responsive screenshots and review evidence in the active OpenSpec change.
