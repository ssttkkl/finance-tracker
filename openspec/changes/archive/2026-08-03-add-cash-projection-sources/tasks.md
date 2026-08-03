## 1. 思考与计划

- [x] 1.1 阅读词表、主规格、来源字段、列表、详情与测试；发现关系投影列表须展示全部成员来源，升级为 A 类接口合同变更。
- [x] 1.2 创建并修订 proposal、delta 规格和设计，明确 `source_types`、单源、关系投影和关联记录的来源显示边界。

## 2. 任务拆分与一致性

- [x] 2.1 将表格列、收支详情字段、关联记录字段列表、窄屏布局和回归测试映射为可验证任务。

## 3. 测试先行

- [x] 3.1 更新双后端列表契约、列表、详情和视觉测试，先要求来源列、有序去重来源与关联记录字段列表。
- [x] 3.2 运行受影响测试并确认详情尚未展示来源、关联记录仍为单行拼接；补齐实现后转绿。

## 4. 构建

- [x] 4.1 在列表快照中批量聚合成员来源并返回 `source_types`，增加来源展示和关系投影的成员来源去重逻辑。
- [x] 4.2 将关联记录重构为标签和值的字段列表，并调整表格与窄屏样式。

## 5. 审查

- [x] 5.1 完成产品/范围、工程和 Hallmark UI 审查。范围复核确认只展示来源和实际关联，不恢复审计结构；工程复核确认成员查询受活动数据集和工作区约束，且为当前页批量查询。Hallmark 审查发现关联记录的左侧粗色条触发「侧边色条卡片」反模式，已改为完整细边框；复审无 critical、major 或 minor finding。

## 6. 测试与 QA

- [x] 6.1 运行 Vitest、构建、Playwright、视觉测试、生产预览、OpenSpec 严格校验和 `git diff --check`。实际命令：`uv run pytest tests/test_application_cash_projection_evidence.py tests/contract/test_web_api.py tests/test_relational_cash_projection_evidence.py`（28 通过，5 个 PostgreSQL 场景因未设置 `FT_TEST_POSTGRES_URL` 跳过）；`npm test -- --run`（35 通过）；`npm run build`；`npm run test:e2e`（3 通过）；`npm run test:visual -- --update-snapshots` 与 `npm run test:visual`（各 10 通过）；`FT_PREVIEW_WEB_PORT=5180 npm run test:preview`（1 通过）；`openspec validate add-cash-projection-sources --strict`、`openspec validate --all --strict`、`openspec doctor` 和 `git diff --check` 均通过。另以 Playwright 截图人工检查 1440 px 和 390 px 的来源列、详情字段与关联记录，无横向溢出。`uv run pytest` 全量回归两次均在 10,000 条流水性能门禁开始后受当前执行窗口提前终止，未获得最终汇总；不将其记为通过。完整验证需要允许该命令在较长窗口完成。真实 PostgreSQL 补跑条件：配置仅指向测试库的 `FT_TEST_POSTGRES_URL` 后重跑上述 Python 契约命令。

## 7. 发布准备

- [x] 7.1 记录回滚与交付证据；不执行提交、推送或部署。回滚方式是还原本次列表 `source_types` 合同、前端来源展示与测试改动；该字段为新增响应字段，既有 `source_type` 保持不变。

## 8. 反思

- [x] 8.1 记录来源集合和字段化关联记录的防回归结论：以双来源退款关系固定 `source_types` 的成员顺序与去重；以列表、详情、无障碍和视觉测试固定单源回退、关系标记与每条关联记录的独立字段展示。
