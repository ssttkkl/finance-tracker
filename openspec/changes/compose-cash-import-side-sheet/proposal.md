## Why

账单导入在 `REGULAR` / `WIDE` 窗口仍以居中、限宽的对话框呈现，空间利用不足，也与流水详情使用的侧边任务面板不一致。将导入改为右侧侧边面板，让账本背景保留为上下文，同时为多步骤导入提供更宽的工作区。

## What Changes

- 按横向 `WindowSizeClass` 呈现账单导入：`REGULAR` 为贴右全高、占当前视口宽度 90% 的模态侧边面板；`WIDE` 占 70%。
- `COMPACT` 继续使用全屏任务页。
- 保留现有遮罩、关闭和 Escape 行为、左上关闭入口、固定底部步骤操作、四步流程与未确认离开确认；不改变导入业务行为。
- 为三类窗口更新共享 Compose 呈现合同、浏览器尺寸断言和功能地图记录。

## Capabilities

### New Capabilities

无。

### Modified Capabilities

- `cash-ledger/cash-ledger-browser`：将导入页面的 `REGULAR` / `WIDE` 呈现从居中模态浮层改为右侧全高的模态侧边面板，分别占视口宽度 90% / 70%；窗口等级按横向 `WindowSizeClass` 判定；保持 `COMPACT` 全屏及现有导入语义。

## Impact

- 共享 Compose：`SecondaryPageContainer.kt`；影响 Compose Web、Android 与 iOS 的 `REGULAR` / `WIDE` 窗口。
- 浏览器 QA：`compose/qa/web/tests/compose-pages.e2e.ts` 中的 F-05 尺寸与定位断言、截图证据。
- 文档：`docs/feature-map.md` 和本变更 OpenSpec artifacts。
- 不改变账单解析、账户映射、关系审查、导入会话、API、数据模型或持久化；恢复方式是还原侧边面板容器及对应 UI 合同。

## 后续现金导入重设计的同步边界

`cash-import-lifecycle-redesign` 已声明本变更部分导入条款的后续取代，具体范围见 [取代清单](../cash-import-lifecycle-redesign/design.md#active-changes-取代与同步顺序)。新变更尚未实施时不改变当前行为；新合同生效后，本变更不得重新同步已经取代的旧流程或规则，须先清理或合并相应 delta。原未完成验证及用户演示仍按原记录保留，不因此自动完成。
