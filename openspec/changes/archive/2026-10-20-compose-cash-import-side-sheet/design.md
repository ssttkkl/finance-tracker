## Context

参见 [proposal.md](proposal.md) 的范围与动机。现有 `SecondaryPageContainer` 已统一承载账单导入的标题、滚动正文和固定页脚；`COMPACT` 使用全屏任务，较宽窗口使用居中基础对话框。流水详情已有 Regular/Wide 全高右侧面板，可作为同一应用内的呈现参照。窗口等级使用横向 `WindowSizeClass`，边界为 600 dp 与 1024 dp。

## Goals / Non-Goals

**Goals：**

- 让账单导入按横向 `WindowSizeClass` 使用右侧全高模态任务面板：`REGULAR` 宽度为窗口的 90%，`WIDE` 为 70%。
- 保留 `COMPACT` 全屏、模态遮罩、关闭行为、四步流程和固定页脚操作。
- 使用 FinanceTheme 既有表面与形状，不新增页面色值或导入控件。

**Non-Goals：**

- 不改变导入步骤、账户映射、关系决定、提交时机、离开确认或路由。
- 不改变列表、流水详情和其他页面级容器。
- 不引入平台专用分支；Web、Android 与 iOS 继续共用 `commonMain` 呈现。

## Decisions

### 共享模态容器改为右侧全高面板

`SecondaryPageContainer` 继续使用现有 Compose `Dialog` 提供模态隔离、遮罩与关闭请求；`REGULAR` / `WIDE` 的内容面板贴齐视口右侧并使用全部可用高度，横向宽度分别为 90% / 70%。`WindowSizeClass` 使用逻辑窗口宽度判定，600 dp 至小于 1024 dp 为 `REGULAR`，1024 dp 起为 `WIDE`。`COMPACT` 路径保持全屏。

面板正文占用标题与页脚之间的剩余高度并独立纵向滚动；步骤操作固定在底部。关闭按钮留在标题栏左侧。面板继续使用既有语义标题和测试标识。

**替代方案：** 保留居中且限宽的对话框会延续当前紧凑卡片观感，无法提供用户要求的侧边任务面板；使用导航抽屉会把单项导入任务表达成导航操作，因此不采用。

### 沿用既有主题表面与关闭语义

面板使用 FinanceTheme 既有中性色表面与 Material 3 形状，不引入新颜色、装饰卡片或额外层级。遮罩期间账本和导航不可操作；Escape、系统返回、关闭按钮、未确认离开确认和返回账本行为沿用当前导入合同，不增加焦点请求或恢复逻辑。

### Cross-platform Impact Check

- **Web：受影响。** Compose Web 使用同一容器，需验证 Compact、Regular、Wide 的位置、比例、全高、滚动和关闭流程。
- **Native：受影响。** Android 与 iOS 共享 `commonMain`；`REGULAR` / `WIDE` 平板或窗口下变为右侧面板，需保持安全区、系统返回与模态阻断语义。
- **共享层：受影响。** 仅 `SecondaryPageContainer` 的宽屏呈现和 F-05 浏览器尺寸断言变化；导入 ViewModel、API、会话状态不变。
- **旧 React Web：不受影响。** 仅供历史参考，不修改 `web/src`。

### Hallmark 方向与自查

此 B 级响应式呈现调整沿用项目已有 Cobalt FinanceTheme、Material 3 表面和应用内全高侧边面板模式。信息架构保持「标题—当前步骤—固定操作」；不新增说明区、装饰卡片、颜色或浮层层级。最终页面按 Hallmark `audit` 检查三尺寸边界、层级、可读性和遮罩状态。

## Risks / Trade-offs

- **Regular 最窄宽度下的 90% 面板会遮住大部分账本上下文：** 这是用户指定的宽度比例；维持现有窄屏关系卡片与字段换行行为，并在 600 dp 边界检查横向溢出和操作可达性。
- **全高面板可能与系统状态栏或安全区冲突：** 面板内容继续应用 `WindowInsets.safeDrawing`，浏览器与 Native 共享同一逻辑窗口宽度合同。
- **改变通用容器会影响未来调用者：** 当前 `SecondaryPageContainer` 只有账单导入一个调用点；若后续有不同呈现需求，应以明确合同扩展调用方式，不复制容器尺寸逻辑。

## Migration Plan

无数据或路由迁移。回滚时还原 `SecondaryPageContainer` 的 Regular/Wide 对话框布局、F-05 尺寸断言和对应功能地图描述。
