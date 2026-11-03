## 1. 思考

- [x] 1.1 读取账本主规格、UI 规范与相关代码/测试，审计列表、空态、加载态、详情原文与金额格式。
- [x] 1.2 用真实 Chrome 在 390/1280/1440 渲染账本与详情，记录三套列模型、空态文案、原文断词与金额精度差异。

## 2. 计划

- [x] 2.1 形成 proposal/design，明确非目标与验收。
- [x] 2.2 用户确认：账单原文保持内容、只修断词；金额统一去尾零。

## 3. 任务拆分

- [x] 3.1 列模型（表头/行/骨架）统一，并同步主规格。
- [x] 3.2 空态拆分与动作，更新 e2e 夹具。
- [x] 3.3 移动端状态标签位置与桌面一致。
- [x] 3.4 金额统一去尾零并补单测。
- [x] 3.5 账单原文标签布局修正，补规格与 UI 规范例外。

## 4. 构建

- [x] 4.1 先补失败回归（空态、金额、原文断词、列对齐），再最小实现。

  金额去尾零后 `DemoCashCreationTest` 的转账断言先失败，按新口径更新；账本行、详情、空态 e2e 断言同步改为新行为（“还没有流水”/动作 ID）。

- [x] 4.2 按任务小步实施并通过相称编译。

  列权重单一来源 `CashLedgerColumn`、骨架删除幽灵 8% 列；空态拆分与 `openCreate`/`openImport` 复用；移动端状态标签移到核对原因之后；`cashAmountWithCurrencyLabel` 去尾零；`DetailValue` 保持 72dp，新增仅用于账单原文的 `StatementFieldValue`。`:webApp:compileKotlinWasmJs` BUILD SUCCESSFUL。

## 5. 审查

- [x] 5.1 范围 diff 复核与最终实现核对。

  改动限于账本呈现（列模型、空态、标签位置、金额格式、原文标签布局）与配套文案/测试；未改接口、数据模型或删除流程。

- [x] 5.2 调用 codebase-degradation-review 调度 UI 复用审查。

  契约面未变，不调度契约设计评审；UI 复用审查由独立只读上下文执行，无阻断，发现及处置：
  - F1（大）空态判定用 `filters != 默认`，零元默认隐藏与关闭开关两个方向都会误判：已抽出 `CashFilters.hasActiveFilters`（忽略默认 `hideZeroAmount`）并补单测；仅零元流水被隐藏的场景缺少“未筛选是否有记录”信号，记录为 known limitation，补信号属接口变更。
  - F2（中）`DetailValue` 全局改 `widthIn(72–180)` 影响关联流水与拆出对比：已回退 72dp，仅账单原文使用新的 `StatementFieldValue`。
  - F3（中）列权重在表头/行/骨架三处字面量：已抽页面局部 `CashLedgerColumn` 枚举，三处共用。
  - F4（中低）空态 CTA 与 FAB 回调重复、新按钮无语义 ID：已抽 `openCreate`/`openImport` 共用，新增 `ledger.empty-create|import|clear`，e2e 改用 ID。
  - F5（低中）金额符号拼接重复且二次解析：已改为一次取 sign、去掉 `!!`；跨函数统一留待后续。
  - F6（低）既有“清除”与新“清除筛选”并存、键位置在文案组内：保留显式措辞，记录术语统一待办。
  - F7（低，历史）`auxiliaryColumn` 死参数、compact/desktop 交易信息双实现、monospace 启发式：记录，不在本次扩大范围。

## 6. 测试与 QA

- [x] 6.1 共享单测与 wasm 构建。

  `./gradlew :shared:wasmJsTest` BUILD SUCCESSFUL（含金额去尾零与 `hasActiveFilters` 新单测）；`:webApp:compileKotlinWasmJs` BUILD SUCCESSFUL。既有 `compose-cash-current-list.e2e.ts` 的三个键盘用例在改动前即失败（stash 后复跑仍失败），与本 change 无关，记录为既有缺陷。

- [x] 6.2 真实 Chrome 冒烟（390/768/1440）：列顺序、空态、详情原文、金额。

  `compose-pages.e2e.ts` 30 passed（含列顺序与两种空态）；`compose-cash-detail.e2e.ts` 9、`compose-cash-detail-redesign.e2e.ts` 8、`compose-cash-month-summary.e2e.ts` 3、`compose-cash-ui-audit.e2e.ts` 5、`compose-cash-relations.e2e.ts` 14 passed。演示重建后实拍 390/768/1280：7 列对齐、金额去尾零、移动端标签位置、原文标签不再断词、两种空态动作可见。768 下时间列仍会截断，属既有列比例问题，记录待后续。
- [x] 6.3 独立设计复核与 Hallmark 自检。

  第一轮 `0 critical / 3 major / 3 minor`；聚焦复核确认 M1、M3、m1 解决，m2 的值列对齐解决但 390 值断行升为 major。处置：
  - M1 768 时间列截断：REGULAR 改为两行「10月29日 / 13:30」，实测无省略号、分钟完整。
  - M2 FAB 遮金额：用户确认 FAB 叠在滚动列表上属正常交互，列表已有 104dp 底部避让，不修。
  - M3 筛选空态唯一动作最弱：改为 FilledTonalButton，实测已是实体容器。
  - m1 汇总与金额右缘差 8px：汇总子列固定宽度并加 8dp 右内边距，实测 726/726、1238/1238。
  - m2 账单原文值列漂移：标签列固定宽度；390 用 128dp、≥768 用 160dp，ISO 时间戳与长值单行。
  - m3 空态卡片与 FAB 双入口：按用户口径接受，FAB 为全局入口。
  Hallmark 自检按规范逐项核对列语义、空态、金额与对比度；未运行 CLI（沿用既有做法）。
- [x] 6.4 OpenSpec strict、术语/链接与 git diff --check。

  `openspec validate --all --strict` 25 passed；`git diff --check` 干净。

## 7. 发布

- [x] 7.1 按最终实现更新功能地图与相关文档。

  页面结构与入口未变，功能地图无需修改；UI 规范补充“同一视图金额精度一致”“FAB 叠层的底部避让”“原始账单次级区例外”；主规格 delta 覆盖列合同、空态与原文范围。
- [x] 7.2 用户决策反思，提交并推送。

  用户决策：账单原文保持内容、只修断词；金额统一去尾零；FAB 叠层遮挡不算缺陷、只要求滚到底避让。前两项已落到规格/实现与单测；后两项按通用规则回写 UI 规范（金额精度一致、FAB 叠层避让）。提交在 `converge-cash-ledger-ui` 分支，未推送。
