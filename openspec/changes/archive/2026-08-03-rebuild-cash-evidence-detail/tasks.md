## 1. 思考与计划

- [x] 1.1 阅读收支账本规格、词表、现有抽屉、测试和项目上下文，确认本次为 A 类 UI 重建且不改变投影或 API。
- [x] 1.2 更新词表，定义收支详情、关联记录与审计信息的边界。
- [x] 1.3 创建 proposal、delta spec 与 design，记录目标、非目标、数据映射、风险和回滚路径。

## 2. 原型与一致性

- [x] 2.1 使用 Hallmark 预检并建立 `prototype/index.html`，覆盖关系投影、单源、无关联、加载、错误、审计展开和焦点状态。
- [x] 2.2 在 320、375、414、768 px 截图检查原型，无横向滚动或文本溢出。
- [x] 2.3 复核原型与 delta spec、词表的术语和信息层级一致。

## 3. 测试先行

- [x] 3.1 更新组件测试，先锁定默认收支详情、关联记录和折叠审计信息的可见边界。
- [x] 3.2 扩展无障碍测试，覆盖审计展开后的键盘焦点和既有模态隔离契约。

## 4. 构建

- [x] 4.1 重构 `EvidenceDetail`，用现有 `Evidence` 数据生成收支详情、关联记录和审计信息。
- [x] 4.2 更新抽屉样式，适配宽屏和窄屏信息层级，保留现有 token、动画和减少动态效果行为。
- [x] 4.3 更新视觉测试及快照，覆盖新的默认抽屉与关系形成内容。

## 5. 审查

- [x] 5.1 完成产品/范围与工程范围复核，确认未改变投影、关系或 API 行为。
- [x] 5.2 按 Hallmark 的 `audit` 技能流程复核目标文件，确认没有 critical、major 或 minor finding。
- [x] 5.3 完成最终 diff 复核，检查术语、无障碍、响应式和既有未提交改动的边界。

## 6. 测试与 QA

- [x] 6.1 运行受影响 Vitest、Web 构建、Playwright 交互与视觉测试、生产预览及 `git diff --check`。
- [x] 6.2 运行 `openspec validate --all --strict` 与 `openspec doctor`，记录实际命令、结果、基线和残余风险。

## 7. 发布准备

- [x] 7.1 记录交付证据与回滚方式；本次无数据库迁移、无外部写入或部署授权。

## 8. 反思

- [x] 8.1 记录可复用的侧滑框信息分层决策和防回归测试。

## 验证与审查证据

- 基线：`dd77c95`；本次只重组前端抽屉与测试，不修改投影计算、关系匹配、HTTP 接口、数据库或持久化数据。
- 产品与工程复核：默认首屏只回答“这笔收支是什么、怎样形成、有哪些关联事实”；完整成员、来源快照、规则和未生效关系仍在审计信息中只读保留。`Evidence` 字段未变，前端不重新推断关系。
- Hallmark `audit` 技能复核范围：`web/src/components/EvidenceDetail.tsx`、`web/src/styles.css` 与原型。结果：0 critical、0 major、0 minor。保留 Workbench / modern-minimal / Cobalt token；无新增渐变、装饰性图形、泛化卡片堆叠、`transition-all`、悬停专属功能或无焦点控件。抽屉开闭动画具有空间反馈用途，并在减少动态效果下关闭。
- 验证命令与结果：`npm test -- --run`（33 项通过）；`npm run build`（通过）；`npx playwright test`（3 项通过）；`npm run test:visual`（10 项通过，覆盖 1440、1024、768、390 px 的抽屉快照）；`FT_PREVIEW_WEB_PORT=5180 npm run test:preview`（1 项通过）；`openspec validate rebuild-cash-evidence-detail --strict`（通过）；`openspec validate --all --strict`（26 项通过）；`openspec doctor`（根目录健康）；`git diff --check`（通过）。
- 残余风险：自动化浏览器检查使用当前 Playwright 浏览器；未单独执行 Firefox 或 WebKit。长来源快照由 390 px 快照和断词样式覆盖。
- 回滚：回退本次的 `EvidenceDetail`、样式、测试、视觉配置与快照即可恢复旧抽屉；无数据库、API 或外部状态需要回滚。未进行提交、推送、部署或其他外部写入。

## 反思

- 面向日常操作的抽屉先展示业务结果和关联事实，把实现结构留给按需审计；这能保留可追溯性而不把内部模型作为使用门槛。
- 将审计内容条件挂载并覆盖其展开前后状态，能防止底层字段重新回到首屏，也使焦点循环随着可用控件变化保持正确。
