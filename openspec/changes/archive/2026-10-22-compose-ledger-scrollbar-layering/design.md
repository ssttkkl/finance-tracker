## Change class

C：局部 UI 缺陷修复。根因明确：滚动条内部先居中绘制轨道，再偏移滑块，导致紧凑窗口的轨道与滑块相差约 18 dp；`CashLedgerScreen` 随后绘制月份吸顶层，又会覆盖轨道。

## Information architecture and removals

账本区域顺序与可见内容不变。保留列表、滚动条、月份汇总和吸顶标题；本次不删除任何账本信息或操作。

## Implementation

将共享 Compose 快速滚动条的水平偏移应用到轨道绘制修饰符之外，使轨道跟随滑块移动；三个窗口等级的轨道右缘由此与列表右缘对齐，并移除 Regular/Wide 原有的 16 dp 内缩。再提高账本滚动条的绘制层级，使轨道和滑块位于月份吸顶摘要上方。40 dp 命中区域、定位状态、键盘语义和轨道样式不变，悬浮操作按钮继续按当前根层级绘制。

## Cross-platform impact

- Shared Compose: 受影响，账本吸顶层与滚动条顺序对所有共享 Compose 尺寸统一生效。
- Native Android/iOS: 受影响，共享 Compose 页面使用相同绘制关系；当前 QA 以 Compose Web 预览覆盖。
- Legacy React Web / Expo Native: 不受影响，这些页面不是该修复的实现目标。

## UI review

这是现有账本层级的缺陷修复，不改变页面结构、信息架构或视觉系统，不需要原型。最终页面将按 `docs/shared/ui-design-rules.md` 执行滚动交互和三个窗口等级的审查，并记录 Hallmark `audit` 可用性及结果。
