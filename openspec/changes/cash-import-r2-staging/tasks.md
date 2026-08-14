## 1. 规格与配置

- [x] 1.1 校验 proposal、delta specs、design 与现有现金导入合同一致，并记录 Render + R2 + Neon 的配置边界
- [x] 1.2 增加显式 R2 配置项、生产缺失配置失败关闭和本地/测试内存存储开关
- [x] 1.3 增加 R2 私有 Bucket、对象前缀、TTL 和生命周期规则的部署说明，不把密钥写入仓库

## 2. 临时会话存储（先测试）

- [x] 2.1 先写存储端口合同测试：随机会话、对象读写、TTL、完成、删除和不存在错误
- [x] 2.2 先写会话安全测试：工作区绑定、用户绑定、摘要/渠道校验、过期和单次消费
- [x] 2.3 实现内存存储适配器，供单元测试与显式本地开发使用
- [x] 2.4 增加 R2 S3-compatible 存储适配器、最小对象操作和受控异常映射
- [x] 2.5 增加对象大小、会话数量、令牌熵、敏感日志和 TTL 清理测试

## 3. Application 导入会话（先测试）

- [x] 3.1 先写扫描、密码重试和预览引用令牌的 Application Service 测试，证明前置步骤不写业务数据库
- [x] 3.2 先写最终确认从会话读取原始来源、提交完整映射和关系的单事务测试
- [x] 3.3 先写幂等重试测试：同一会话与幂等键重复提交只产生一次业务写入
- [x] 3.4 实现会话创建、来源保存、扫描草稿和预览草稿的服务流程
- [x] 3.5 实现最终确认的会话读取、人工关系覆盖、幂等响应和成功/失败清理边界

## 4. Web API（先测试）

- [x] 4.1 先写扫描、密码重试、预览和提交的请求/响应契约测试，确认后续请求不接收原始文件正文
- [x] 4.2 先写令牌越权、过期、摘要失效、存储错误和幂等冲突的错误合同测试
- [x] 4.3 改造现金导入路由，支持会话令牌和 `Idempotency-Key`，保留受控旧请求兼容窗口
- [x] 4.4 确认错误响应和日志不会暴露密码、令牌、对象键、完整账号或账单正文

## 5. Web 前端

- [x] 5.1 先更新 API 类型和组件测试，断言扫描后保存令牌、预览/确认不重复上传文件
- [x] 5.2 修改导入页面状态，使密码重试、映射预览和最终确认使用令牌
- [x] 5.3 只提交关系决定（含未修改的自动决定）和必要映射，确认请求不生成大文件 Base64 JSON
- [x] 5.4 增加提交失败后的安全重试行为，复用同一幂等键和会话，不丢失用户决策
- [x] 5.5 将导入页嵌入统一应用外壳，恢复移动顶栏、完整侧边导航和 PR 44 的文件选择/预览/配对样式
- [x] 5.6 按 Hallmark `audit` 规则复核 `web/src/App.tsx`、`web/src/pages/CashImportPage.tsx` 和导入相关 CSS；无 critical / major / minor finding，统一外壳、页面结构、焦点样式和响应式边界均与现有设计系统一致

## 6. 验证与发布准备

- [x] 6.1 运行新增单元、契约和回归测试，覆盖正常、空、错误、过期、重复和恢复路径；此前全量结果为 `1460 passed, 176 skipped, 1 warning`
- [x] 6.2 运行 SQLite 与 `FT_TEST_POSTGRES_URL` 指向 `_test` 数据库的同一导入事务矩阵；`tests/contract/test_cash_import_dual_backend.py tests/test_alembic_migration.py` 结果为 `18 passed`
- [x] 6.3 运行 Web 测试、构建和真实浏览器 QA：`npm test` 为 `10 files / 107 passed`，`npm run build` 通过；浏览器使用 `http://127.0.0.1:5187/cash-import`，覆盖 320/375/390/414/768/1440，1440 与 390 截图分别为 `/tmp/ft-r2-shell-1440-preview.png`、`/tmp/ft-r2-shell-390-preview-viewport.png`，选择文件与配对复核截图为 `/tmp/ft-r2-shell-390-select-fixed.png`、`/tmp/ft-r2-shell-1440-relations-fixed.png`；页面无横向溢出，表格仅在自身容器内滚动，菜单、账户入口和键盘焦点可用，实际微信账单预览为 985 条、4 条自动关系
- [x] 6.4 运行 `openspec validate --all --strict`（25 passed, 0 failed）、`openspec doctor`（root ok）、`git diff --check` 并完成范围化 diff 复核
- [x] 6.5 验证时 `HEAD` 为 `d9a24e6f71b5f8382a90ffa5b6528fe6b448eb3c`，比较基线为 `f5b0398ddf87cc0cf497a9e816cda898f0446b95`；生产 R2 配置前提、临时对象清理和密码账单浏览器验证风险仍按 design 记录，本次提交、推送与合入按当前发布流程完成
