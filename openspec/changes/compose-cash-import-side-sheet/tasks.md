## 1. 思考

- [x] 1.1 阅读项目上下文、账单导入与跨端 presentation 主规格、术语词表、共享容器、F-05 浏览器用例及功能地图。
- [x] 1.2 通过 `grill-me` / `grilling` 确认账单导入按横向 `WindowSizeClass` 呈现：`REGULAR` 占视口宽度 90%，`WIDE` 占 70%；`COMPACT` 保持全屏。按侧边面板的现有应用模式采用贴右全高呈现。
- [x] 1.3 完成 Cross-platform Impact Check：Compose Web、Android/iOS 均使用同一共享容器；只改变 `REGULAR` / `WIDE` 视觉承载，导入流程、路由和操作语义不变。

## 2. 计划

- [x] 2.1 创建 proposal、`cash-ledger/cash-ledger-browser` 完整 MODIFIED delta、design 与 tasks；明确 Regular 90%、Wide 70%、贴右全高、Compact 不变及回滚方式。
- [x] 2.2 记录信息架构「标题—步骤内容—固定页脚」、无删除业务内容、FinanceTheme 中性表面与既有侧边面板形态；不创建新颜色或装饰层级。
- [x] 2.3 更新 Cross-platform Impact Check：Web 需三尺寸真实浏览器检查；Native 使用共享 `commonMain`，复核 Native 影响并记录本轮验证边界；旧 React Web 不修改。

## 3. 任务拆分与一致性

- [x] 3.1 先更新 F-05 浏览器断言，覆盖 Regular 90%、Wide 70%、贴右位置、全高以及 Compact 全屏保持；初始实现的 70% 几何断言通过，比例细化的失败先行证据见第 9 阶段。
- [x] 3.2 核对 proposal、完整 MODIFIED requirement、design、测试断言与功能地图使用同一呈现合同；移除继承 requirement 中未实现且与本 change 无关的关闭后焦点恢复要求。

## 4. 构建

- [x] 4.1 调整共享 `SecondaryPageContainer`：Regular 使用右侧 90%、Wide 使用右侧 70% 视口宽的全高模态侧边面板；正文滚动、标题和固定页脚分区清楚，安全区与现有关闭语义保留。
- [x] 4.2 保持 Compact 全屏、遮罩/背景阻断、Escape/系统返回、关闭确认、四步流程及底部操作行为不变。
- [x] 4.3 更新 `docs/feature-map.md` 的 F-05 呈现描述和实现证据。

## 5. 审查

- [x] 5.1 对照 F-05 requirement 审查模态行为、窗口尺寸、导入状态、旧内容和回滚边界；本次只调整共享容器与 Web 断言，没有改导入业务。
- [x] 5.2 完成 Hallmark `audit`：审查 Compose 实现及 Compact/Regular/Wide 实际截图，0 critical、0 major、0 minor；右侧不对称布局沿用页面任务结构、FinanceTheme 表面和 M3 控件，没有新增装饰、卡片嵌套或无用说明。
- [x] 5.3 独立复核最终 diff、文案必要性、组件层级、FinanceTheme 表面、安全区、固定操作和按横向窗口等级计算的 90%/70% 宽度；Compact 路径未变。

## 6. 测试与 QA

- [x] 6.1 用 Playwright Chromium 生产预览覆盖 `COMPACT 390×844`、`REGULAR 768×1024`、`WIDE 1440×900`，并验证 600 dp 与 1024 dp 等级边界：导入打开与关闭、完整导入步骤、账户映射、忙碌时关闭/放弃，以及 Compact 完整导入与账本返回。
- [x] 6.2 保存实际浏览器截图并审查：[`import-390x844.png`](screenshots/import-390x844.png)、[`import-600x900.png`](screenshots/import-600x900.png)、[`import-768x1024.png`](screenshots/import-768x1024.png)、[`import-1440x900.png`](screenshots/import-1440x900.png)、[`dark-regular-768x1024.png`](screenshots/dark-regular-768x1024.png)、[`dark-wide-1440x900.png`](screenshots/dark-wide-1440x900.png)。浏览器 URL 为 `http://127.0.0.1:5237/w/workspace-1/cash-import`；检查了 Regular 90%、Wide 70%、贴右位置、全高、遮罩、关闭入口、步骤操作固定在底部、滚动宽度、600 dp 与 1024 dp 等级边界和深色主题。所有通过用例的 `afterEach` 浏览器诊断断言均通过，Console error、失败请求与静态 HTTP 错误为 0。
- [x] 6.3 初始 70% 版本的聚合命令 `CI=1 FT_COMPOSE_PREVIEW_PORT=5237 npm run test:e2e -- --grep 'F-05 账单导入从账本以响应式|F-05 cash-import uses the Material 3 side-sheet variant|F-05 Regular/Wide 侧板可以通过 Escape 关闭|F-05 Regular/Wide 导入侧板可完成各步骤并提交|F-05 导入请求处理中关闭会先提示等待|F-05 账户映射菜单和选中账户在三种窗口保持一致|F-05 导入可完成选择、扫描、预览、确认和结果摘要|F-05 未确认离开导入页不触发账本返回刷新'`（8 passed）；`CI=1 FT_COMPOSE_PREVIEW_PORT=5237 npm run test:e2e -- --grep 'F-05 账单导入文件选择页适配夜间主题'`（1 passed）；`openspec validate --all --strict`（34 passed）、`openspec doctor`、`git diff --check` 均通过。另运行 `F-05 浅色与夜间主题下导入全流程覆盖三类窗口` 未通过：旧测试在 Compact 账户编辑取消后，Compose Web 语义镜像只暴露账户类型菜单项，原坐标点击误触菜单，预览 API 未调用；测试 ID 也未镜像出可见的「生成预览」按钮。测试试探已撤回；本次未改 Compact 路径。独立 Compact 完整导入、Regular/Wide 完整导入、关闭及深色主题测试均通过。此处为首次 70% 版本的历史证据，后续宽度细化后的复测见第 9 阶段。原执行时间：2026-10-19 17:38 Asia/Shanghai；代码 HEAD `8987ff6ae1472012d46741216603a21589acc317`，比较基线 `main` / merge-base `3b3bbe25b4dbd6007be024fdb7b35473a0ea229f`。
- [x] 6.4 影响范围为 Compose `commonMain`，Android/iOS 与 Compose Web 共用 Regular/Wide 面板布局；按当前验证范围仅运行 Web 浏览器 QA，未运行 Native。补测条件：Android/iOS 平板或可调整窗口分别打开导入，确认 Regular 90%、Wide 70% 贴右全高、系统安全区、模态遮罩/返回、步骤页脚及滚动行为。

