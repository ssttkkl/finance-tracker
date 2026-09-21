## 1. 思考与范围

- [x] 1.1 复核真实 `~/.ft/bills` 微信/支付宝复现样本、主规格、项目上下文和设计决策，确认五笔独立收入的三类根因。
- [x] 1.2 记录 `grill-me` `/grilling` 会话不可由当前运行时调用，以及本次已明确的目标、非目标、验收标准和残余风险。

## 2. 失败回归测试

- [x] 2.1 为同一支付宝原始支出的两笔部分退款添加失败测试，要求两笔均生成 `refund_offset` 且精确金额合计不超额。
- [x] 2.2 为支付宝 `investment_out` 原始支出与订单退款添加失败测试，并验证普通非支付宝/无订单流水不被放行。
- [x] 2.3 为微信红包/转账退回的两条 `transfer_reversal` 添加失败测试，验证同交易标识可配对、缺标识或仅文本不可配对。
- [x] 2.4 为导入关系计划和收支投影添加失败测试，验证全额平台退回不再形成独立收入、重复计划保持幂等。

## 3. 最小实现

- [x] 3.1 将支付宝 Phase A 的单一 `used_origin` 改为按订单唯一性和 `Decimal` 剩余金额控制，传递既有活动退款的剩余容量。
- [x] 3.2 仅在支付宝硬订单键路径允许 `investment_out` 作为退款原始支出，并保留原流水类型、金额和来源快照。
- [x] 3.3 增加微信 `transfer_reversal` 精确交易标识配对路径，生成 `refund_offset` 的 `p2p_return` 子类型并保留原分类。
- [x] 3.4 保持通用商户退款、支付镜像、转账配对和账单转换器的既有排除边界，不引入新表、迁移或依赖。

## 4. 一致性与契约

- [x] 4.1 检查导入预览、关系检查和持久化使用同一平台匹配结果；确认关系业务键、规则版本、来源证据和工作区边界不变。
- [x] 4.2 检查 `refund_offset` 的全额隐藏、部分冲销净额和关联流水详情，确认不改写任何原始现金流水。

## 5. 审查

- [x] 5.1 完成范围审查：仅覆盖三类已复现退款根因，确认账户映射和无结构化证据的文本退款不在范围内。
- [x] 5.2 完成工程审查：复核候选唯一性、时间方向、币种/金额精度、剩余容量、幂等、回滚与 SQLite/PostgreSQL 等价性。
- [x] 5.3 复核最终差异、OpenSpec 与实现一致性，并按严重级别记录 finding、处置和回写位置。

## 6. 测试与 QA

- [x] 6.1 运行新增回归、平台退款匹配、关系计划、导入和收支投影的 SQLite 测试；变基后结果为 `140 passed`，保留 1 个既有 warning。
- [x] 6.2 运行 `openspec validate --all --strict`（35 passed）、`openspec doctor`、`PYTHONPATH=.:src python -m compileall -q src tests`、Web 单测（143 passed）、Web production build 和 `git diff --check`。
- [x] 6.3 在生产预览中用真实浏览器验证导入后列表、关系详情、正常/空状态、键盘 Escape/焦点和 1440/390 宽度；最终基线生产预览 11 项、受影响主流程 10 项全部通过，并复跑完整 E2E（36 passed，1 个既有暗色模式断言失败）；记录 URL、截图路径、控制台/网络错误。
- [x] 6.4 用同一 Application Service 和去标识化夹具运行真实 PostgreSQL 契约矩阵；专用数据库 `finance_tracker_refund_test` 名称符合 `_test` 约束，结果为 4 个 accepted `refund_offset` 关系，随后清理专用测试 schema。

## 7. 发布与合入

- [x] 7.1 记录变基后验证的当前 `HEAD`、比较基线、命令、结果、未解决风险和回滚方式。
- [x] 7.2 创建聚焦本地提交 `71cb9e0` 并推送修复分支，创建 base 为 `refactor/web` 的 PR #71。
- [x] 7.3 检查 PR #71：GitHub 未配置自动检查项（`gh pr checks 71 --watch` 返回 no checks reported），PR 状态为 `MERGEABLE/CLEAN`；已合入 `refactor/web`，合入提交为 `5ae1ef73d16f4ecb506f16797d969d648c5bcd48`，并确认修复提交是目标分支祖先。

## 8. 反思

- [x] 8.1 沉淀防复发测试覆盖：多笔部分退款、平台原生退回、投资退款和无硬证据排除路径。

## 验证记录

