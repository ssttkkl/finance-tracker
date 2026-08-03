# 任务：收支投影来源标识

## 1. 思考

- [x] 确认现有投影合同已提供 `composition` 和 `member_count`，无需改变投影构建或 API。
- [x] 在词表确定「单源投影」与「关系投影」的边界和中文文案。

## 2. 计划

- [x] 记录范围、非目标、验收、数据流、风险和 UI 策略。

## 3. 任务拆分与一致性

- [x] 覆盖失败测试、主列表、证据详情、样式、审查和验证。

## 4. 构建

- [x] 先新增单源和关系投影的前端回归测试。
- [x] 在主列表和证据详情展示来源标签及成员数。

## 5. 审查

- [x] 复核术语、判断边界、窄屏可读性和最终范围化 diff。

审查范围为 `DOMAIN_GLOSSARY.md`、收支列表、证据详情、样式和前端测试。工程复核确认前端只读取 `composition` 与 `member_count`，未重算关系、金额或成员。Hallmark UI 审计覆盖 `web/src/components/CashTable.tsx`、`web/src/components/EvidenceDetail.tsx` 和 `web/src/styles.css`：0 critical、0 major、0 minor；现有 `Workbench / Ledger Grid` 标记与实际界面一致，新增标签以文字和成员数传达语义，并在 390 px 截图中保持可读。没有阻断性 finding。

## 6. 测试与 QA

- [x] 运行前端单元测试、类型构建、Playwright、`git diff --check` 和 OpenSpec 校验。

执行基线：`HEAD` 为 `dd77c95`，比较基线为 `8c18ed7ecff6b31cd5adcc18becb4e4e09035f55`。

- `cd web && npm test`：4 个文件、33 项测试通过。
- `cd web && npm run build`：TypeScript 与 Vite 生产构建通过。
- `cd web && npx playwright test`：3 项收支账本交互测试通过，包含 320、375、414、768、1024、1440 px 无横向溢出检查。
- `cd web && npx playwright test -c tests/playwright.visual.config.ts`：10 项视觉测试连续通过；覆盖 1440、1024、768、390 px 的列表、抽屉、空态、错误态和加载态。
- `cd web && FT_PREVIEW_WEB_PORT=5180 npm run test:preview`：1 项生产预览测试通过。为避免占用用户另一工作树的 5173 端口，预览配置支持以 `FT_PREVIEW_WEB_PORT` 隔离端口，并向 API 传递匹配的 CORS 来源。
- `openspec validate --all --strict`：26 项规格和变更通过；`openspec doctor`：根目录健康；`git diff --check`：通过。

未运行 PostgreSQL 双后端矩阵：本变更没有持久化、投影构建或 API 合同改动；残余风险仅为服务端返回违反既有投影合同的数据，此类情况仍由后端合同负责拒绝。

## 7. 发布

- [x] 记录交付证据；未获用户明确授权，不提交、推送、创建 PR 或部署。

工作树保留为待交付状态；没有执行提交、推送、创建 PR 或部署。

## 8. 反思

- [x] 记录可复用结论：前端只展示服务端已确定的投影成员数和组成关系；视觉测试须等待抽屉入场动画结束后再取快照，避免把过渡帧固化为基线。
