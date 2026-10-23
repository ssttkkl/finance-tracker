## ADDED Requirements

### Requirement: 月份吸顶摘要不得遮挡账本滚动条

Compose 收支账本的月份吸顶摘要显示时，垂直滚动条轨道和滑块 MUST 绘制在摘要表面上方，且 MUST 保持可见和可交互。`COMPACT`、`REGULAR` 和 `WIDE` 窗口的可见轨道 MUST 与滑块对齐，并贴近列表右缘。滚动定位、触控热区及摘要内容 MUST 保持现有合同。

#### Scenario: 滚动时月份摘要与滚动条重叠

- **GIVEN** Compose 收支账本滚动条可见且月份摘要已固定在列表顶部
- **WHEN** 月份摘要覆盖列表右侧的滚动条区域
- **THEN** 滚动条轨道 MUST 显示在月份摘要表面上方
- **AND** 使用者 MUST 能在滚动条区域继续拖动定位
- **AND** 三类 Compose 窗口的轨道 MUST 与滑块处于同一水平位置并贴近列表右缘
