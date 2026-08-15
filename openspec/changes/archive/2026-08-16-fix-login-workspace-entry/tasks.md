## 1. 思考与计划

- [x] 1.1 完成 `/grilling` 需求澄清并记录目标、非目标、验收场景和根因；当前运行时未提供可执行的 Skill session API，澄清结论已回写本变更 artifacts。
- [x] 1.2 读取 UI 约束、领域词表、认证壳、工作区入口规格与现有测试，确认本次为 C 类局部缺陷修复且不需要 UI 原型。

## 2. 构建（测试先行）

- [x] 2.1 先新增已有工作区自动进入、无工作区隐藏返回按钮和已有工作区创建后可返回的失败回归测试；实现前 `npm test -- --run tests/AccessApp.test.tsx` 为 16 tests 中 2 failed（已有工作区登录入口、无工作区返回按钮）。
- [x] 2.2 实现会话无活动工作区时选择第一个可访问工作区，并按流程条件渲染创建页返回按钮。
- [x] 2.3 补充默认工作区选择失败时不得进入创建页的失败回归测试；补测前 `npm test -- --run tests/AccessApp.test.tsx` 为 18 tests 中 1 failed（缺少错误/重试状态）。
- [x] 2.4 实现选择失败的可重试错误状态，确保只有空工作区列表进入创建页；补测后 `AccessApp.test.tsx` 18/18 通过。

## 3. 审查

- [x] 3.1 独立复核范围、状态分支、工作区授权边界、键盘焦点和 UI 可见文字；0 critical、0 major、0 minor finding。自动选择仍通过既有服务端成员选择接口，返回按钮只在已有活动工作区进入创建流程时出现，现有 `button:focus-visible` 样式继续生效，未新增实现术语或冗余说明。
- [x] 3.2 对最终认证壳执行 Hallmark `audit`；运行时未提供可调用的 Hallmark audit action，已按其 audit 规则对 `web/src/AccessApp.tsx`、相关样式和 390/1440 px 截图完成人工等价审查，0 critical、0 major、0 minor finding，未声称执行了不可用动作。

## 4. 测试与 QA

- [x] 4.1 运行受影响 Vitest、TypeScript/生产构建和 `git diff --check`：`cd web && npm test -- --run` 为 10 files/124 tests passed；`cd web && npm run build` 通过；`git diff --check` 通过。
- [x] 4.2 使用真实 Playwright Chromium 覆盖登录后已有工作区、无工作区创建页、返回按钮交互及 390/1440 px 响应式状态：`FT_E2E_WEB_PORT=5195 npm run test:e2e -- --reporter=line tests/workspace-entry.e2e.ts` 为 3 passed；`FT_PREVIEW_WEB_PORT=5196 FT_PREVIEW_API_PORT=8778 npm run test:preview -- --reporter=line` 为 10 passed（含 3 条入口回归）。`/tmp/fix-login-workspace-entry-390.png` 与 `/tmp/fix-login-workspace-entry-1440.png` 已截图并人工检查，页面无横向溢出；控制台仅有未登录会话探测的预期 401，无意外 console 或 request failure。最终全量 E2E（`FT_E2E_WEB_PORT=5200 npm run test:e2e -- --reporter=line`）为 33 passed、1 failed，唯一失败是既有暗色侧栏颜色断言期望 7 个节点而当前实际有 8 个导航节点，与本变更无关。
- [x] 4.3 运行 `openspec validate --all --strict` 与 `openspec doctor`：29 passed、0 failed，doctor 根目录正常；验证前 `HEAD=c2456dbc7a2f1dc78488dd6de2a62741b0ced57e`，比较基线 `origin/refactor/web` 同为该提交；未涉及数据库、迁移或 PostgreSQL 矩阵。

## 5. 发布与反思

- [x] 5.1 记录前端发布准备、回滚条件和外部动作范围：本次只需前后端兼容发布前端；回滚恢复 `AccessApp.tsx` 与入口回归测试配置即可；本次按用户授权提交、推送并创建目标为 `refactor/web` 的 PR，不直接合并或部署。
- [x] 5.2 记录防复发规则：认证后必须按工作区列表与活动 ID共同决定入口，工作区选择失败不得落到创建页，无目标的创建流程不得显示无效返回操作；自动化覆盖登录、会话恢复、选择失败、首次创建和已有工作区创建返回。