## 7. 发布准备

- [x] 7.1 不涉及数据库或 API 发布；回滚范围为 `SecondaryPageContainer.kt`、对应 F-05 浏览器断言、功能地图和本 change artifacts。保留的 QA 限制是既有 Compact 浅/深色综合测试在关闭账户编辑后的语义镜像失配，不影响本次变更覆盖的 Regular/Wide 布局；Native 仍待有设备时按 6.4 补测。

## 8. 反思

- [x] 8.1 复用流水详情的右侧任务面板模式，并将 Regular 90%、Wide 70% 的贴右全高合同写入规格和几何断言；F-05 新增 Regular/Wide 完整步骤与提交用例，后续可作为同类模态侧面板的回归基准。

## 9. 横向窗口比例细化

- [x] 9.1 用户确认 `COMPACT` 保持全屏，横向 `REGULAR` 使用 90% 宽、横向 `WIDE` 使用 70% 宽；窗口等级边界来自共享 `WindowSizeClass`（600 dp、1024 dp）。
- [x] 9.2 同步更新 proposal、完整 MODIFIED delta、design、tasks 与 F-05 功能地图；将浏览器几何断言更新为宽度比例与右侧对齐计算。
- [x] 9.3 测试先行：比例细化前运行 `CI=1 FT_COMPOSE_PREVIEW_PORT=5237 npm run test:e2e -- --grep 'F-05 cash-import uses the Material 3 side-sheet variant for each horizontal window class'`；在 `REGULAR 768 px` 观察到失败（期望 90%，旧实现 70%），确认回归断言有效。
- [x] 9.4 按窗口等级在共享 `SecondaryPageContainer` 中设置 90% / 70% 宽度；Compact 路径不变。
- [x] 9.5 在 Playwright Chromium 生产预览 `http://127.0.0.1:5237/w/workspace-1/cash-import` 运行 `CI=1 FT_COMPOSE_PREVIEW_PORT=5237 npm run test:e2e -- --grep 'F-05 账单导入从账本以响应式|F-05 cash-import uses the Material 3 side-sheet variant|F-05 Regular/Wide 侧板可以通过 Escape 关闭|F-05 Regular/Wide 导入侧板可完成各步骤并提交|F-05 导入请求处理中关闭会先提示等待|F-05 账户映射菜单和选中账户在三种窗口保持一致|F-05 导入可完成选择、扫描、预览、确认和结果摘要|F-05 未确认离开导入页不触发账本返回刷新|F-05 账单导入文件选择页适配夜间主题'`（9 passed，1.4 分钟）；另运行同一水平等级几何用例（1 passed，1.6 分钟）。覆盖 Compact `390×844`、Regular `600×900` / `768×1024` / `1023×900`、Wide `1024×900` / `1440×900`，并验证打开/关闭、完整导入提交、未确认离开、Escape、账户映射及夜间主题。测试断言 Regular 90%、Wide 70%、全高和右侧对齐；通过用例的浏览器诊断检查无 Console error、失败请求或静态 HTTP 错误。已刷新并目视审查本 change 下的 4 张亮色视口图及 2 张深色图，路径见 6.2。实现前同一几何用例在 Regular 768 px 以 70% 实际宽度失败，实施后通过。
- [x] 9.6 按 Hallmark `audit` 审查最终 Compose 面板、FinanceTheme 表面、层级、600 px 窄 Regular 与 1440 px Wide 截图及遮罩/固定页脚：0 critical、0 major、0 minor，无需修复；独立 diff 复核确认 Compact 路径和导入业务行为未变。`openspec validate --all --strict`（34 passed）、`openspec doctor`、`git diff --check` 均通过。Web 覆盖按用户要求完成；Native 未运行。执行时间：2026-10-19 17:51 CST；代码 HEAD `8987ff6ae1472012d46741216603a21589acc317`，比较基线 `main` / merge-base `3b3bbe25b4dbd6007be024fdb7b35473a0ea229f`。
