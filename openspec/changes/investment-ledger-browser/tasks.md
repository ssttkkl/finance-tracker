# Tasks

## 1. 思考

- [ ] 1.1 阅读 `investment-event-model`、`ledger-records`、`portfolio-valuation`、`time-semantics`、现有收支账本 Web、投资查询代码和测试，确认当前行为与缺口。
- [ ] 1.2 使用 `$openspec-explore` 复核核心任务、范围、失败模式、数据隐私和不改变投资事实的边界。

## 2. 计划

- [ ] 2.1 复核 proposal、delta spec 与 design 的一致性，明确 API、数据流、分页、局部失败、兼容、回滚和安全策略。
- [ ] 2.2 使用 Hallmark 创建 `prototype/index.html`，覆盖事件、持仓、筛选、加载、空、错误、行情受限和证据详情，并完成 320、375、414、768 px 检查与设计复核。

## 3. 任务拆分与一致性

- [ ] 3.1 为每条 requirement 和 scenario 建立失败测试、实现、审查与验证任务映射，确认原型、proposal、spec 和 design 无矛盾。
- [ ] 3.2 固定 SQLite 与真实 PostgreSQL 契约夹具、工作区隔离样例、精确十进制样例、版本化分页和行情局部失败验收数据。

## 4. 构建

- [ ] 4.1 先增加失败测试，再实现投资事件筛选、稳定分页和当前页批量关系摘要的 Application Service 与 persistence adapter。
- [ ] 4.2 先增加失败测试，再实现工作区隔离的投资事件证据详情与脱敏 Web API。
- [ ] 4.3 先增加失败测试，再复用组合查询实现当前持仓与有界估值状态响应，确保局部行情失败不阻塞事件列表。
- [ ] 4.4 以已批准原型实现投资事件、当前持仓、筛选、分页和证据详情 Web 交互，保持十进制字符串和浏览器本地时间语义。

## 5. 审查

- [ ] 5.1 完成产品与范围复核，检查核心任务、非目标、成功标准和未实现行为没有被提前写入主规格。
- [ ] 5.2 完成工程与安全复核，检查查询边界、N+1、预算、分页一致性、工作区隔离、证据最小披露、兼容和回滚。
- [ ] 5.3 使用 `$hallmark audit <target>` 审查最终 UI，修复全部 critical 与 major finding 后重新 audit。
- [ ] 5.4 完成最终 diff 复核，按严重级别记录 finding、处理结论和 artifact 回写位置。

## 6. 测试与 QA

- [ ] 6.1 运行新增回归、受影响测试、SQLite 与真实 PostgreSQL 契约矩阵、完整 Python 回归、Web Vitest、构建和 `git diff --check`。
- [ ] 6.2 运行生产预览与 Playwright，覆盖主流程、加载、空、错误、行情受限、键盘、焦点恢复和 320、375、414、768 px 响应式行为。
- [ ] 6.3 运行 `openspec validate --all --strict` 与 `openspec doctor`，记录当前 `HEAD`、比较基线、实际命令、结果和残余风险。

## 7. 发布准备

- [ ] 7.1 记录路由与 API 回滚、观察项和未解决风险；未经用户明确授权不提交、不推送、不创建 PR、不部署。

## 8. 反思

- [ ] 8.1 归档前同步 delta；沉淀投资浏览查询、证据安全、局部失败和 UI 验证中的可复用规则。
