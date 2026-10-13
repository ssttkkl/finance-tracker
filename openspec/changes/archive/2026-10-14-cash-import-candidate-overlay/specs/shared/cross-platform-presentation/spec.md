## MODIFIED Requirements

### Requirement: Compose cash import follows the confirmed secondary-page design

Compose 账单导入页 MUST 遵循已确认的 Pen 与独立原型所定义的信息层级和响应式布局。`REGULAR` 和 `WIDE` MUST 先显示页面标题，再显示右侧关闭按钮；`COMPACT` MUST 先显示左侧返回按钮，再显示标题。二级页面标题栏高度 MUST 按 `COMPACT` / `REGULAR` / `WIDE` 分别为 72 dp、78 dp 和 84 dp。`REGULAR` 与 `WIDE` 模态框圆角 MUST 分别为 22 dp 和 24 dp，且不得添加额外阴影。页面打开时，关闭或返回按钮 MUST 显示原型中的蓝色焦点样式。

四步指示器 MUST 使用等宽步骤、编号或完成状态圆形标记及其下方标签，并独立于受 720 dp 宽度限制的阶段内容，`REGULAR` / `WIDE` 两侧内边距 MUST 为 36 dp，`COMPACT` MUST 为 12 dp。文件选择、账户映射和流水核对内容 MUST 居中且最大宽度为 720 dp；关系核对 MUST 使用可用容器宽度，并以来源到目标的卡片或行展示每项关系。页面 MUST 保留既有扫描、账户映射、流水核对、分配、关系类型、候选选择、拒绝与恢复、错误处理及确认语义。

完整账单导入路由 MUST 始终使用原型中的浅色 Finance 调色板，不受系统外观影响，且包含导航栏、顶栏、路由背景和导入界面。`REGULAR` / `WIDE` 模态框背景遮罩 MUST 使用现有深色遮罩 token 的 38% 透明度；模态框 MUST 阻止背景交互、保持模态键盘焦点并支持 Escape 或返回操作关闭。所有共享组件颜色 MUST 从当前 Compose 主题作用域解析。使用者离开账单导入页后，应用 MUST 恢复已配置的系统外观。

关系筛选器 MUST 显示全部、待确认和已匹配的数量。关系卡片 MUST 以原型中的紧凑层级展示来源与目标流水，不得常驻展示账户名称或重复的字段标题；关系时间 MUST 使用本地时间的 `MM 月 dd 日 · HH:mm` 格式。待确认的目标流水 MUST 使用一个显示“选择流水”的候选下拉框，不得再添加说明文案或独立的可见选择操作。候选选项 MUST 锚定在选择框附近以浮层展示，打开或关闭时 MUST NOT 改变关系卡片及列表的尺寸或位置。选择候选项 MUST 关闭浮层并更新目标流水与关系数量；按 Escape 或点击浮层外部 MUST 关闭浮层且保留当前选择。待确认状态 MUST 使用原型中的警告色标签。`COMPACT` 关系卡片 MUST 将来源和目标并列展示，并把状态放在下方；`REGULAR` / `WIDE` MUST 将关系状态放在关系行末尾。较少使用的关系类型以及拒绝/恢复操作 MAY 放入可通过键盘操作的辅助操作入口，并以状态标签呈现。

二级页面页脚 MUST 只在 `COMPACT` 显示分隔线；`REGULAR` / `WIDE` 不显示分隔线。`COMPACT` 页脚说明 MUST 显示在操作行上方，顶部与底部内边距分别为 8 dp 和 16 dp；`REGULAR` / `WIDE` 页脚内容的顶部与底部内边距 MUST 分别为 16 dp 和 24 dp。映射、核对、配对阶段的“上一步” MUST 使用描边次按钮；`REGULAR` / `WIDE` 的操作按钮 MUST 靠右对齐，`COMPACT` 的“上一步”和主操作 MUST 分别靠左和靠右对齐。`COMPACT` 文件选择阶段 MUST 将放弃提示显示在下一步操作上方。界面展示的文件格式 MUST 与原型的 `CSV · XLSX · PDF` 文案一致，同时保留现有 XLS 导入支持。

#### Scenario: 在 Wide 尺寸查看账单导入

- **WHEN** 使用者在 `WIDE` 窗口打开账单导入页
- **THEN** 页面 MUST 显示左侧标题、右侧关闭按钮和带有步骤标签的四步指示器
- **AND** 系统外观为深色时，导航栏、顶栏和导入界面 MUST 继续使用原型的浅色调色板
- **AND** 模态框背景 MUST 使用原型中的 38% 遮罩
- **AND** 文件选择、账户映射和流水核对内容 MUST 居中且最大宽度为 720 dp，关系核对 MUST 使用可用容器宽度
- **AND** 待确认关系 MUST 在来源到目标的行中提供一个候选选择器；其浮层 MUST 覆盖在内容之上且不撑开关系行，选中后关系类型与拒绝/恢复操作 MUST 仍然可用
- **AND** 已匹配筛选 MUST 包含自动匹配和用户确认的关系，被拒绝的关系 MUST 仍显示在全部筛选中
- **AND** 页脚 MUST 没有分隔线，底部内边距 MUST 为 24 dp
- **AND** 映射、核对和配对阶段的页脚操作按钮 MUST 靠右对齐
- **AND** “上一步” MUST 使用描边次按钮

#### Scenario: 在 Regular 尺寸查看账单导入

- **WHEN** 使用者在 `REGULAR` 窗口打开账单导入页
- **THEN** 标题栏 MUST 高 78 dp，模态框 MUST 使用 22 dp 圆角且没有额外阴影
- **AND** 页面标题 MUST 位于关闭按钮之前
- **AND** 候选选择浮层 MUST 覆盖在关系内容之上；打开时关系卡片尺寸 MUST 保持不变，选择候选后 MUST 更新关系目标
- **AND** 页脚 MUST 没有分隔线，底部内边距 MUST 为 24 dp
- **AND** 映射、核对和配对阶段的页脚操作按钮 MUST 靠右对齐
- **AND** “上一步” MUST 使用描边次按钮

#### Scenario: 在 Compact 尺寸查看账单导入

- **WHEN** 使用者在 `COMPACT` 窗口打开账单导入页
- **THEN** 页面 MUST 使用全屏界面，并先显示返回按钮再显示标题
- **AND** 文件选择阶段的放弃提示 MUST 显示在下一步操作上方
- **AND** 阶段内容和关系卡片 MUST 保持单列或适当重排，页面 MUST 没有横向溢出
- **AND** 所有导入决策和结果 MUST 与 `REGULAR` / `WIDE` 一致
- **AND** 关系卡片 MUST 横向并列显示来源与目标，且不得造成横向溢出
- **AND** 候选选择浮层 MUST 锚定在选择框附近且不改变卡片高度；选择后 MUST 更新目标和关系数量
- **AND** 在映射、核对和配对阶段，“上一步” MUST 靠左、“下一步”或主操作 MUST 靠右；“上一步” MUST 使用描边次按钮

#### Scenario: 离开账单导入后恢复系统外观

- **WHEN** 使用者从账单导入页返回其他应用路由
- **THEN** 目标路由 MUST 使用已配置的系统外观

#### Scenario: 关闭候选浮层而不改变选择

- **WHEN** 使用者按 Escape 或点击候选浮层以外的位置
- **THEN** 候选浮层 MUST 关闭
- **AND** 当前目标流水及关系数量 MUST 保持不变