- 根因复现：在全新 SQLite 账本中导入 `~/.ft/bills` 的 3 份支付宝 CSV 和 3 份微信 XLSX，共 6,390 条流水。修复前五笔目标退回均未形成有效 `refund_offset`；修复后五笔均各匹配一次，规则分别为 `scan.alipay.order_prefix.v1` 和 `scan.wechat.transfer_return.v1`。同一支付宝原始支出的 3 组多笔部分退款也全部按剩余金额配对。
- SQLite：`PYTHONPATH=.:src python -m pytest -q tests/test_platform_refund_matchers.py tests/test_transaction_relations_refund.py tests/test_import_relation_planning.py tests/test_import_scan_refund_boundary.py tests/test_record_type.py tests/test_transaction_relations_transfer.py tests/test_cash_projection.py` → `129 passed`，1 个既有 warning。
- Web 单测与构建：在 `web/` 执行 `npm ci`、`npm test -- --run` → `108 passed`；`npm run build` → Vite production build 成功。
- 浏览器：`FT_PREVIEW_WEB_PORT=5185 FT_PREVIEW_API_PORT=8767 npm run test:preview` → 7 passed，生产预览 URL 为 `http://127.0.0.1:5185`，覆盖列表、详情、导入处理页、正常/空状态和 390 px；截图为 `/tmp/cash-import-production-1440.png`、`/tmp/cash-import-production-390.png`。`FT_E2E_WEB_PORT=5186 npm run test:e2e -- --grep='默认折叠|追加失败|筛选后|详情切换|关联流水|导入处理页面|四个目标宽度|浏览器本地时区|新建流水|查看抽屉'` → 10 passed，覆盖 1440、390、320、375、414、768 px、键盘重试、详情焦点和导入流程。完整 E2E 为 26 passed、1 failed；失败是未改动的暗色模式文字颜色计数断言（期望 7 个、实际 8 个），不属于本变更。
- PostgreSQL：在本机 `quantdinger-db` 容器中新建专用 `finance_tracker_refund_test` 数据库（名称以 `_test` 结尾），使用同一 `RelationService`/`RelationalUnitOfWork` 运行多笔部分退款、支付宝投资退款和微信退回夹具 → `4 accepted refund_offset relations`；随后清理专用测试 schema。未触碰现有业务库。
- 全量后端（变基前）：`PYTHONPATH=.:src python -m pytest -q` → `1440 passed, 178 skipped, 14 failed`。失败均为既有环境/测试依赖问题：`tests` 名称与 site-packages 同名导致测试辅助模块无法导入、缺少 `xlwt`，以及与本次修改无关的查询余额断言；受影响退款测试已单独通过。
- OpenSpec/静态检查：`openspec validate --all --strict` → 27 passed；`openspec doctor` → root ok；`python -m compileall -q src tests` 和 `git diff --check` → 通过。
- 审查结论：范围、工程和最终差异均未发现阻断性 finding；保留的已知风险是完整 E2E 的既有暗色模式断言、全量回归的环境性失败，以及当前仅通过本地专用 PostgreSQL 容器验证。未修改 UI、账户映射、导入转换器或花呗还款规则。
- 初始验证基线：变基前 `HEAD=efa313b3e604b38c0dbbd65582da8d84be55ef1e`、比较基线为 `origin/refactor/web=71621282b8add3046bfc69eef009c4021124a7c0`；相关测试、生产预览和真实账单校验已在下方“变基后复核”中按最新基线重跑。
- 回滚：回退本次代码提交即可停止新规则生成；不删除原始现金流水或既有关系。

### 变基后复核（2026-09-04）

- 变基完成后 `HEAD=6a5e7bcc267515a7c54383e5e8b4984c9677b8bb`，比较基线为 `origin/refactor/web=0a7c01b785a8ff77a8e088a6a5aa5f682989e5f2`；`git diff --check` 通过，差异仍仅包含本变更的 14 个文件。
- 真实账单回放：读取同一组六份微信/支付宝账单共 6,391 条可匹配流水，Phase A 生成 153 条提案；2023-06-23 `+20.90`、2023-07-18 `+9.53`、2025-01-28 `+300`、2025-05-16 `+50`、2026-02-25 `+89.12` 五笔目标均各匹配一次；其中 2025-05-16 规则为 `scan.wechat.transfer_return.v1`、子类型为 `p2p_return`，支付宝有 5 个原始支出存在多笔部分退款配对。
- 变基后真实导入链路：在临时 SQLite 数据库中通过 `StatementParser`、`StatementImportService` 和 `RelationService` 导入同一组六份账单，共持久化 6,390 条流水和 231 条 accepted `refund_offset` 关系；五笔目标退款均已配对，临时数据库随测试结束释放。
- 变基后 SQLite 命令 `PYTHONPATH=.:src python -m pytest -q tests/test_platform_refund_matchers.py tests/test_transaction_relations_refund.py tests/test_import_relation_planning.py tests/test_import_scan_refund_boundary.py tests/test_record_type.py tests/test_transaction_relations_transfer.py tests/test_cash_projection.py` → `140 passed`。
- 最终基线 Web 命令 `npm test -- --run` → `14 files passed / 143 tests passed`；`npm run build` → Vite production build 成功；`FT_PREVIEW_WEB_PORT=5188 FT_PREVIEW_API_PORT=8768 npm run test:preview` → 11 passed；`FT_E2E_WEB_PORT=5189 npm run test:e2e -- --grep='默认折叠|追加失败|筛选后|详情切换|关联流水|导入处理页面|四个目标宽度|浏览器本地时区|新建流水|查看抽屉'` → 10 passed；`FT_E2E_WEB_PORT=5190 npm run test:e2e` → 36 passed、1 failed，失败仍为未改动的暗色模式侧栏文字颜色数量断言（期望 7、实际 8）。生产预览 URL 为 `http://127.0.0.1:5188`，截图路径为 `/tmp/cash-import-production-1440.png`、`/tmp/cash-import-production-390.png`；未观察到本变更引起的控制台或网络错误。
- 变基后 PostgreSQL：使用 `FT_TEST_POSTGRES_URL` 指向本机专用 `finance_tracker_refund_test`，执行同一 `RelationService`/`RelationalUnitOfWork` 夹具 → `4 accepted refund_offset relations`，随后仅清理该测试库 schema。
- 变基后 OpenSpec：`openspec validate --all --strict` → `35 passed`；`openspec doctor` → root ok；`PYTHONPATH=.:src python -m compileall -q src tests` → 通过。未解决风险仍为完整 E2E 的既有断言、全量后端测试的环境/依赖失败；PR #71 未配置自动检查项，GitHub 合入状态为 `MERGED`。
- 全量后端（变基后）：`PYTHONPATH=.:src python -m pytest -q` → `1495 passed, 189 skipped, 14 failed`。14 个失败仍集中于同名 `tests` 包导致的测试辅助模块导入、缺少 `xlwt`、与本次修改无关的查询余额断言；没有退款相关失败。
