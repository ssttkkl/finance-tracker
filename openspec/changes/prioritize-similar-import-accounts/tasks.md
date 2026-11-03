# 导入目标账户优先展示建议账户

分级：B（共享 Compose 的局部交互增强；无接口、领域规则、币种、持久化或权限变更）。

## 1. 思考

- [x] 1.1 阅读账户准备主规格、`CashImportPreparation` 的来源条目与 `ChoicePicker`、`ImportSourceAccount`/`ImportAccountUiState` 模型、现有 e2e 账户选择用例。
- [x] 1.2 与用户确认：规则匹配 + Jaro-Winkler 独立判定、尾号不同硬排除、全部来源都计算、已确认建议置顶、不限制建议数量、分组间加分隔线且只有建议组显示灰色“建议”。
- [x] 1.3 不升级理由：不做服务端候选、不改账户模型或映射；纯展示派生，可用共享单测与真实浏览器验证。

## 2. 计划

- [x] 2.1 产出 `proposal.md`、delta 与 `design.md`。
- [x] 2.2 明确归一化、Jaro-Winkler 阈值、排序键、置顶与无建议时的回退行为。
- [x] 2.3 明确 `ChoicePicker` 的分区扩展与无障碍边界；不新增请求、不持久化。

## 3. 任务拆分

- [x] 3.1 匹配函数失败测试先行：建设银行示例、尾号不同排除、模糊错字、包含关系、无命中、排序键、置顶。
- [x] 3.2 真实浏览器用例先行：有建议时出现“建议”标题并按顺序排列、无建议时平铺（390/1440）、已确认建议置顶；键盘/读屏由 `heading()` 语义与不可聚焦标题保证，未做设备读屏。
- [x] 3.3 真实浏览器：打开账户下拉框，验证建议组顺序与视觉呈现。

## 4. 构建

- [x] 4.1 新增共享匹配函数 `importAccountSuggestions`（归一化、尾号、Jaro-Winkler、排序）。
- [x] 4.2 扩展 `ChoicePicker` 可选分区，保持无建议调用方行为不变。
- [x] 4.3 在 `CashImportSourceAccountEntry` 构建建议 ID、置顶已确认建议并传入分区函数；按来源名缓存结果。
- [x] 4.4 4 份 locale 新增“建议”文案；功能地图/导入文档同步说明。
- [x] 4.5 跨端影响：共享 Compose，Web 与 Native 同源；无后端、无 Native 专属实现。

## 5. 审查

- [x] 5a 范围化工程与产品复核：匹配规则、排序、尾号过滤、置顶、无建议回退、无障碍。
  - 匹配：空名称不产生建议；纯数字来源按相同尾号匹配；全角字符归一化；重复同名账户保留并按 ID 稳定排序；尾号不同硬排除；包含关系要求较短主体 ≥ 2；模糊要求 ≥ 3 且 0.75。
  - UI：只在存在建议时传入 `sectionOf`；无建议时 `ChoicePicker` 行为不变；标题不可聚焦；账户选项仍可按键盘选择。
  - 性能：`remember(matchName, accounts, suggestedAccountId, targetAccountId)` 缓存每个来源条目的结果；账户/来源数量受导入上限约束。
- [x] 5b 代码库防劣化检查：运行时未注册 `$codebase-degradation-review` / `$ui-reuse-review`（`skill` 返回 unknown），按技能文件执行等价只读检查。
  - 复用：扩展共享 `ChoicePicker` 的可选分区，没有复制下拉框；颜色/字体来自 `MaterialTheme`，无硬编码；分区模型只服务菜单标题，不构成万能组件。
  - 归属：匹配函数是导入专用的纯展示逻辑，放在 `presentation/ImportAccountSuggestions.kt`；`ChoiceSection` 属于共享菜单能力。
- [x] 5c 独立复核：`ImportAccountSuggestionsTest` + 390/1440 截图 + 独立只读审查。发现与处置：
  - **修复**：Jaro-Winkler 前剥离 `中国/银行/分行` 等通用词，避免“中国银行/中国建设银行/工商/农业”互相建议；补否定用例。
  - **修复**：卡号尾号改为“名称末尾或括号内最后一段 4 位以上数字的后 4 位”，覆盖掩码 `6225****1234`、带空格完整卡号与中部年份；补用例。
  - **回退**：全角“＋新建账户”文案不在本次改，避免与主规格 MUST 冲突；wasm 字体缺字形记为既有待修。
  - **补测**：已确认建议置顶加入 e2e（1440），无建议平铺覆盖 390/1440；分区成员由匹配函数单测保证，DOM 不暴露分组归属。
  - **文档**：`suggestion.account` 归入同一 PR 的 `reduce-cash-import-account-round-trips`，并更新 api-contract / redesign-contracts。
  - **说明**：空名称不再产生建议；重复同名账户保留并按 ID 排序；纯数字来源按相同尾号匹配。

## 6. 测试与 QA

- [x] 6.1 共享单测：`ImportAccountSuggestionsTest` 6 passed（建设银行变体、尾号硬排除、模糊错字、包含、无命中、已确认置顶）；共享全量 507 passed。
- [x] 6.2 `:webApp:compileKotlinWasmJs`、`:shared:wasmJsBrowserTest`、`:shared:compileKotlinIosSimulatorArm64` 均 BUILD SUCCESSFUL。
- [x] 6.3 真实浏览器（Playwright + 生产 wasm + Chrome）：390/1440 打开下拉框，截图 `account-suggestions-390.png`、`account-suggestions-1440.png`、`account-suggestions-none-390.png`；建议组顺序、分隔线、灰色“建议”、选择后生效；无建议时平铺且无标题；无控制台/网络错误。
  - 全量 `compose-cash-import.e2e.ts` **46 passed / 0 failed**（新增 390/1440 无建议用例）。
  - 截图路径：`compose/qa/web/test-results/compose-cash-import.e2e.ts-2f2d1-nts-are-suggested-first-390/account-suggestions-390.png`、`...-1440/account-suggestions-1440.png`、无建议 `.../account-suggestions-none-390.png` / `...-1440.png`。
- [x] 6.4 `openspec validate --all --strict` → 31 passed；`git diff --check` 干净。
- [x] 6.5 Hallmark `audit`（组件范围，读取 `references/verbs/audit.md`）：目标 390/1440 截图；**0 critical · 0 major · 0 minor**。
  - “建议”标题 `labelMedium` + `onSurfaceVariant`，对菜单底 rgb(238,238,240) 实测对比度约 7.94:1；分隔线可见；标题有 `heading()` 语义且不可聚焦。
  - 既有问题（不在本次范围）：wasm 字体对全角“＋”缺字形，截图显示为方框；未改文案，避免与主规格 MUST 冲突，另记待修。

## 7. 发布与反思

- [x] 7.1 交付：提交 `c8fc9ebd`，分支 `fix-cash-import-account-round-trips`，PR #110（base `main`）。回滚：移除建议分区与匹配函数，恢复平铺下拉；无接口、存储或迁移影响。剩余风险：简称/拼音/英文行名不覆盖；读屏未做实机验证；全角“＋”缺字形为既有问题。
- [x] 7.2 用户决策反思：本次确认“规则 + Jaro-Winkler 独立判定、尾号不同硬排除、已确认建议置顶、不限制数量、只有建议组显示灰色标题”。这些是本次业务方案；“建议只影响排序/分组、不改变选择语义”暂保留在 change 内，若其他选择器也要复用再写入通用规范。
