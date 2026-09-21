## 1. 思考

- [x] 1.1 阅读 `openspec/project-context.md`、`openspec/specs/cash-ledger-browser/spec.md`、`docs/ui-design-rules.md`、生产入口和现有 Web 样式，确认这是不涉及业务数据的 B 类 Web 行为变更。
- [x] 1.2 显式读取并尝试进入项目 `grill-me` 的 `/grilling` session；当前运行时只提供技能声明文件，没有可执行 session API，已依据用户明确要求锁定目标为生产入口禁止双指缩放，非目标为字号、布局和原型。

## 2. 计划

- [x] 2.1 完成 proposal、`cash-ledger-browser` delta spec 和 design，记录 `maximum-scale=1.0` / `user-scalable=no`、可访问性取舍和回滚方式。
- [x] 2.2 确认本次只改 viewport 元数据，不新增原型、不改视觉布局，因此不触发 A 类 UI 原型门禁。

## 3. 任务拆分与一致性

- [x] 3.1 核对 requirement、场景、失败回归测试、生产入口改动、浏览器验证和回滚记录均有对应任务。

## 4. 构建

- [x] 4.1 先新增生产入口 viewport 的失败回归测试，并确认在缺少禁止缩放参数时失败；Vitest 定向运行按预期因入口缺少禁止缩放参数失败。
- [x] 4.2 在 `web/index.html` 增加禁止用户缩放的 viewport 参数，保持现有设备宽度和初始比例；viewport 定向回归测试已转绿。

## 5. 审查

- [x] 5.1 独立复核产品范围、工程边界、可访问性风险和最终 diff；确认未修改字号、布局、原型或其他页面。`git diff --check` 通过，范围符合 proposal/design。
- [x] 5.2 目标为仅含 viewport 元数据的生产入口；当前运行时没有可调用 Hallmark `audit` action API，已按 audit 规则人工审查 `web/index.html`，结论为 0 critical、0 major、0 minor；若获得可执行 action，补跑条件为对该入口执行 `hallmark audit`。

## 6. 测试与 QA

- [x] 6.1 运行 viewport 定向回归测试和完整 Web Vitest：定向 1 passed；完整 Web Vitest 11 files / 120 tests passed。
- [x] 6.2 运行 Web TypeScript 检查、生产构建和 `git diff --check`：`npm run build` 通过，`git diff --check` 通过。
- [x] 6.3 在生产预览中用真实 Chromium 检查 390 px 与 1440 px：`npm run test:preview` 8 passed；viewport 元数据、页面级宽度、控制台和网络失败检查均通过。自动化无法模拟真实手机双指动作，保留该残余风险。
- [x] 6.4 运行 `openspec validate --all --strict` 和 `openspec doctor`：28 passed / 0 failed，OpenSpec root ok。

## 7. 发布

- [x] 7.1 记录实施阶段 `HEAD` 与比较基线均为 `c2456dbc7a2f1dc78488dd6de2a62741b0ced57e`，分支为 `fix-mobile-webpage-zooming`；已完成定向/全量 Vitest、生产构建、Playwright 预览、OpenSpec 校验、doctor 和 `git diff --check`。实施阶段未执行提交、推送或部署；本轮用户已明确授权创建并合入目标为 `refactor/web` 的 PR，交付动作按本轮 ship 流程执行。回滚为移除 viewport 中新增的 `maximum-scale` / `user-scalable` 参数。未解决风险：真实手机物理双指手势未由自动化模拟；`npm ci` 报告 1 个 high severity audit advisory，本变更未改依赖。

## 8. 反思

- [x] 8.1 记录“禁用缩放影响依赖页面放大的使用者”这一可复用的产品与可访问性取舍；本次按用户明确要求接受该 trade-off。
